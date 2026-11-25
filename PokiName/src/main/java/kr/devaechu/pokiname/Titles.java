package kr.devaechu.pokiname;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 칭호. 두 종류 — 이미지(리소스팩 배지) / 글씨(리소스팩 없이 게임 안에서 바로 만듦). 칸이 따로라 하나씩 같이 장착.
 * - titles.yml      : 칭호 정의 (id, 종류, 이름, 배지 글자 코드 또는 글씨 색, 채팅·Tab 용 글씨)
 * - player-titles.yml : 플레이어별 가진 칭호 / 칸별 장착 칭호 (equipped-image, equipped-text)
 * 머리 위: 이미지 배지는 텍스트 디스플레이로 이름표 위에, 글씨 칭호는 한글닉 이름표 안 한 줄 위 (엔티티 안 늘림).
 * Tab: 이미지 칭호(chat 색 글씨) > 글씨 칭호 순으로 하나만.
 * 파일 쓰기는 바뀔 때만, 비동기(한 줄 작업자 스레드 — 저장 순서 유지).
 */
final class Titles implements CommandExecutor, TabCompleter {

    static final String TAG = "pokiname_title";
    static final String ADMIN = "pokiname.title.admin";
    /** 기본 titles.yml 의 version — 기본 칭호 목록을 바꿀 때 올림 (서버의 옛 titles.yml 을 새 것으로 교체) */
    static final int DEFS_VERSION = 5;

    /** text=true 면 글씨 칭호 (glyph 없음, color = #색 또는 #색1-#색2) */
    record Def(String id, String name, String glyph, String chat, String tier, boolean announceFirst, boolean text, String color) { }

    /** 서버 최초 획득 기록 */
    record First(UUID uuid, String name, String at) { }

    private static final class Owned {
        String name;
        final Set<String> owned = new LinkedHashSet<>();
        /** 이미지 칸 */
        String equipped;
        /** 글씨 칸 */
        String equippedText;
    }

    /** 파일 쓰기 전용 (메인 스레드에서 파일 안 씀) */
    private final java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PokiName-titles-io");
        t.setDaemon(true);
        return t;
    });

    private final PokiName plugin;
    private final Map<String, Def> defs = new LinkedHashMap<>();
    private final Map<UUID, Owned> players = new LinkedHashMap<>();
    private final Map<UUID, TextDisplay> displays = new HashMap<>();
    private final Map<UUID, String> shownGlyph = new HashMap<>();
    private File defsFile;
    private File dataFile;
    private File firstFile;
    /** 칭호 id → 서버에서 처음 받은 사람 */
    private final Map<String, First> firsts = new LinkedHashMap<>();

    Titles(PokiName plugin) {
        this.plugin = plugin;
    }

    void load() {
        defsFile = new File(plugin.getDataFolder(), "titles.yml");
        if (!defsFile.exists()) {
            plugin.saveResource("titles.yml", false);
        } else {
            YamlConfiguration cur = YamlConfiguration.loadConfiguration(defsFile);
            int v = cur.getInt("version", 1);
            if (v < 2) {
                // 아주 옛 목록(영어 id) → 백업하고 새 목록으로
                File old = new File(plugin.getDataFolder(), "titles.yml.old");
                if (old.exists()) old.delete();
                if (defsFile.renameTo(old)) plugin.getLogger().info("칭호 목록이 바뀌어 titles.yml 을 새로 만듦 (옛 파일: titles.yml.old)");
                plugin.saveResource("titles.yml", true);
            } else if (v < DEFS_VERSION) {
                // 새 기본 칭호만 덧붙임 (서버에서 고친 칭호는 그대로)
                try (java.io.Reader rd = new java.io.InputStreamReader(plugin.getResource("titles.yml"), java.nio.charset.StandardCharsets.UTF_8)) {
                    YamlConfiguration def = YamlConfiguration.loadConfiguration(rd);
                    ConfigurationSection ds = def.getConfigurationSection("titles");
                    int added = 0;
                    if (ds != null) for (String id : ds.getKeys(false)) {
                        if (cur.contains("titles." + id)) continue;
                        cur.createSection("titles." + id, ds.getConfigurationSection(id).getValues(false));
                        added++;
                    }
                    // v5: 종류(type) 칸 — 옛 칭호는 전부 이미지 칭호
                    int typed = 0;
                    ConfigurationSection cs = cur.getConfigurationSection("titles");
                    if (cs != null) for (String id : cs.getKeys(false)) {
                        if (cs.isString(id + ".type")) continue;
                        cs.set(id + ".type", cs.isString(id + ".glyph") ? "image" : "text");
                        typed++;
                    }
                    if (v < 5) {
                        File bak = new File(plugin.getDataFolder(), "titles.yml.v" + v + ".old");
                        if (!bak.exists()) java.nio.file.Files.copy(defsFile.toPath(), bak.toPath());
                    }
                    cur.set("version", DEFS_VERSION);
                    cur.save(defsFile);
                    plugin.getLogger().info("새 기본 칭호 " + added + "개를 titles.yml 에 추가" + (typed > 0 ? ", 칭호 " + typed + "개에 종류(type) 표시 (옛 파일: titles.yml.v" + v + ".old)" : ""));
                } catch (Exception e) {
                    plugin.getLogger().warning("titles.yml 새 칭호 추가 실패: " + e);
                }
            }
        }
        dataFile = new File(plugin.getDataFolder(), "player-titles.yml");
        loadDefs();
        loadPlayers();
        firstFile = new File(plugin.getDataFolder(), "first-titles.yml");
        loadFirsts();
        plugin.getLogger().info("칭호 " + defs.size() + "개 정의, 칭호 가진 플레이어 " + players.size() + "명");
    }

    // ---------- 조회 ----------

    boolean enabled() {
        return plugin.getConfig().getBoolean("title.enabled", true);
    }

    /** 이미지 칸에 장착한 칭호 (없으면 null) */
    Def equipped(UUID id) {
        if (!enabled()) return null;
        Owned o = players.get(id);
        if (o == null || o.equipped == null) return null;
        Def d = defs.get(o.equipped);
        return d == null || d.text() ? null : d;
    }

    /** 글씨 칸에 장착한 칭호 (없으면 null) */
    Def equippedText(UUID id) {
        if (!enabled()) return null;
        Owned o = players.get(id);
        if (o == null || o.equippedText == null) return null;
        Def d = defs.get(o.equippedText);
        return d == null || !d.text() ? null : d;
    }

    /** Tab·채팅용 칭호: 이미지 칭호 > 글씨 칭호 순으로 하나 */
    Def listTitle(UUID id) {
        Def d = equipped(id);
        return d != null ? d : equippedText(id);
    }

    /** 채팅·Tab 용 칭호 글씨 + 띄어쓰기. 없으면 "" */
    String chatPrefix(UUID id) {
        Def d = listTitle(id);
        if (d == null) return "";
        String fmt = plugin.getConfig().getString("title.chat-format", "{title}&r ");
        return fmt.replace("{title}", d.chat());
    }

    /** 머리 위 이름표 안에 넣을 글씨 칭호 한 줄 (색 적용됨). 없으면 "" */
    String overheadTextLine(UUID id) {
        if (!plugin.getConfig().getBoolean("title.text-overhead", true)) return "";
        Def d = equippedText(id);
        if (d == null) return "";
        return PokiName.color(plugin.getConfig().getString("title.text-format", "{title}").replace("{title}", d.chat()));
    }

    /** PlaceholderAPI 용: 이미지 칭호 이름 (없으면 "") */
    String nameOf(UUID id) {
        Def d = equipped(id);
        return d == null ? "" : d.name();
    }

    // ---------- 머리 위 배지 ----------

    /** PokiName.tickTags 에서 매 틱 호출. base = 한글닉 이름표 위치 (없으면 원래 이름표 높이) */
    void tick(Player p, Location base, boolean show, boolean seeThrough) {
        UUID id = p.getUniqueId();
        Def def = equipped(id);
        if (!show || def == null || !plugin.getConfig().getBoolean("title.overhead", true)) {
            remove(id);
            return;
        }
        double gap = plugin.getConfig().getDouble("title.gap", 0.3);
        Location at = base.clone().add(0, gap, 0);
        TextDisplay d = displays.get(id);
        if (d != null && (!d.isValid() || !d.getWorld().equals(at.getWorld())
                || d.getLocation().distanceSquared(at) > 64 || !def.glyph().equals(shownGlyph.get(id)))) {
            remove(id);
            d = null;
        }
        if (d == null) {
            d = spawn(p, at, def);
            if (d == null) return;
        } else {
            d.teleport(at);
        }
        d.setSeeThrough(seeThrough && !p.isSneaking());
    }

    private TextDisplay spawn(Player p, Location at, Def def) {
        try {
            World w = at.getWorld();
            TextDisplay d = w.spawn(at, TextDisplay.class);
            d.setPersistent(false);
            d.addScoreboardTag(TAG);
            d.setBillboard(Display.Billboard.CENTER);
            d.setTeleportDuration(1);
            d.setViewRange(0.5f);
            d.setShadowed(false);
            d.setDefaultBackground(false);
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            d.setLineWidth(1000);
            d.setText("§f" + def.glyph());
            float sc = (float) plugin.getConfig().getDouble("title.scale", 1.0);
            if (sc != 1f) d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                    new Vector3f(sc, sc, sc), new AxisAngle4f()));
            p.hideEntity(plugin, d);
            displays.put(p.getUniqueId(), d);
            shownGlyph.put(p.getUniqueId(), def.glyph());
            return d;
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("칭호 배지 생성 실패 (" + p.getName() + "): " + ex.getMessage());
            return null;
        }
    }

    void remove(UUID id) {
        shownGlyph.remove(id);
        TextDisplay d = displays.remove(id);
        if (d != null && d.isValid()) d.remove();
    }

    void removeAll() {
        for (UUID id : new ArrayList<>(displays.keySet())) remove(id);
    }

    /** 관리 중인 배지 엔티티 id (잔상 정리용) */
    Set<UUID> liveEntities() {
        Set<UUID> s = new HashSet<>();
        for (TextDisplay d : displays.values()) if (d.isValid()) s.add(d.getUniqueId());
        return s;
    }

    // ---------- 명령어 ----------

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "";
        boolean admin = s.hasPermission(ADMIN);
        switch (sub) {
            case "", "목록", "list" -> {
                if (!(s instanceof Player p)) { usage(s, label); return true; }
                showOwned(p, label);
            }
            case "장착", "equip" -> {
                if (!(s instanceof Player p)) { s.sendMessage(PokiName.color("&c플레이어만 쓸 수 있어요.")); return true; }
                if (a.length < 2) { showOwned(p, label); return true; }
                Def d = find(join(a, 1));
                Owned o = players.get(p.getUniqueId());
                if (d == null || o == null || !o.owned.contains(d.id())) {
                    s.sendMessage(PokiName.color("&c가지고 있지 않은 칭호예요. &7/" + label + " &7로 목록 보기"));
                    return true;
                }
                if (d.text()) o.equippedText = d.id(); else o.equipped = d.id();
                o.name = p.getName();
                save();
                plugin.refresh(p);
                s.sendMessage(PokiName.color("&a" + (d.text() ? "글씨" : "이미지") + " 칭호를 장착했어요: " + d.chat()));
            }
            case "해제", "unequip" -> {
                if (!(s instanceof Player p)) { s.sendMessage(PokiName.color("&c플레이어만 쓸 수 있어요.")); return true; }
                Owned o = players.get(p.getUniqueId());
                if (o == null || (o.equipped == null && o.equippedText == null)) { s.sendMessage(PokiName.color("&7장착한 칭호가 없어요.")); return true; }
                String which = a.length > 1 ? a[1] : "전체";
                boolean img = which.equals("이미지") || which.equalsIgnoreCase("image");
                boolean txt = which.equals("글씨") || which.equalsIgnoreCase("text");
                if (!img && !txt) { img = true; txt = true; }
                if (img) o.equipped = null;
                if (txt) o.equippedText = null;
                save();
                plugin.refresh(p);
                s.sendMessage(PokiName.color("&a" + (img && txt ? "칭호를 모두" : img ? "이미지 칭호를" : "글씨 칭호를") + " 뺐어요."));
            }
            case "만들기", "create", "수정", "edit" -> {
                if (!admin) { noPerm(s); return true; }
                boolean create = sub.equals("만들기") || sub.equals("create");
                if (a.length < 3) {
                    s.sendMessage(PokiName.color("&e/" + label + " " + (create ? "만들기" : "수정") + " <이름> <색> &7- 띄어쓰기는 _ · 색: #FF7F7F 또는 그라데이션 #FF7F7F-#FFD36B"));
                    return true;
                }
                String col = normColor(a[2]);
                if (col == null) { s.sendMessage(PokiName.color("&c색은 #FF7F7F 나 #FF7F7F-#FFD36B (그라데이션), &e 같은 색 코드로 적어 주세요.")); return true; }
                if (create) {
                    String id = a[1].trim().replace(' ', '_').toLowerCase(Locale.ROOT);
                    String name = a[1].replace('_', ' ').trim();
                    if (name.isEmpty() || name.length() > 16) { s.sendMessage(PokiName.color("&c칭호 이름은 1~16자로 적어 주세요.")); return true; }
                    if (defs.containsKey(id) || find(a[1]) != null) { s.sendMessage(PokiName.color("&c이미 있는 칭호예요: " + a[1] + " &7(색만 바꾸려면 /" + label + " 수정)")); return true; }
                    Def d = textDef(id, name, col, "글씨", true);
                    defs.put(id, d);
                    saveDefs();
                    s.sendMessage(PokiName.color("&a글씨 칭호를 만들었어요: " + d.chat() + " &7(명령어 이름: &f" + id + "&7) → &f/" + label + " 지급 <마크닉> " + id));
                } else {
                    Def old = find(a[1]);
                    if (old == null || !old.text()) { s.sendMessage(PokiName.color("&c그런 글씨 칭호가 없어요: " + a[1] + " &7(이미지 칭호는 titles.yml 에서)")); return true; }
                    Def d = textDef(old.id(), old.name(), col, old.tier(), old.announceFirst());
                    defs.put(old.id(), d);
                    saveDefs();
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        Owned o = players.get(p.getUniqueId());
                        if (o != null && old.id().equals(o.equippedText)) plugin.refresh(p);
                    }
                    s.sendMessage(PokiName.color("&a색을 바꿨어요: " + d.chat()));
                }
            }
            case "삭제", "delete" -> {
                if (!admin) { noPerm(s); return true; }
                if (a.length < 2) { s.sendMessage(PokiName.color("&e/" + label + " 삭제 <글씨 칭호> &7- 가진 사람들한테서도 회수")); return true; }
                Def d = find(join(a, 1));
                if (d == null || !d.text()) { s.sendMessage(PokiName.color("&c그런 글씨 칭호가 없어요: " + join(a, 1) + " &7(이미지 칭호는 titles.yml 에서)")); return true; }
                defs.remove(d.id());
                firsts.remove(d.id());
                int had = 0;
                for (Map.Entry<UUID, Owned> e : players.entrySet()) {
                    Owned o = e.getValue();
                    if (o.owned.remove(d.id())) had++;
                    if (d.id().equals(o.equippedText)) o.equippedText = null;
                    if (d.id().equals(o.equipped)) o.equipped = null;
                }
                saveDefs();
                save();
                saveFirsts();
                for (Player p : Bukkit.getOnlinePlayers()) plugin.refresh(p);
                s.sendMessage(PokiName.color("&a글씨 칭호 " + d.chat() + " &a를 지웠어요 &7(가지고 있던 " + had + "명한테서 회수)"));
            }
            case "지급", "give" -> {
                if (!admin) { noPerm(s); return true; }
                if (a.length < 3) { usage(s, label); return true; }
                OfflinePlayer t = target(s, a[1]);
                if (t == null) return true;
                List<Def> list = resolveMany(s, join(a, 2));
                if (list == null) return true;
                Owned o = players.computeIfAbsent(t.getUniqueId(), k -> new Owned());
                if (t.getName() != null) o.name = t.getName();
                int added = 0;
                for (Def d : list) if (o.owned.add(d.id())) added++;
                boolean autoEquip = list.size() == 1 && (list.get(0).text() ? o.equippedText == null : o.equipped == null);
                if (autoEquip) { if (list.get(0).text()) o.equippedText = list.get(0).id(); else o.equipped = list.get(0).id(); }
                save();
                // 서버 최초 — 칭호 하나씩 줄 때만 (전체 지급은 테스트용이라 최초로 안 침)
                if (list.size() == 1) checkFirst(t, list.get(0));
                if (t.getPlayer() != null) {
                    plugin.refresh(t.getPlayer());
                    for (Def d : list)
                        t.getPlayer().sendMessage(PokiName.color("&e새 칭호를 얻었어요! " + d.chat()
                                + (autoEquip ? " &7(자동 장착)" : " &7/칭호 &7로 장착")));
                }
                s.sendMessage(PokiName.color("&a" + a[1] + " 님에게 칭호 " + added + "개 지급"
                        + (list.size() == 1 ? " (" + list.get(0).chat() + "&a)" : "")));
            }
            case "회수", "take" -> {
                if (!admin) { noPerm(s); return true; }
                if (a.length < 3) { usage(s, label); return true; }
                UUID id = findPlayer(a[1]);
                Owned o = id == null ? null : players.get(id);
                if (o == null) { s.sendMessage(PokiName.color("&c" + a[1] + " 님은 칭호가 없어요.")); return true; }
                List<Def> list = resolveMany(s, join(a, 2));
                if (list == null) return true;
                int removed = 0;
                for (Def d : list) {
                    if (o.owned.remove(d.id())) removed++;
                    if (d.id().equals(o.equipped)) o.equipped = null;
                    if (d.id().equals(o.equippedText)) o.equippedText = null;
                }
                save();
                Player online = Bukkit.getPlayer(id);
                if (online != null) plugin.refresh(online);
                s.sendMessage(PokiName.color("&a" + a[1] + " 님의 칭호 " + removed + "개 회수"));
            }
            case "보기", "check" -> {
                if (!admin) { noPerm(s); return true; }
                if (a.length < 2) { usage(s, label); return true; }
                UUID id = findPlayer(a[1]);
                Owned o = id == null ? null : players.get(id);
                if (o == null || o.owned.isEmpty()) { s.sendMessage(PokiName.color("&7" + a[1] + " 님은 칭호가 없어요.")); return true; }
                s.sendMessage(PokiName.color("&e" + a[1] + " 님의 칭호 (" + o.owned.size() + "개)"));
                for (String tid : o.owned) {
                    Def d = defs.get(tid);
                    s.sendMessage(PokiName.color("&7- " + (d == null ? "&c" + tid + " (정의 없음)" : d.chat() + " &8" + tid)
                            + (tid.equals(o.equipped) || tid.equals(o.equippedText) ? " &a[장착 중]" : "")));
                }
            }
            case "전체", "all" -> {
                if (!admin) { noPerm(s); return true; }
                s.sendMessage(PokiName.color("&e칭호 전체 (" + defs.size() + "개)"));
                for (Def d : defs.values())
                    s.sendMessage(PokiName.color("&7- &8[" + (d.text() ? "글씨" : "이미지") + (d.tier().isEmpty() ? "" : "·" + d.tier()) + "] " + d.chat() + " &7명령어: &f" + d.id()));
            }
            case "최초", "firsts" -> {
                if (!admin) { noPerm(s); return true; }
                if (firsts.isEmpty()) { s.sendMessage(PokiName.color("&7아직 최초 기록이 없어요.")); return true; }
                s.sendMessage(PokiName.color("&e서버 최초 획득 기록 (" + firsts.size() + "개)"));
                for (Map.Entry<String, First> e : firsts.entrySet()) {
                    Def d = defs.get(e.getKey());
                    s.sendMessage(PokiName.color("&7- " + (d == null ? "&8" + e.getKey() : d.chat()) + " &f" + e.getValue().name() + " &8" + e.getValue().at()));
                }
            }
            case "최초초기화", "resetfirsts" -> {
                if (!admin) { noPerm(s); return true; }
                if (a.length < 2) {
                    s.sendMessage(PokiName.color("&e/" + label + " 최초초기화 전체 &7- 최초 기록 전부 지우기 (오픈 직전에 한 번)"));
                    s.sendMessage(PokiName.color("&e/" + label + " 최초초기화 <칭호> &7- 그 칭호만 지우기"));
                    s.sendMessage(PokiName.color("&7가진 칭호는 그대로, '처음 받은 사람' 기록만 지워요."));
                    return true;
                }
                String key = join(a, 1);
                if (key.equals("전체") || key.equalsIgnoreCase("all")) {
                    int n = firsts.size();
                    firsts.clear();
                    saveFirsts();
                    s.sendMessage(PokiName.color("&a최초 기록 " + n + "개를 지웠어요. 이제부터 처음 받는 사람이 최초로 공지돼요."));
                } else {
                    Def d = find(key);
                    if (d == null) { s.sendMessage(PokiName.color("&c그런 칭호가 없어요: " + key)); return true; }
                    if (firsts.remove(d.id()) == null) { s.sendMessage(PokiName.color("&7" + d.chat() + " &7은 최초 기록이 없어요.")); return true; }
                    saveFirsts();
                    s.sendMessage(PokiName.color("&a" + d.chat() + " &a최초 기록을 지웠어요."));
                }
            }
            case "리로드", "reload" -> {
                if (!admin) { noPerm(s); return true; }
                plugin.reloadConfig();
                loadDefs();
                loadPlayers();
                loadFirsts();
                removeAll();
                for (Player p : Bukkit.getOnlinePlayers()) plugin.refresh(p);
                s.sendMessage(PokiName.color("&a칭호를 다시 불러왔어요. (" + defs.size() + "개)"));
            }
            default -> usage(s, label);
        }
        return true;
    }

    private void showOwned(Player p, String label) {
        Owned o = players.get(p.getUniqueId());
        if (o == null || o.owned.isEmpty()) {
            p.sendMessage(PokiName.color("&7아직 가진 칭호가 없어요."));
            return;
        }
        p.sendMessage(PokiName.color("&e&l내 칭호 &7(" + o.owned.size() + "개) &8- 글씨를 누르면 장착 · 이미지·글씨 하나씩 같이 달 수 있어요"));
        for (boolean textKind : new boolean[]{false, true}) {
            List<Def> list = new ArrayList<>();
            for (String tid : o.owned) {
                Def d = defs.get(tid);
                if (d != null && d.text() == textKind) list.add(d);
            }
            if (list.isEmpty()) continue;
            String slot = textKind ? o.equippedText : o.equipped;
            p.sendMessage(PokiName.color(textKind ? "&b글씨 칭호" : "&d이미지 칭호"));
            for (Def d : list) {
                boolean on = d.id().equals(slot);
                TextComponent line = new TextComponent(TextComponent.fromLegacyText(PokiName.color(" &7- " + d.chat() + (on ? " &a[장착 중]" : " &7[장착]"))));
                if (!on) {
                    line.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/" + label + " 장착 " + d.id()));
                    line.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(PokiName.color("&a눌러서 장착"))));
                }
                p.spigot().sendMessage(line);
            }
            if (slot != null) {
                TextComponent off = new TextComponent(TextComponent.fromLegacyText(PokiName.color(" &c[" + (textKind ? "글씨" : "이미지") + " 칭호 빼기]")));
                off.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/" + label + " 해제 " + (textKind ? "글씨" : "이미지")));
                p.spigot().sendMessage(new BaseComponent[]{off});
            }
        }
    }

    private void usage(CommandSender s, String label) {
        s.sendMessage(PokiName.color("&e/" + label + " &7- 내 칭호 목록 (눌러서 장착)"));
        s.sendMessage(PokiName.color("&e/" + label + " 장착 <칭호> &7/ &e/" + label + " 해제 [이미지|글씨|전체]"));
        if (s.hasPermission(ADMIN)) {
            s.sendMessage(PokiName.color("&6/" + label + " 만들기 <이름> <색> &7- 글씨 칭호 바로 만들기 (#FF7F7F, 그라데이션 #FF7F7F-#FFD36B)"));
            s.sendMessage(PokiName.color("&6/" + label + " 수정 <글씨 칭호> <색> &7/ &6/" + label + " 삭제 <글씨 칭호> &7- 삭제하면 가진 사람들한테서도 회수"));
            s.sendMessage(PokiName.color("&6/" + label + " 지급 <마크닉> <칭호|전체> &7- 칭호 주기"));
            s.sendMessage(PokiName.color("&6/" + label + " 회수 <마크닉> <칭호|전체> &7- 칭호 뺏기"));
            s.sendMessage(PokiName.color("&6/" + label + " 보기 <마크닉> &7- 가진 칭호 보기"));
            s.sendMessage(PokiName.color("&6/" + label + " 전체 &7- 칭호 목록"));
            s.sendMessage(PokiName.color("&6/" + label + " 최초 &7- 서버 최초 획득 기록"));
            s.sendMessage(PokiName.color("&6/" + label + " 최초초기화 <칭호|전체> &7- 최초 기록 지우기"));
            s.sendMessage(PokiName.color("&6/" + label + " 리로드 &7- titles.yml 다시 불러오기"));
        }
    }

    private static void noPerm(CommandSender s) {
        s.sendMessage(PokiName.color("&c권한이 없어요."));
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        boolean admin = s.hasPermission(ADMIN);
        if (a.length == 1) {
            List<String> subs = new ArrayList<>(List.of("장착", "해제"));
            if (admin) subs.addAll(List.of("지급", "회수", "보기", "전체", "만들기", "수정", "삭제", "최초", "최초초기화", "리로드"));
            for (String o : subs) if (o.startsWith(a[0])) out.add(o);
            return out;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (a.length == 2 && (sub.equals("해제") || sub.equals("unequip"))) {
            for (String o : List.of("이미지", "글씨", "전체")) if (o.startsWith(a[1])) out.add(o);
        } else if (a.length == 2 && admin && List.of("수정", "edit", "삭제", "delete").contains(sub)) {
            for (Def d : defs.values()) if (d.text() && d.id().startsWith(a[1])) out.add(d.id());
        } else if (a.length == 3 && admin && List.of("만들기", "create", "수정", "edit").contains(sub)) {
            if (a[2].isEmpty()) { out.add("#FF7F7F"); out.add("#8FD3FF-#C9A7FF"); }
        } else if (a.length == 2 && (sub.equals("장착") || sub.equals("equip")) && s instanceof Player p) {
            Owned o = players.get(p.getUniqueId());
            if (o != null) for (String id : o.owned) if (id.startsWith(a[1])) out.add(id);
        } else if (a.length == 2 && admin && List.of("지급", "give", "회수", "take", "보기", "check").contains(sub)) {
            for (Player p : Bukkit.getOnlinePlayers())
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(a[1].toLowerCase(Locale.ROOT))) out.add(p.getName());
        } else if (a.length == 2 && admin && (sub.equals("최초초기화") || sub.equals("resetfirsts"))) {
            if ("전체".startsWith(a[1])) out.add("전체");
            for (String id : firsts.keySet()) if (id.startsWith(a[1])) out.add(id);
        } else if (a.length == 3 && admin && List.of("지급", "give", "회수", "take").contains(sub)) {
            if ("전체".startsWith(a[2])) out.add("전체");
            for (String id : defs.keySet()) if (id.startsWith(a[2])) out.add(id);
        }
        return out;
    }

    // ---------- 찾기 ----------

    private static String join(String[] a, int from) {
        return String.join(" ", java.util.Arrays.copyOfRange(a, from, a.length));
    }

    /** id 또는 칭호 이름(띄어쓰기는 _ 로 써도 됨)으로 찾기 */
    Def find(String key) {
        if (key == null) return null;
        Def d = defs.get(key.toLowerCase(Locale.ROOT));
        if (d != null) return d;
        d = defs.get(key.trim().replace(' ', '_').toLowerCase(Locale.ROOT));
        if (d != null) return d;
        String k = key.replace('_', ' ').trim();
        for (Def x : defs.values()) if (x.name().equals(k)) return x;
        return null;
    }

    private List<Def> resolveMany(CommandSender s, String key) {
        if (key.equals("전체") || key.equalsIgnoreCase("all")) return new ArrayList<>(defs.values());
        Def d = find(key);
        if (d == null) {
            s.sendMessage(PokiName.color("&c그런 칭호가 없어요: " + key + " &7(/칭호 전체 로 id 확인)"));
            return null;
        }
        return List.of(d);
    }

    @SuppressWarnings("deprecation")
    private OfflinePlayer target(CommandSender s, String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        UUID known = findPlayer(name);
        if (known != null) return Bukkit.getOfflinePlayer(known);
        OfflinePlayer op = Bukkit.getOfflinePlayer(name);
        if (!op.hasPlayedBefore()) {
            s.sendMessage(PokiName.color("&c" + name + " 님은 이 서버에 접속한 적이 없어요."));
            return null;
        }
        return op;
    }

    private UUID findPlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        for (Map.Entry<UUID, Owned> e : players.entrySet())
            if (name.equalsIgnoreCase(e.getValue().name)) return e.getKey();
        return null;
    }

    void onJoin(Player p) {
        Owned o = players.get(p.getUniqueId());
        if (o != null && !p.getName().equals(o.name)) {
            o.name = p.getName();
            save();
        }
    }

    // ---------- 저장 ----------

    private void loadDefs() {
        defs.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(defsFile);
        ConfigurationSection sec = y.getConfigurationSection("titles");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection t = sec.getConfigurationSection(id);
            if (t == null) continue;
            String name = t.getString("name", id);
            String type = t.getString("type", t.isString("glyph") ? "image" : "text");
            if (type.equalsIgnoreCase("text")) {
                String col = normColor(t.getString("color", "&f"));
                if (col == null) { plugin.getLogger().warning("titles.yml: " + id + " 의 color 값이 잘못됨 (" + t.getString("color") + ")"); col = "&f"; }
                String k = id.toLowerCase(Locale.ROOT);
                Def d = textDef(k, name, col, t.getString("tier", "글씨"), t.getBoolean("announce-first", true));
                // chat 을 직접 적어 뒀으면 그걸 씀
                if (t.isString("chat")) d = new Def(k, name, "", PokiName.color(t.getString("chat")), d.tier(), d.announceFirst(), true, col);
                defs.put(k, d);
                continue;
            }
            String code = t.getString("glyph", "");
            String glyph;
            try {
                glyph = new String(Character.toChars(Integer.parseInt(code.replace("\\u", "").replace("U+", ""), 16)));
            } catch (NumberFormatException ex) {
                plugin.getLogger().warning("titles.yml: " + id + " 의 glyph 값이 잘못됨 (" + code + ")");
                continue;
            }
            String chat = PokiName.color(t.getString("chat", "&f[" + name + "]"));
            defs.put(id.toLowerCase(Locale.ROOT), new Def(id.toLowerCase(Locale.ROOT), name, glyph, chat, t.getString("tier", ""),
                    t.getBoolean("announce-first", true), false, ""));
        }
    }

    // ---------- 글씨 칭호 ----------

    private static final java.util.regex.Pattern ONE_HEX = java.util.regex.Pattern.compile("#?([0-9a-fA-F]{6})");

    /** "#FF7F7F" · "FF7F7F" · "#FF7F7F-#FFD36B" · "&e" → 저장 형식 ("#FF7F7F", "#FF7F7F-#FFD36B", "&e"). 틀리면 null */
    static String normColor(String in) {
        if (in == null) return null;
        String s = in.trim();
        if (s.matches("(&[0-9a-fA-Fk-oK-OrR])+")) return s.toLowerCase(Locale.ROOT);
        String[] parts = s.split("-");
        if (parts.length < 1 || parts.length > 2) return null;
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            java.util.regex.Matcher m = ONE_HEX.matcher(part.trim());
            if (!m.matches()) return null;
            if (out.length() > 0) out.append('-');
            out.append('#').append(m.group(1).toUpperCase(Locale.ROOT));
        }
        return out.toString();
    }

    /** 글씨 칭호 정의 — chat = "[이름]" 에 색(그라데이션)을 입힌 것 */
    static Def textDef(String id, String name, String col, String tier, boolean announce) {
        String fmt = "[" + name + "]";
        return new Def(id, name, "", PokiName.color(paint(fmt, col)), tier == null ? "" : tier, announce, true, col);
    }

    /** 글자마다 색 칠하기 (그라데이션이면 글자마다 색이 조금씩 바뀜). 결과는 &#RRGGBB 형식 */
    static String paint(String text, String col) {
        if (col.startsWith("&")) return col + text;
        String[] parts = col.split("-");
        int a = Integer.parseInt(parts[0].substring(1), 16);
        if (parts.length == 1) return "&#" + parts[0].substring(1) + text;
        int b = Integer.parseInt(parts[1].substring(1), 16);
        int[] cps = text.codePoints().toArray();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cps.length; i++) {
            double t = cps.length == 1 ? 0 : (double) i / (cps.length - 1);
            int r = (int) Math.round(((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
            int g = (int) Math.round(((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
            int bl = (int) Math.round((a & 255) + ((b & 255) - (a & 255)) * t);
            sb.append(String.format("&#%02X%02X%02X", r, g, bl)).appendCodePoint(cps[i]);
        }
        return sb.toString();
    }

    /** 글씨 칭호만 titles.yml 에 반영 (이미지 칭호 칸은 손대지 않음) */
    private void saveDefs() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(defsFile);
        ConfigurationSection sec = y.getConfigurationSection("titles");
        if (sec == null) sec = y.createSection("titles");
        for (String id : new ArrayList<>(sec.getKeys(false))) {
            String type = sec.getString(id + ".type", sec.isString(id + ".glyph") ? "image" : "text");
            if (type.equalsIgnoreCase("text") && !defs.containsKey(id.toLowerCase(Locale.ROOT))) sec.set(id, null);
        }
        for (Def d : defs.values()) {
            if (!d.text()) continue;
            String base = d.id();
            sec.set(base + ".name", d.name());
            sec.set(base + ".type", "text");
            sec.set(base + ".color", d.color());
            sec.set(base + ".chat", null);
            sec.set(base + ".tier", d.tier());
            if (!d.announceFirst()) sec.set(base + ".announce-first", false);
        }
        write(defsFile, y.saveToString());
    }

    /** 파일 쓰기는 작업자 스레드에서 (임시 파일에 쓰고 바꿔치기) */
    private void write(File f, String text) {
        Runnable job = () -> {
            try {
                File dir = f.getParentFile();
                dir.mkdirs();
                File tmp = new File(dir, f.getName() + ".tmp");
                java.nio.file.Files.writeString(tmp.toPath(), text, java.nio.charset.StandardCharsets.UTF_8);
                java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                plugin.getLogger().warning(f.getName() + " 저장 실패: " + ex.getMessage());
            }
        };
        if (io.isShutdown()) job.run(); else io.execute(job);
    }

    /** 플러그인 끌 때: 남은 저장 다 끝내기 */
    void close() {
        io.shutdown();
        try { io.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    /** 예전(1.5.0) 영어 id → 지금 한글 id. 저장 파일에 남은 옛 id 를 불러올 때 바꿔줌 */
    private static final Map<String, String> OLD_IDS = Map.ofEntries(
            Map.entry("beginner", "초보"),
            Map.entry("poki_friend", "포키의_친구"),
            Map.entry("warrior_path", "전사의_길"),
            Map.entry("miner", "광산의_달인"),
            Map.entry("fisher", "낚시의_달인"),
            Map.entry("explorer", "탐험가"),
            Map.entry("villager", "마을의_방문자"),
            Map.entry("trainer", "포켓몬_트레이너"),
            Map.entry("master", "포켓몬_마스터"),
            Map.entry("collector", "전설의_수집가"),
            Map.entry("gym_champion", "체육관_챔피언"),
            Map.entry("legend_warrior", "전설의_트레이너"),
            Map.entry("hero", "서버의_영웅"),
            Map.entry("donor", "후원자"),
            Map.entry("staff", "포키_운영진"));

    private static String migrate(String id) {
        String k = id.toLowerCase(Locale.ROOT);
        return OLD_IDS.getOrDefault(k, k);
    }

    private boolean legacyFound;

    private void loadPlayers() {
        players.clear();
        if (!dataFile.exists()) return;
        legacyFound = false;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection sec = y.getConfigurationSection("players");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            try {
                ConfigurationSection ps = sec.getConfigurationSection(key);
                if (ps == null) continue;
                Owned o = new Owned();
                o.name = ps.getString("name");
                for (String t : ps.getStringList("owned")) o.owned.add(migrate(t));
                String img = ps.getString("equipped-image");
                String txt = ps.getString("equipped-text");
                String legacy = ps.getString("equipped");
                if (img == null && txt == null && legacy != null && !legacy.isEmpty()) {
                    // 2.0 전: 장착 칸이 하나 → 그 칭호 종류의 칸으로 옮김
                    legacyFound = true;
                    Def d = defs.get(migrate(legacy));
                    if (d != null && d.text()) txt = legacy; else img = legacy;
                }
                o.equipped = img == null || img.isEmpty() ? null : migrate(img);
                o.equippedText = txt == null || txt.isEmpty() ? null : migrate(txt);
                players.put(UUID.fromString(key), o);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (legacyFound) {
            // 처음 한 번: 옛 파일 백업하고 칸별 형식으로 저장
            File old = new File(plugin.getDataFolder(), "player-titles.yml.old");
            try { if (!old.exists()) java.nio.file.Files.copy(dataFile.toPath(), old.toPath()); }
            catch (IOException ex) { plugin.getLogger().warning("player-titles.yml 백업 실패: " + ex.getMessage()); }
            save();
            plugin.getLogger().info("장착 칭호를 이미지·글씨 칸으로 옮김 (옛 파일: player-titles.yml.old)");
        }
    }

    // ---------- 서버 최초 ----------

    /** 이 칭호를 서버에서 처음 받은 거면 기록하고 전체 공지 */
    private void checkFirst(OfflinePlayer t, Def d) {
        if (firsts.containsKey(d.id())) return;
        String name = t.getName() == null ? "?" : t.getName();
        firsts.put(d.id(), new First(t.getUniqueId(), name,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))));
        saveFirsts();
        if (!d.announceFirst() || !plugin.getConfig().getBoolean("title.first.enabled", true)) return;

        String shown = plugin.nickOf(t.getUniqueId(), name);
        String msg = plugin.getConfig().getString("title.first.message",
                "&6&l[서버 최초] &f{nick} &e님이 {title} &e칭호를 서버에서 처음으로 얻었어요!");
        Bukkit.broadcastMessage(PokiName.color(fill(msg, shown, name, d)));

        String big = plugin.getConfig().getString("title.first.screen-title", "&6&l서버 최초!");
        String small = plugin.getConfig().getString("title.first.screen-subtitle", "&f{nick} &7- {title}");
        String soundName = plugin.getConfig().getString("title.first.sound", "UI_TOAST_CHALLENGE_COMPLETE");
        Sound sound = null;
        try { if (soundName != null && !soundName.isBlank()) sound = Sound.valueOf(soundName.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException ignored) { }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (big != null && !big.isBlank())
                p.sendTitle(PokiName.color(fill(big, shown, name, d)), PokiName.color(fill(small == null ? "" : small, shown, name, d)), 10, 60, 20);
            if (sound != null) p.playSound(p.getLocation(), sound, 1f, 1f);
        }
    }

    private static String fill(String s, String nick, String name, Def d) {
        return s.replace("{nick}", nick).replace("{name}", name).replace("{title}", d.chat() + "&r").replace("{title_name}", d.name());
    }

    private void loadFirsts() {
        firsts.clear();
        if (firstFile == null || !firstFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(firstFile);
        ConfigurationSection sec = y.getConfigurationSection("firsts");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection f = sec.getConfigurationSection(id);
            if (f == null) continue;
            try {
                firsts.put(migrate(id), new First(UUID.fromString(f.getString("uuid", "")), f.getString("name", "?"), f.getString("at", "")));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void saveFirsts() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of("서버에서 칭호를 처음 받은 사람 기록 — /칭호 최초초기화 로 지울 수 있음"));
        for (Map.Entry<String, First> e : firsts.entrySet()) {
            String base = "firsts." + e.getKey();
            y.set(base + ".uuid", e.getValue().uuid().toString());
            y.set(base + ".name", e.getValue().name());
            y.set(base + ".at", e.getValue().at());
        }
        write(firstFile, y.saveToString());
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Owned> e : players.entrySet()) {
            Owned o = e.getValue();
            if (o.owned.isEmpty() && o.equipped == null && o.equippedText == null) continue;
            String base = "players." + e.getKey();
            y.set(base + ".name", o.name);
            y.set(base + ".owned", new ArrayList<>(o.owned));
            y.set(base + ".equipped-image", o.equipped);
            y.set(base + ".equipped-text", o.equippedText);
        }
        write(dataFile, y.saveToString());
    }
}

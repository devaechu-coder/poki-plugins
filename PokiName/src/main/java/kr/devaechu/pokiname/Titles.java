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
 * 칭호.
 * - titles.yml      : 칭호 정의 (id, 이름, 리소스팩 글자 코드, 채팅·Tab 용 글씨)
 * - player-titles.yml : 플레이어별 가진 칭호 / 장착한 칭호
 * 머리 위에는 리소스팩 배지 이미지(글자 하나)를 텍스트 디스플레이로 이름표 위에 띄운다.
 * 채팅·Tab 에는 배지가 한 줄에 안 들어가서 색 글씨를 쓴다.
 */
final class Titles implements CommandExecutor, TabCompleter {

    static final String TAG = "pokiname_title";
    static final String ADMIN = "pokiname.title.admin";
    /** 기본 titles.yml 의 version — 기본 칭호 목록을 바꿀 때 올림 (서버의 옛 titles.yml 을 새 것으로 교체) */
    static final int DEFS_VERSION = 2;

    record Def(String id, String name, String glyph, String chat, String tier, boolean announceFirst) { }

    /** 서버 최초 획득 기록 */
    record First(UUID uuid, String name, String at) { }

    private static final class Owned {
        String name;
        final Set<String> owned = new LinkedHashSet<>();
        String equipped;
    }

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
        } else if (YamlConfiguration.loadConfiguration(defsFile).getInt("version", 1) < DEFS_VERSION) {
            // 칭호 목록이 새로 바뀜 → 옛 파일은 백업하고 새 목록으로
            File old = new File(plugin.getDataFolder(), "titles.yml.old");
            if (old.exists()) old.delete();
            if (defsFile.renameTo(old)) plugin.getLogger().info("칭호 목록이 바뀌어 titles.yml 을 새로 만듦 (옛 파일: titles.yml.old)");
            plugin.saveResource("titles.yml", true);
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

    /** 장착한 칭호 (없으면 null) */
    Def equipped(UUID id) {
        if (!enabled()) return null;
        Owned o = players.get(id);
        if (o == null || o.equipped == null) return null;
        return defs.get(o.equipped);
    }

    /** 채팅·Tab 용 칭호 글씨 + 띄어쓰기. 없으면 "" */
    String chatPrefix(UUID id) {
        Def d = equipped(id);
        if (d == null) return "";
        String fmt = plugin.getConfig().getString("title.chat-format", "{title}&r ");
        return fmt.replace("{title}", d.chat());
    }

    /** PlaceholderAPI 용: 칭호 이름 (없으면 "") */
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
                o.equipped = d.id();
                o.name = p.getName();
                save();
                plugin.refresh(p);
                s.sendMessage(PokiName.color("&a칭호를 장착했어요: " + d.chat()));
            }
            case "해제", "unequip" -> {
                if (!(s instanceof Player p)) { s.sendMessage(PokiName.color("&c플레이어만 쓸 수 있어요.")); return true; }
                Owned o = players.get(p.getUniqueId());
                if (o == null || o.equipped == null) { s.sendMessage(PokiName.color("&7장착한 칭호가 없어요.")); return true; }
                o.equipped = null;
                save();
                plugin.refresh(p);
                s.sendMessage(PokiName.color("&a칭호를 뺐어요."));
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
                boolean autoEquip = o.equipped == null && list.size() == 1;
                if (autoEquip) o.equipped = list.get(0).id();
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
                            + (tid.equals(o.equipped) ? " &a[장착 중]" : "")));
                }
            }
            case "전체", "all" -> {
                if (!admin) { noPerm(s); return true; }
                s.sendMessage(PokiName.color("&e칭호 전체 (" + defs.size() + "개)"));
                for (Def d : defs.values())
                    s.sendMessage(PokiName.color("&7- &8[" + d.tier() + "] " + d.chat() + " &7명령어: &f" + d.id()));
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
        p.sendMessage(PokiName.color("&e&l내 칭호 &7(" + o.owned.size() + "개) &8- 글씨를 누르면 장착"));
        for (String tid : o.owned) {
            Def d = defs.get(tid);
            if (d == null) continue;
            boolean on = tid.equals(o.equipped);
            TextComponent line = new TextComponent(TextComponent.fromLegacyText(PokiName.color(" &7- " + d.chat() + (on ? " &a[장착 중]" : " &7[장착]"))));
            if (!on) {
                line.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/" + label + " 장착 " + d.id()));
                line.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(PokiName.color("&a눌러서 장착"))));
            }
            p.spigot().sendMessage(line);
        }
        if (o.equipped != null) {
            TextComponent off = new TextComponent(TextComponent.fromLegacyText(PokiName.color(" &c[칭호 빼기]")));
            off.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/" + label + " 해제"));
            p.spigot().sendMessage(new BaseComponent[]{off});
        }
    }

    private void usage(CommandSender s, String label) {
        s.sendMessage(PokiName.color("&e/" + label + " &7- 내 칭호 목록 (눌러서 장착)"));
        s.sendMessage(PokiName.color("&e/" + label + " 장착 <칭호> &7/ &e/" + label + " 해제"));
        if (s.hasPermission(ADMIN)) {
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
            if (admin) subs.addAll(List.of("지급", "회수", "보기", "전체", "최초", "최초초기화", "리로드"));
            for (String o : subs) if (o.startsWith(a[0])) out.add(o);
            return out;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (a.length == 2 && (sub.equals("장착") || sub.equals("equip")) && s instanceof Player p) {
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
                    t.getBoolean("announce-first", true)));
        }
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

    private void loadPlayers() {
        players.clear();
        if (!dataFile.exists()) return;
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
                String eq = ps.getString("equipped");
                o.equipped = eq == null || eq.isEmpty() ? null : migrate(eq);
                players.put(UUID.fromString(key), o);
            } catch (IllegalArgumentException ignored) {
            }
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
        try {
            plugin.getDataFolder().mkdirs();
            y.save(firstFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("first-titles.yml 저장 실패: " + ex.getMessage());
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Owned> e : players.entrySet()) {
            Owned o = e.getValue();
            if (o.owned.isEmpty() && o.equipped == null) continue;
            String base = "players." + e.getKey();
            y.set(base + ".name", o.name);
            y.set(base + ".owned", new ArrayList<>(o.owned));
            y.set(base + ".equipped", o.equipped);
        }
        try {
            plugin.getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("player-titles.yml 저장 실패: " + ex.getMessage());
        }
    }
}

package kr.devaechu.pokitown;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.event.DeleteTownEvent;
import com.palmergames.bukkit.towny.event.NewTownEvent;
import com.palmergames.bukkit.towny.event.RenameTownEvent;
import com.palmergames.bukkit.towny.event.TownAddResidentEvent;
import com.palmergames.bukkit.towny.event.TownAddResidentRankEvent;
import com.palmergames.bukkit.towny.event.TownInvitePlayerEvent;
import com.palmergames.bukkit.towny.event.TownRemoveResidentEvent;
import com.palmergames.bukkit.towny.event.TownRemoveResidentRankEvent;
import com.palmergames.bukkit.towny.event.town.TownMayorChangedEvent;
import com.palmergames.bukkit.towny.event.town.TownMergeEvent;
import com.palmergames.bukkit.towny.event.town.TownReclaimedEvent;
import com.palmergames.bukkit.towny.event.town.TownRuinedEvent;
import com.palmergames.bukkit.towny.event.town.TownTrustAddEvent;
import com.palmergames.bukkit.towny.event.town.TownTrustRemoveEvent;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 포키 마을. 마을은 따로 저장하지 않고 Towny 에서 바로 읽음.
 *  - 마을 표시 이름·색 (towns.yml, 마을 UUID 기준 → 마을 이름이 바뀌어도 유지)
 *  - Towny 이벤트를 받아서 바뀐 사람만 "마을 바뀜" 알림 (PokiTownChangeEvent / PokiTownApi.onChange)
 *  - 포키폰 마을 앱이 부르는 API (PokiTownApi)
 */
public final class PokiTown extends JavaPlugin implements Listener, TabCompleter {

    static PokiTown inst;

    /** 접속 중인 사람의 마지막 마을 상태 */
    record Snap(UUID town, String display, String color, String role, Set<UUID> trusted) {}

    private final Map<UUID, Snap> snaps = new HashMap<>();
    private boolean recheckQueued;

    // towns.yml
    private final Map<UUID, String> displayOverride = new ConcurrentHashMap<>();
    private final Map<UUID, String> colorOverride = new ConcurrentHashMap<>();
    private volatile boolean listPublic = true;
    private File townsFile;

    private String defaultColor = "&#8FD3FF";
    private String filteredName = "마을";
    private boolean notify = true;
    /** 욕설 알림은 마을·이름마다 한 번만 */
    private final Set<String> alerted = new HashSet<>();

    final List<Consumer<UUID>> changeListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    final List<BiConsumer<UUID, String>> notifyListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override
    public void onEnable() {
        inst = this;
        saveDefaultConfig();
        reloadSettings();
        townsFile = new File(getDataFolder(), "towns.yml");
        loadTowns();
        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("pokitown");
        if (cmd != null) { cmd.setExecutor(this); cmd.setTabCompleter(this); }
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try { new PokiTownExpansion().register(); getLogger().info("PlaceholderAPI 연결: %pokitown_name%, %pokitown_color%"); }
            catch (Throwable t) { getLogger().warning("PlaceholderAPI 연결 실패: " + t); }
        }
        // 켜질 때 접속 중인 사람 (리로드 대비)
        Bukkit.getScheduler().runTaskLater(this, this::recheckNow, 40L);
        getLogger().info("PokiTown 켜짐 — 마을 " + TownyAPI.getInstance().getTowns().size() + "개, 전체 마을 목록 유저 공개: " + (listPublic ? "켜짐" : "꺼짐"));
    }

    @Override
    public void onDisable() {
        changeListeners.clear();
        notifyListeners.clear();
        snaps.clear();
        inst = null;
    }

    void reloadSettings() {
        reloadConfig();
        defaultColor = getConfig().getString("default-color", "&#8FD3FF");
        filteredName = getConfig().getString("filtered-name", "마을");
        notify = getConfig().getBoolean("notify", true);
    }

    // ───────── towns.yml ─────────

    private void loadTowns() {
        if (!townsFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(townsFile);
        listPublic = y.getBoolean("list-public", true);
        var sec = y.getConfigurationSection("towns");
        if (sec == null) return;
        for (String k : sec.getKeys(false)) {
            UUID id;
            try { id = UUID.fromString(k); } catch (IllegalArgumentException e) { continue; }
            String d = sec.getString(k + ".display");
            String c = sec.getString(k + ".color");
            if (d != null && !d.isBlank()) displayOverride.put(id, d);
            if (c != null && !c.isBlank()) colorOverride.put(id, c);
        }
    }

    /** 바뀔 때만, 파일 쓰기는 비동기 */
    private void saveTowns() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of("포키 마을 표시 (명령어 /마을앱 으로 바꿈 — 직접 고치면 /마을앱 리로드)",
                "list-public: 포키폰 '전체 마을' 탭을 일반 유저에게도 보일지", "towns.<마을 UUID>.display / .color"));
        y.set("list-public", listPublic);
        Set<UUID> ids = new HashSet<>(displayOverride.keySet());
        ids.addAll(colorOverride.keySet());
        for (UUID id : ids) {
            Town t = TownyAPI.getInstance().getTown(id);
            if (t != null) y.set("towns." + id + ".towny-name", t.getName());
            y.set("towns." + id + ".display", displayOverride.get(id));
            y.set("towns." + id + ".color", colorOverride.get(id));
        }
        String text = y.saveToString();
        File f = townsFile;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                f.getParentFile().mkdirs();
                File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
                Files.writeString(tmp.toPath(), text, StandardCharsets.UTF_8);
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                getLogger().warning("towns.yml 저장 실패: " + e);
            }
        });
    }

    boolean listPublic() { return listPublic; }

    void setListPublic(boolean on, String by) {
        if (listPublic == on) return;
        listPublic = on;
        saveTowns();
        staff("&e[마을앱] &f" + by + " &7님이 포키폰 전체 마을 목록 유저 공개를 " + (on ? "&a켰어요" : "&c껐어요"));
        getLogger().info("전체 마을 목록 유저 공개 " + (on ? "켜짐" : "꺼짐") + " (" + by + ")");
    }

    // ───────── 표시 이름·색 ─────────

    String display(Town t) {
        String d = displayOverride.get(t.getUUID());
        if (d != null) return d;
        String n = t.getName();
        if (Filter.profane(n)) {
            if (alerted.add(t.getUUID() + "|" + n)) {
                staff("&c[마을앱] &f'" + n + "' &7마을 이름이 욕설 필터에 걸려서 &f'" + filteredName + "' &7로 보여요. 바꾸려면 &f/마을앱 표시 " + n + " <이름>");
                getLogger().warning("마을 이름 욕설 필터: " + n + " → " + filteredName);
            }
            return filteredName;
        }
        return n;
    }

    boolean customDisplay(Town t) { return displayOverride.containsKey(t.getUUID()); }

    String color(Town t) {
        String c = colorOverride.get(t.getUUID());
        return c != null ? c : defaultColor;
    }

    // ───────── 상태 계산 ─────────

    static String role(Town t, Resident r) {
        if (t == null || r == null) return "";
        if (t.isMayor(r)) return "mayor";
        if (r.hasTownRank("assistant")) return "assistant";
        return "resident";
    }

    /** 사람 UUID → 그 사람을 알바로 둔 마을들 */
    private static Map<UUID, Set<UUID>> trustIndex() {
        Map<UUID, Set<UUID>> out = new HashMap<>();
        for (Town t : TownyAPI.getInstance().getTowns())
            for (Resident r : t.getTrustedResidents())
                out.computeIfAbsent(r.getUUID(), k -> new HashSet<>()).add(t.getUUID());
        return out;
    }

    private Snap snap(Player p, Map<UUID, Set<UUID>> trust) {
        Resident r = TownyAPI.getInstance().getResident(p.getUniqueId());
        Town t = r == null ? null : r.getTownOrNull();
        Set<UUID> tr = trust.getOrDefault(p.getUniqueId(), Set.of());
        if (t == null) return new Snap(null, null, null, "", tr);
        return new Snap(t.getUUID(), display(t), color(t), role(t, r), tr);
    }

    /** 다음 틱에 한 번 (같은 틱에 이벤트가 여러 개 와도 한 번만). 비동기 스레드에서 불러도 됨 */
    void queueRecheck() {
        if (!Bukkit.isPrimaryThread()) { Bukkit.getScheduler().runTask(this, this::queueRecheck); return; }
        if (recheckQueued || inst == null) return;
        recheckQueued = true;
        Bukkit.getScheduler().runTaskLater(this, () -> { recheckQueued = false; recheckNow(); }, 1L);
    }

    private void recheckNow() {
        Map<UUID, Set<UUID>> trust = trustIndex();
        Map<UUID, Snap> now = new HashMap<>();
        List<Player> changed = new ArrayList<>();
        List<Runnable> notes = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Snap s = snap(p, trust);
            now.put(p.getUniqueId(), s);
            Snap old = snaps.get(p.getUniqueId());
            if (!Objects.equals(old, s)) {
                changed.add(p);
                if (old != null) notes.add(() -> describe(p, old, s));
            }
        }
        snaps.clear();
        snaps.putAll(now);
        for (Player p : changed) {
            Bukkit.getPluginManager().callEvent(new PokiTownChangeEvent(p));
            for (Consumer<UUID> l : changeListeners) {
                try { l.accept(p.getUniqueId()); } catch (Throwable t) { getLogger().warning("마을 바뀜 알림 처리 실패: " + t); }
            }
        }
        if (notify && !notifyListeners.isEmpty()) for (Runnable r : notes) r.run();
    }

    /** 바뀐 내용 → 포키폰 알림 문구 */
    private void describe(Player p, Snap old, Snap s) {
        String who = nick(p.getUniqueId(), p.getName());
        if (!Objects.equals(old.town(), s.town())) {
            if (old.town() != null) toMembers(old.town(), p.getUniqueId(), who + "님이 마을을 나갔어요");
            if (s.town() != null) {
                toMembers(s.town(), p.getUniqueId(), who + "님이 마을에 들어왔어요");
                send(p.getUniqueId(), plain(s.display()) + " 마을 주민이 됐어요");
            }
        } else if (s.town() != null && !old.role().equals(s.role())) {
            switch (s.role()) {
                case "mayor" -> send(p.getUniqueId(), "마을 이장이 됐어요");
                case "assistant" -> send(p.getUniqueId(), "부이장으로 지정됐어요");
                default -> { if (old.role().equals("assistant")) send(p.getUniqueId(), "부이장에서 해제됐어요"); }
            }
        }
        for (UUID t : s.trusted()) if (!old.trusted().contains(t)) send(p.getUniqueId(), plain(townDisplay(t)) + " 마을 알바가 됐어요");
        for (UUID t : old.trusted()) if (!s.trusted().contains(t)) send(p.getUniqueId(), plain(townDisplay(t)) + " 마을 알바에서 빠졌어요");
    }

    private String townDisplay(UUID id) {
        Town t = TownyAPI.getInstance().getTown(id);
        return t == null ? "" : display(t);
    }

    private void toMembers(UUID town, UUID except, String msg) {
        for (var e : snaps.entrySet())
            if (town.equals(e.getValue().town()) && !e.getKey().equals(except)) send(e.getKey(), msg);
    }

    void send(UUID to, String msg) {
        for (BiConsumer<UUID, String> l : notifyListeners) {
            try { l.accept(to, msg); } catch (Throwable t) { getLogger().warning("포키폰 알림 실패: " + t); }
        }
    }

    Snap snapOf(UUID player) { return snaps.get(player); }

    // ───────── 한글닉 (PokiName 이 있으면) ─────────

    String nick(UUID id, String fallback) {
        try {
            if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                String s = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(Bukkit.getOfflinePlayer(id), "%pokiname_nick%");
                if (s != null && !s.isBlank() && !s.contains("%")) return plain(s);
            }
        } catch (Throwable ignored) {
        }
        return fallback == null ? "" : fallback;
    }

    private static final Pattern HEX = Pattern.compile("&#([0-9a-fA-F]{6})");

    static String color(String s) {
        if (s == null) return "";
        Matcher m = HEX.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            StringBuilder rep = new StringBuilder("§x");
            for (char c : m.group(1).toCharArray()) rep.append('§').append(c);
            m.appendReplacement(sb, Matcher.quoteReplacement(rep.toString()));
        }
        m.appendTail(sb);
        return ChatColor.translateAlternateColorCodes('&', sb.toString());
    }

    static String plain(String s) {
        return s == null ? "" : ChatColor.stripColor(color(s));
    }

    private void staff(String msg) {
        String c = color(msg);
        for (Player p : Bukkit.getOnlinePlayers()) if (p.hasPermission("pokitown.admin")) p.sendMessage(c);
    }

    // ───────── Towny 이벤트 → 다시 확인 ─────────
    // 직책·알바 이벤트는 바뀌기 "전" 에 오고 취소될 수도 있음 → 다음 틱에 실제 상태를 다시 읽음

    @EventHandler(priority = EventPriority.MONITOR) public void on(NewTownEvent e) { queueRecheck(); }
    @EventHandler(priority = EventPriority.MONITOR) public void on(DeleteTownEvent e) { queueRecheck(); }
    @EventHandler(priority = EventPriority.MONITOR) public void on(RenameTownEvent e) { queueRecheck(); }
    @EventHandler(priority = EventPriority.MONITOR) public void on(TownAddResidentEvent e) { queueRecheck(); }
    @EventHandler(priority = EventPriority.MONITOR) public void on(TownRemoveResidentEvent e) { queueRecheck(); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void on(TownAddResidentRankEvent e) { queueRecheck(); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void on(TownRemoveResidentRankEvent e) { queueRecheck(); }
    @EventHandler(priority = EventPriority.MONITOR) public void on(TownMayorChangedEvent e) { queueRecheck(); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void on(TownTrustAddEvent e) { queueRecheck(); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void on(TownTrustRemoveEvent e) { queueRecheck(); }
    @EventHandler(priority = EventPriority.MONITOR) public void on(TownRuinedEvent e) { queueRecheck(); }
    @EventHandler(priority = EventPriority.MONITOR) public void on(TownReclaimedEvent e) { queueRecheck(); }
    @EventHandler(priority = EventPriority.MONITOR) public void on(TownMergeEvent e) { queueRecheck(); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void on(TownInvitePlayerEvent e) {
        try {
            if (!(e.getInvite().getReceiver() instanceof Resident r)) return;
            if (!(e.getInvite().getSender() instanceof Town t)) return;
            UUID to = r.getUUID();
            String name = plain(display(t));
            Runnable run = () -> { if (notify) send(to, name + " 마을에서 초대가 왔어요 · 포키폰 마을 앱에서 수락할 수 있어요"); };
            if (Bukkit.isPrimaryThread()) run.run(); else Bukkit.getScheduler().runTask(this, run);
        } catch (Throwable ignored) {
        }
    }

    /** 이벤트가 없는 변경(관리자 명령어 등)도 놓치지 않게: 마을 명령어를 치면 한 번 다시 확인 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void on(PlayerCommandPreprocessEvent e) { if (townCommand(e.getMessage())) laterRecheck(); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void on(ServerCommandEvent e) { if (townCommand("/" + e.getCommand())) laterRecheck(); }

    private static boolean townCommand(String msg) {
        String m = msg.toLowerCase(Locale.ROOT);
        int sp = m.indexOf(' ');
        String head = (sp < 0 ? m : m.substring(0, sp));
        if (head.startsWith("/towny:")) head = "/" + head.substring(7);
        return switch (head) {
            case "/t", "/town", "/ta", "/townyadmin", "/invite", "/invites", "/마을", "/마을알바", "/마을부이장", "/마을생성", "/confirm" -> true;
            default -> false;
        };
    }

    private void laterRecheck() {
        Bukkit.getScheduler().runTaskLater(this, this::queueRecheck, 10L);
    }

    @EventHandler
    public void on(PlayerJoinEvent e) {
        // Towny 가 주민 정보를 준비할 시간
        Bukkit.getScheduler().runTaskLater(this, this::queueRecheck, 20L);
    }

    @EventHandler
    public void on(PlayerQuitEvent e) { snaps.remove(e.getPlayer().getUniqueId()); }

    // ───────── /마을앱 ─────────

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 0) { help(s); return true; }
        String by = s.getName();
        switch (a[0]) {
            case "목록공개" -> {
                String v = a.length > 1 ? a[1] : "상태";
                switch (v) {
                    case "켜기", "on" -> setListPublic(true, by);
                    case "끄기", "off" -> setListPublic(false, by);
                    default -> { }
                }
                msg(s, "&f포키폰 전체 마을 목록 — 일반 유저: " + (listPublic ? "&a보임" : "&c안 보임 (내 마을만)") + " &7(운영진은 항상 보임)");
            }
            case "표시" -> {
                if (a.length < 3) { msg(s, "&c/마을앱 표시 <마을> <보일 이름>"); return true; }
                Town t = town(s, a[1]);
                if (t == null) return true;
                String d = String.join(" ", java.util.Arrays.copyOfRange(a, 2, a.length)).trim();
                if (plain(d).isEmpty() || plain(d).length() > 20) { msg(s, "&c보일 이름은 1~20자로 적어 주세요"); return true; }
                displayOverride.put(t.getUUID(), d);
                saveTowns(); queueRecheck();
                msg(s, "&a" + t.getName() + " &f마을은 이제 &r" + color(color(t) + d) + " &f로 보여요 &7(Towny 이름은 그대로)");
            }
            case "색" -> {
                if (a.length < 3) { msg(s, "&c/마을앱 색 <마을> <색>  &7예) &b, &#FF7F7F"); return true; }
                Town t = town(s, a[1]);
                if (t == null) return true;
                String col = a[2];
                if (col.matches("#[0-9a-fA-F]{6}")) col = "&" + col;
                if (!col.matches("(&[0-9a-fA-Fk-oK-OrR]|&#[0-9a-fA-F]{6})+")) { msg(s, "&c색은 &b 같은 색 코드나 #7EC8E3 같은 헥스로 적어 주세요"); return true; }
                colorOverride.put(t.getUUID(), col);
                saveTowns(); queueRecheck();
                msg(s, "&a" + t.getName() + " &f마을 색: &r" + color(col + display(t)));
            }
            case "초기화" -> {
                if (a.length < 2) { msg(s, "&c/마을앱 초기화 <마을>"); return true; }
                Town t = town(s, a[1]);
                if (t == null) return true;
                displayOverride.remove(t.getUUID()); colorOverride.remove(t.getUUID());
                alerted.removeIf(k -> k.startsWith(t.getUUID() + "|"));
                saveTowns(); queueRecheck();
                msg(s, "&a" + t.getName() + " &f마을 표시를 Towny 이름·기본색으로 되돌렸어요 → &r" + color(color(t) + display(t)));
            }
            case "정보" -> {
                if (a.length < 2) { msg(s, "&c/마을앱 정보 <마을>"); return true; }
                Town t = town(s, a[1]);
                if (t == null) return true;
                msg(s, "&fTowny 이름: &e" + t.getName() + " &7(" + t.getUUID() + ")");
                msg(s, "&f보이는 이름: &r" + color(color(t) + display(t)) + (displayOverride.containsKey(t.getUUID()) ? " &7(운영진 지정)" : Filter.profane(t.getName()) ? " &c(욕설 필터)" : ""));
                msg(s, "&f이장 " + (t.getMayor() == null ? "-" : t.getMayor().getName()) + " · 주민 " + t.getNumResidents() + " · 알바 " + t.getTrustedResidents().size() + " · 땅 " + t.getNumTownBlocks() + "청크");
            }
            case "리로드" -> {
                reloadSettings();
                displayOverride.clear(); colorOverride.clear();
                loadTowns(); queueRecheck();
                msg(s, "&a설정·towns.yml 다시 읽음");
            }
            default -> help(s);
        }
        return true;
    }

    private Town town(CommandSender s, String name) {
        Town t = TownyAPI.getInstance().getTown(name);
        if (t == null) msg(s, "&c그런 마을이 없어요: " + name + " &7(Towny 마을 이름으로 적기, 띄어쓰기는 _)");
        return t;
    }

    private static void msg(CommandSender s, String m) { s.sendMessage(color("&6[마을앱] &r" + m)); }

    private static void help(CommandSender s) {
        msg(s, "&f/마을앱 표시 <마을> <이름> &7- 머리 위·포키폰에 보일 마을 이름 (Towny 이름은 안 바뀜)");
        msg(s, "&f/마을앱 색 <마을> <색> &7- 마을 이름 색 (&b, #7EC8E3)");
        msg(s, "&f/마을앱 초기화 <마을> &7- Towny 이름·기본색으로");
        msg(s, "&f/마을앱 정보 <마을>");
        msg(s, "&f/마을앱 목록공개 켜기|끄기|상태 &7- 포키폰 '전체 마을' 탭을 일반 유저에게 보일지");
        msg(s, "&f/마을앱 리로드");
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            for (String o : List.of("표시", "색", "초기화", "정보", "목록공개", "리로드")) if (o.startsWith(a[0])) out.add(o);
        } else if (a.length == 2 && a[0].equals("목록공개")) {
            for (String o : List.of("켜기", "끄기", "상태")) if (o.startsWith(a[1])) out.add(o);
        } else if (a.length == 2 && List.of("표시", "색", "초기화", "정보").contains(a[0])) {
            String p = a[1].toLowerCase(Locale.ROOT);
            for (Town t : TownyAPI.getInstance().getTowns()) if (t.getName().toLowerCase(Locale.ROOT).startsWith(p)) out.add(t.getName());
        }
        return out;
    }

    static Plugin plugin() { return inst; }
}

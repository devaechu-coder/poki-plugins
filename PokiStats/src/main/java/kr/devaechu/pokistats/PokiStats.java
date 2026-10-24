package kr.devaechu.pokistats;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import kr.devaechu.pokistats.rank.Category;
import kr.devaechu.pokistats.rank.RankDb;
import kr.devaechu.pokistats.rank.Ranks;

/**
 * 포키 운영 통계.
 * 1단계: 동접자 추이 · 접속 기록(신규/플레이타임/재접속) · 후원 통계
 * 2단계: PokiEvents 모드 이벤트(포획·배틀·진화·부화·관장·배틀타워·레이드) 전달 · 운영진 숨김 · 돈 스냅샷
 *        + 인게임 명예의 전당(/명예의전당 GUI · 로비 홀로그램 · PAPI)
 * 기록은 queue.jsonl 에 쌓였다가 몇 분마다 통계 서버로 보냄 (서버 → 밖으로만).
 */
public final class PokiStats extends JavaPlugin implements Listener {

    private static final String PREFIX = "&8[&b통계&8] &f";

    private EventQueue queue;
    private Sender sender;
    private DonateReader donate;
    private BukkitTask task;
    private final AtomicBoolean busy = new AtomicBoolean();
    /** 접속 시작 시각 (ms) */
    private final Map<UUID, Long> sessionStart = new ConcurrentHashMap<>();
    /** jar 에 같이 빌드된 주소·키 (built-in.properties) */
    private final Properties builtIn = new Properties();

    private Ranks ranks;
    private Inbox inbox;
    private Wealth wealth;
    private HofGui gui;
    private Holograms holos;
    private final List<BukkitTask> rankTasks = new ArrayList<>();
    /** /명예의전당 초기화 확인 대기 (보낸 사람 → 만료 시각) */
    private final Map<String, Long> resetConfirm = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // 새 버전에서 생긴 설정(rank·wealth·hologram)을 기존 config.yml 에 채워 넣음 (적어둔 값은 그대로)
        getConfig().options().copyDefaults(true);
        saveConfig();
        try (InputStream in = getResource("built-in.properties")) {
            if (in != null) builtIn.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            getLogger().warning("built-in.properties 를 못 읽었어요: " + ex);
        }
        queue = new EventQueue(new File(getDataFolder(), "queue.jsonl"), getConfig().getInt("max-queue", 50000), getLogger());
        queue.load();
        sender = new Sender(getLogger(), getDescription().getVersion());
        donate = new DonateReader(new File(getDataFolder(), "donate-state.yml"), getLogger());

        long now = System.currentTimeMillis();
        queue.add(Json.event("boot", now).put("version", getDescription().getVersion())
                .put("maxPlayers", Bukkit.getMaxPlayers()).toString());
        // /reload 로 켜졌을 때 이미 접속 중인 사람
        for (Player p : Bukkit.getOnlinePlayers()) sessionStart.put(p.getUniqueId(), now);

        getServer().getPluginManager().registerEvents(this, this);
        schedule();
        startRanks();
        getLogger().info(endpoint().isBlank() || secret().isBlank() ? "통계 서버 주소·키가 없어서 기록만 쌓아둬요" : "통계 서버로 " + interval() + "분마다 보냄 (" + source() + ")");
    }

    @Override
    public void onDisable() {
        if (task != null) task.cancel();
        stopRanks(true);
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) queue.add(quit(p, now, "shutdown").toString());
        queue.add(Json.event("shutdown", now).toString());
        // 끌 때는 안 보내고 저장만 → 다음에 켜질 때 보냄 (서버 종료가 느려지지 않게)
        queue.save();
    }

    /** config.yml 에 적은 값이 우선, 비어 있으면 jar 에 빌드된 값 */
    private String setting(String key) {
        String v = getConfig().getString(key, "");
        if (v != null && !v.isBlank()) return v.strip();
        String b = builtIn.getProperty(key, "");
        return b.startsWith("${") ? "" : b.strip();
    }

    private String endpoint() {
        return setting("endpoint");
    }

    private String secret() {
        return setting("secret");
    }

    private String source() {
        String v = getConfig().getString("endpoint", "");
        return v != null && !v.isBlank() ? "config.yml" : "jar 기본값";
    }

    private int interval() {
        return Math.max(1, getConfig().getInt("interval-minutes", 5));
    }

    private void schedule() {
        if (task != null) task.cancel();
        long period = interval() * 60L * 20L;
        task = Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L * 30, period);
    }

    /** 메인 스레드: 동접 기록 → 비동기로 후원 장부 읽기·보내기·저장 */
    private void tick() {
        List<String> uuids = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) uuids.add(p.getUniqueId().toString());
        queue.add(Json.event("online", System.currentTimeMillis())
                .put("count", uuids.size()).put("max", Bukkit.getMaxPlayers())
                .putStrings("players", uuids).toString());
        flushAsync(null);
    }

    private void flushAsync(CommandSender reply) {
        if (!busy.compareAndSet(false, true)) {
            if (reply != null) tell(reply, "이미 보내는 중이에요");
            return;
        }
        final String endpoint = endpoint();
        final String secret = secret();
        final String serverId = getConfig().getString("server-id", "poki");
        final boolean debug = getConfig().getBoolean("debug");
        final boolean donateOn = getConfig().getBoolean("donate.enabled", true);
        final File donateDb = new File(getConfig().getString("donate.db", "plugins/PokiDonate/donate.db"));
        final boolean includeTest = getConfig().getBoolean("donate.include-test", false);
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                if (donateOn) donate.poll(donateDb, includeTest, queue);
                sender.flush(queue, endpoint, secret, serverId, debug);
                queue.save();
                if (reply != null) Bukkit.getScheduler().runTask(this, () -> tell(reply, sender.lastResult));
            } finally {
                busy.set(false);
            }
        });
    }

    // ── 접속 기록 ─────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        long now = System.currentTimeMillis();
        sessionStart.put(p.getUniqueId(), now);
        queue.add(Json.event("join", now)
                .put("uuid", p.getUniqueId().toString())
                .put("name", p.getName())
                .put("first", !p.hasPlayedBefore())
                .put("totalPlayMin", playMinutes(p))
                .toString());
        rankHide(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        queue.add(quit(e.getPlayer(), System.currentTimeMillis(), "quit").toString());
    }

    private Json quit(Player p, long now, String reason) {
        Long start = sessionStart.remove(p.getUniqueId());
        long sec = start == null ? 0 : Math.max(0, (now - start) / 1000);
        return Json.event("quit", now)
                .put("uuid", p.getUniqueId().toString())
                .put("name", p.getName())
                .put("sessionSec", sec)
                .put("totalPlayMin", playMinutes(p))
                .put("reason", reason);
    }

    /** 마크 통계의 누적 플레이 시간 (플러그인 넣기 전 시간 포함), 분 */
    private static long playMinutes(Player p) {
        try {
            return p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L / 60L;
        } catch (RuntimeException ex) {
            return -1;
        }
    }

    // ── 명령어 ─────────────────────────

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("hof")) return hof(s, args);
        String sub = args.length == 0 ? "상태" : args[0];
        switch (sub) {
            case "상태", "status" -> {
                boolean ready = !endpoint().isBlank() && !secret().isBlank();
                tell(s, "통계 서버: " + (ready ? "&a설정됨 &7(" + source() + ")" : "&c없음 (기록만 쌓는 중)"));
                tell(s, "못 보낸 기록: &e" + queue.size() + "개" + (queue.dropped() > 0 ? " &c(넘쳐서 버린 것 " + queue.dropped() + "개)" : ""));
                tell(s, "마지막 결과: &7" + sender.lastResult);
                if (sender.lastOkAt > 0)
                    tell(s, "마지막 성공: &7" + new SimpleDateFormat("MM-dd HH:mm:ss").format(new Date(sender.lastOkAt)));
                if (inbox != null) tell(s, "게임 이벤트(PokiEvents): &e" + inbox.read + "개 &7읽음 (이번 실행)");
                if (ranks != null) tell(s, "명예의 전당: " + (ranks.isOk() ? "&a켜짐" : "&c꺼짐 (ranks.db 오류)"));
                tell(s, "&7/통계 보내기 · /통계 리로드");
            }
            case "보내기", "send" -> {
                tell(s, "보내는 중… (" + queue.size() + "개)");
                flushAsync(s);
            }
            case "리로드", "reload" -> {
                reloadConfig();
                queue.setMax(getConfig().getInt("max-queue", 50000));
                schedule();
                stopRanks(false);
                startRanks();
                tell(s, "config.yml 다시 읽음 · " + interval() + "분마다");
            }
            default -> tell(s, "/통계 [상태|보내기|리로드]");
        }
        return true;
    }

    // ── 2단계: 게임 이벤트 · 명예의 전당 ─────────────────────────

    private void startRanks() {
        if (ranks == null) {
            ranks = new Ranks(getLogger());
            ranks.open(new File(getDataFolder(), "ranks.db"));
            inbox = new Inbox(new File(getDataFolder(), "inbox"), queue, ranks, getLogger());
            wealth = new Wealth(getLogger());
            gui = new HofGui(ranks);
            getServer().getPluginManager().registerEvents(gui, this);
            holos = new Holograms(new File(getDataFolder(), "holograms.yml"), ranks, getLogger());
            if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                try {
                    new StatsExpansion(this, ranks).register();
                } catch (Throwable t) {
                    getLogger().warning("PlaceholderAPI 연결 실패: " + t);
                }
            }
        }
        wealth.resetEconomy();
        holos.setSize(getConfig().getInt("hologram.size", 3));
        holos.load();
        // 모드 이벤트 받기 — 1초마다
        rankTasks.add(Bukkit.getScheduler().runTaskTimerAsynchronously(this, inbox::poll, 20L, 20L));
        // 순위 다시 계산 → 홀로그램 갱신
        long refresh = Math.max(10, getConfig().getInt("rank.refresh-seconds", 60)) * 20L;
        rankTasks.add(Bukkit.getScheduler().runTaskTimer(this, this::refreshRanks, 40L, refresh));
        // 돈 스냅샷 — 기본 10분마다
        long wp = Math.max(1, getConfig().getInt("wealth.interval-minutes", 10)) * 60L * 20L;
        rankTasks.add(Bukkit.getScheduler().runTaskTimer(this, () -> snapshotWealth(true), 20L * 60, wp));
        for (Player p : Bukkit.getOnlinePlayers()) rankHide(p);
    }

    private void stopRanks(boolean shutdown) {
        rankTasks.forEach(BukkitTask::cancel);
        rankTasks.clear();
        if (holos != null) holos.clear();
        if (!shutdown || ranks == null) return;
        try {
            inbox.poll();
        } catch (RuntimeException ex) {
            getLogger().warning("inbox 마지막 읽기 실패: " + ex);
        }
        snapshotWealth(true);
        ranks.close();
    }

    private void refreshRanks() {
        List<String> names = hiddenNames();
        for (Player p : Bukkit.getOnlinePlayers()) ranks.player(p.getUniqueId().toString(), p.getName(), hidden(p, names));
        ranks.refresh(names,
                () -> Bukkit.getScheduler().runTask(this, () -> holos.update()));
    }

    /** 운영진 숨김 — 권한(pokistats.rank.hidden) 또는 config rank.hiddenPlayers */
    private boolean hidden(Player p) {
        return hidden(p, hiddenNames());
    }

    private boolean hidden(Player p, List<String> names) {
        if (p.hasPermission("pokistats.rank.hidden")) return true;
        for (String v : names) {
            if (v.equalsIgnoreCase(p.getName()) || v.equalsIgnoreCase(p.getUniqueId().toString())) return true;
        }
        return false;
    }

    /**
     * 순위에서 뺄 사람 (마크닉·uuid) — PokiStats rank.hiddenPlayers
     * + PokiName 의 순위 제외 목록(rank-exclude, money-rank.exclude, cobble-rank.exclude, rank.use-pokiname-exclude)
     * 한글닉으로 적힌 건 마크닉으로 바꿔서 넣음. 메인 스레드에서 부름.
     */
    private List<String> hiddenNames() {
        java.util.Set<String> raw = new java.util.LinkedHashSet<>();
        addNames(raw, getConfig(), "rank.hiddenPlayers");
        org.bukkit.plugin.Plugin pn = getServer().getPluginManager().getPlugin("PokiName");
        if (getConfig().getBoolean("rank.use-pokiname-exclude", true) && pn != null && pn.isEnabled()) {
            org.bukkit.configuration.file.FileConfiguration c = pn.getConfig();
            addNames(raw, c, "rank-exclude");
            addNames(raw, c, "money-rank.exclude");
            addNames(raw, c, "cobble-rank.exclude");
        }
        if (raw.isEmpty()) return List.of();
        java.util.Set<String> lower = new java.util.HashSet<>();
        for (String r : raw) lower.add(r.toLowerCase(java.util.Locale.ROOT));
        List<String> out = new ArrayList<>(raw);
        // 한글닉으로 적은 사람 → 마크닉 찾아서 추가
        for (org.bukkit.OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            String name = op.getName();
            if (name == null || lower.contains(name.toLowerCase(java.util.Locale.ROOT))) continue;
            String nick = Display.nick(name);
            if (nick != null && lower.contains(nick.trim().toLowerCase(java.util.Locale.ROOT))) out.add(name);
        }
        return out;
    }

    private static void addNames(java.util.Set<String> out, org.bukkit.configuration.ConfigurationSection c, String path) {
        if (c == null || !c.contains(path)) return;
        List<String> list = c.isList(path) ? c.getStringList(path) : List.of(String.valueOf(c.getString(path, "")).split(","));
        for (String n : list) {
            String v = n == null ? "" : ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', n)).trim();
            if (!v.isEmpty()) out.add(v);
        }
    }

    private void rankHide(Player p) {
        boolean h = hidden(p);
        if (ranks != null) ranks.player(p.getUniqueId().toString(), p.getName(), h);
        queue.add(Json.event("rank_hide", System.currentTimeMillis())
                .put("uuid", p.getUniqueId().toString())
                .put("name", p.getName())
                .put("hidden", h)
                .toString());
    }

    /** 메인 스레드: 전원 잔액 → 로컬 순위, 일부(접속자 + 상위 100씩) → 통계 서버 wealth */
    private void snapshotWealth(boolean upload) {
        if (wealth == null || ranks == null) return;
        try {
            long now = System.currentTimeMillis();
            List<RankDb.Wealth> all = wealth.collect(getConfig().getString("wealth.cobble-data-folder", ""));
            if (all.isEmpty()) return;
            ranks.wealth(all, now);
            if (!upload) return;
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (RankDb.Wealth w : Wealth.pickForUpload(all)) {
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"uuid\":").append(Json.str(w.uuid()))
                        .append(",\"name\":").append(Json.str(w.name()))
                        .append(",\"money\":").append((long) Math.floor(w.money()))
                        .append(",\"coin\":").append((long) Math.floor(w.coin())).append('}');
            }
            sb.append(']');
            queue.add(Json.event("wealth", now).putRaw("players", sb.toString()).toString());
        } catch (RuntimeException ex) {
            getLogger().warning("돈 스냅샷 실패: " + ex);
        }
    }

    private boolean hof(CommandSender s, String[] args) {
        if (ranks == null || !ranks.isOk()) {
            tell(s, "&c명예의 전당을 쓸 수 없어요 (ranks.db 오류 — 콘솔 확인)");
            return true;
        }
        boolean admin = s.hasPermission("poki.stats.admin");
        if (args.length == 0) {
            if (s instanceof Player p) gui.openMain(p);
            else tell(s, "게임 안에서 쓰는 명령어예요");
            return true;
        }
        String sub = args[0];
        if (sub.equals("홀로그램") || sub.equalsIgnoreCase("holo")) {
            if (!admin) return noPerm(s);
            return holo(s, args);
        }
        if (sub.equals("초기화") || sub.equalsIgnoreCase("reset")) {
            if (!admin) return noPerm(s);
            String key = s.getName();
            Long until = resetConfirm.get(key);
            if (args.length > 1 && (args[1].equals("확인") || args[1].equalsIgnoreCase("confirm")) && until != null && until > System.currentTimeMillis()) {
                resetConfirm.remove(key);
                ranks.reset(msg -> Bukkit.getScheduler().runTask(this, () -> {
                    tell(s, "&a" + msg + " &7(통계 서버 쪽은 운영 통계에서 따로 지워야 숫자가 같아요)");
                    refreshRanks();
                }));
                return true;
            }
            resetConfirm.put(key, System.currentTimeMillis() + 30_000);
            tell(s, "&c인게임 순위 기록(포획·배틀 등)을 전부 지워요. 돈 순위는 그대로.");
            tell(s, "&c30초 안에 &f/명예의전당 초기화 확인 &c을 입력하면 지워져요");
            return true;
        }
        if (sub.equals("새로고침") || sub.equalsIgnoreCase("refresh")) {
            if (!admin) return noPerm(s);
            snapshotWealth(false);
            refreshRanks();
            tell(s, "순위를 다시 계산했어요");
            return true;
        }
        Category c = Category.find(sub);
        if (c == null) {
            tell(s, "순위 종류: &f" + java.util.Arrays.stream(Category.values()).map(x -> x.title.replace(" ", "")).collect(Collectors.joining(", ")));
            if (admin) tell(s, "&7관리: /명예의전당 홀로그램 · 초기화 · 새로고침");
            return true;
        }
        boolean weekly = args.length > 1 && (args[1].equals("주간") || args[1].equals("이번주") || args[1].equalsIgnoreCase("week"));
        if (s instanceof Player p) gui.openRank(p, c, weekly);
        else {
            List<RankDb.Row> rows = ranks.snapshot().get(c, weekly);
            tell(s, c.title + (weekly && c.hasWeekly() ? " (이번 주)" : ""));
            for (int i = 0; i < Math.min(10, rows.size()); i++)
                tell(s, (i + 1) + "위 " + rows.get(i).name() + " · " + c.format(rows.get(i).value()));
        }
        return true;
    }

    private boolean holo(CommandSender s, String[] args) {
        String op = args.length > 1 ? args[1] : "";
        switch (op) {
            case "설치", "add" -> {
                if (!(s instanceof Player p)) { tell(s, "게임 안에서 써 주세요"); return true; }
                Category c = args.length > 2 ? Category.find(args[2]) : null;
                if (c == null) { tell(s, "/명예의전당 홀로그램 설치 <순위종류> [주간]"); return true; }
                boolean weekly = args.length > 3 && (args[3].equals("주간") || args[3].equalsIgnoreCase("week"));
                Holograms.Holo h = holos.add(c, weekly, p.getLocation().add(0, getConfig().getDouble("hologram.height", 2.2), 0));
                tell(s, "&a홀로그램 #" + h.id() + " 설치: " + c.title + (h.weekly() ? " (이번 주)" : ""));
            }
            case "목록", "list" -> {
                List<Holograms.Holo> l = holos.list();
                if (l.isEmpty()) tell(s, "설치된 홀로그램이 없어요");
                for (Holograms.Holo h : l)
                    tell(s, "#" + h.id() + " &e" + h.cat().title + (h.weekly() ? " (이번 주)" : "") + " &7" + h.world()
                            + " " + (int) h.x() + ", " + (int) h.y() + ", " + (int) h.z());
            }
            case "삭제", "remove" -> {
                int id = args.length > 2 ? parse(args[2]) : -1;
                tell(s, holos.remove(id) ? "&a#" + id + " 삭제" : "&c그 번호의 홀로그램이 없어요 (/명예의전당 홀로그램 목록)");
            }
            case "이동", "move" -> {
                if (!(s instanceof Player p)) { tell(s, "게임 안에서 써 주세요"); return true; }
                int id = args.length > 2 ? parse(args[2]) : -1;
                tell(s, holos.move(id, p.getLocation().add(0, getConfig().getDouble("hologram.height", 2.2), 0)) ? "&a#" + id + " 을 여기로 옮겼어요" : "&c그 번호의 홀로그램이 없어요");
            }
            default -> {
                tell(s, "/명예의전당 홀로그램 설치 <순위종류> [주간] &7— 발 위치에서 " + getConfig().getDouble("hologram.height", 2.2) + "칸 위가 맨 아랫줄, TOP " + getConfig().getInt("hologram.size", 3));
                tell(s, "/명예의전당 홀로그램 목록 · 삭제 <번호> · 이동 <번호>");
            }
        }
        return true;
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.replace("#", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean noPerm(CommandSender s) {
        tell(s, "&c운영자만 쓸 수 있어요");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("hof")) return List.of();
        List<String> cats = java.util.Arrays.stream(Category.values()).map(c -> c.title.replace(" ", "")).toList();
        boolean admin = s.hasPermission("poki.stats.admin");
        if (args.length == 1) {
            List<String> l = new ArrayList<>(cats);
            if (admin) l.addAll(List.of("홀로그램", "초기화", "새로고침"));
            return l.stream().filter(x -> x.startsWith(args[0])).toList();
        }
        if (args.length == 2 && Category.find(args[0]) != null) return List.of("주간", "전체");
        if (admin && args[0].equals("홀로그램")) {
            if (args.length == 2) return List.of("설치", "목록", "삭제", "이동");
            if (args.length == 3 && args[1].equals("설치")) return cats.stream().filter(x -> x.startsWith(args[2])).toList();
            if (args.length == 4 && args[1].equals("설치")) return List.of("주간");
        }
        return List.of();
    }

    private static void tell(CommandSender s, String msg) {
        s.sendMessage(ChatColor.translateAlternateColorCodes('&', PREFIX + msg));
    }
}

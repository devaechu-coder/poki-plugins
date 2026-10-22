package kr.devaechu.pokidonate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * 포키 치지직 후원 연동.
 * 1단계: 채널 연동 + 후원 수신 + 장부 기록 (보상 지급은 다음 단계).
 */
public final class PokiDonate extends JavaPlugin implements SessionManager.Sink, LinkFlow.Events, Listener {

    static final String PREFIX = "&6[후원] &f";

    private final Links links = new Links();
    /** 지금 마크에 접속 중인 사람 (후원 이벤트는 다른 스레드에서 오니까 따로 들고 있음) */
    private final Set<UUID> online = ConcurrentHashMap.newKeySet();
    private Db db;
    private ChzzkApi api;
    private TokenKeeper tokens;
    private SessionManager sessions;
    private LinkFlow linkFlow;
    private ExecutorService net;
    private ScheduledExecutorService timer;
    private boolean configured;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        db = new Db();
        try {
            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            db.open(new File(getDataFolder(), "donate.db"));
        } catch (ClassNotFoundException e) {
            getLogger().severe("SQLite 드라이버가 서버에 없어요 (org.sqlite.JDBC). 플러그인을 끕니다.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "장부(donate.db)를 열지 못했어요. 플러그인을 끕니다.", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        links.load(db.loadLinks().join());

        for (Player p : Bukkit.getOnlinePlayers()) online.add(p.getUniqueId());
        Bukkit.getPluginManager().registerEvents(this, this);

        DonateCommand cmd = new DonateCommand(this);
        var command = getCommand("donate");
        if (command != null) {
            command.setExecutor(cmd);
            command.setTabCompleter(cmd);
        }
        startServices();
    }

    @Override
    public void onDisable() {
        stopServices();
        if (db != null) db.close();
    }

    /** /후원 리로드 */
    void reload() {
        reloadConfig();
        stopServices();
        startServices();
    }

    private void startServices() {
        String clientId = getConfig().getString("chzzk.client-id", "").trim();
        String clientSecret = getConfig().getString("chzzk.client-secret", "").trim();
        String bridgeUrl = getConfig().getString("bridge.url", "").trim();
        String bridgeSecret = getConfig().getString("bridge.secret", "").trim();
        configured = !clientId.isEmpty() && !clientSecret.isEmpty() && !bridgeUrl.isEmpty() && !bridgeSecret.isEmpty();
        if (!configured) {
            getLogger().warning("config.yml 의 chzzk.client-secret / bridge.secret 이 비어 있어요. 채워넣고 /후원 리로드 하면 시작해요.");
            return;
        }

        net = Executors.newCachedThreadPool(daemon("PokiDonate-net"));
        timer = Executors.newSingleThreadScheduledExecutor(daemon("PokiDonate-timer"));
        api = new ChzzkApi(getConfig().getString("chzzk.api-base", ChzzkApi.DEFAULT_BASE), clientId, clientSecret);
        tokens = new TokenKeeper(api, links, db, getLogger(), this::needsRelink);
        sessions = new SessionManager(api, links, tokens, db, getLogger(), this, net, timer, getConfig().getBoolean("debug"));
        linkFlow = new LinkFlow(api, links, db, getLogger(), this, bridgeUrl, bridgeSecret,
                Math.max(1, getConfig().getInt("link-expire-minutes", 10)));

        timer.scheduleWithFixedDelay(() -> net.execute(tokens::sweep), 1, 30, TimeUnit.MINUTES);
        LinkFlow flow = linkFlow;
        ExecutorService pool = net;
        java.util.concurrent.atomic.AtomicBoolean polling = new java.util.concurrent.atomic.AtomicBoolean();
        timer.scheduleWithFixedDelay(() -> {
            if (!flow.hasPending() || !polling.compareAndSet(false, true)) return;
            pool.execute(() -> {
                try { flow.poll(); } finally { polling.set(false); }
            });
        }, 3, 3, TimeUnit.SECONDS);
        sessions.start();
    }

    private void stopServices() {
        if (sessions != null) sessions.stop();
        if (timer != null) timer.shutdownNow();
        if (net != null) net.shutdownNow();
        sessions = null;
        linkFlow = null;
        timer = null;
        net = null;
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { online.add(e.getPlayer().getUniqueId()); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { online.remove(e.getPlayer().getUniqueId()); }

    // ── 후원 수신 (세션 스레드) ──────────────────────────

    @Override
    public void onDonation(JsonObject d) {
        String channelId = ChzzkApi.str(d, "channelId");
        Link l = links.byChannel(channelId);
        record(donation(channelId, l, ChzzkApi.str(d, "donatorChannelId"), ChzzkApi.str(d, "donatorNickname"),
                amount(d.get("payAmount")), ChzzkApi.str(d, "donationType"), ChzzkApi.str(d, "donationText"), false));
    }

    /**
     * 보상 대상인지 정해서 장부 한 줄을 만든다.
     * 방송 중이라도 스트리머가 마크에 접속해 있지 않으면 보상 대상이 아니다 (기록은 '대상 아님'으로 남김).
     */
    Donation donation(String channelId, Link l, String donatorId, String donatorNick, long amount, String type, String text, boolean test) {
        Donation.Status st = Donation.Status.PENDING;
        String note = null;
        if (l == null) { st = Donation.Status.SKIPPED; note = "미연동 채널"; }
        else if (!online.contains(l.uuid())) { st = Donation.Status.SKIPPED; note = "마크 미접속"; }
        return new Donation(0, System.currentTimeMillis(), channelId, l == null ? null : l.uuid(), l == null ? null : l.playerName(),
                donatorId, donatorNick, amount, type, text, st, test, note);
    }

    /** 장부에 쓰고 알림. /후원 테스트 도 여기로 온다 */
    void record(Donation don) {
        db.insertDonation(don).whenComplete((id, err) -> {
            if (err != null) {
                getLogger().log(Level.SEVERE, "후원 기록 실패! " + don, err);
                return;
            }
            getLogger().info("후원 #" + id + (don.test() ? " (테스트)" : "") + " " + (don.streamerName() == null ? don.channelId() : don.streamerName())
                    + " ← " + don.donatorNickname() + " " + won(don.amount()) + " [" + don.type() + "] " + don.message());
            if (don.status() == Donation.Status.PENDING && getConfig().getBoolean("notify-streamer", true)) {
                tell(don.streamerUuid(), "&e" + nick(don.donatorNickname()) + "&f님 &a" + won(don.amount())
                        + (don.test() ? " &7(테스트)" : "") + " &8· 장부 #" + id);
            }
        });
    }

    static long amount(JsonElement e) {
        if (e == null || e.isJsonNull()) return 0;
        try {
            if (e.getAsJsonPrimitive().isNumber()) return e.getAsLong();
            String digits = e.getAsString().replaceAll("[^0-9]", ""); // 문서엔 문자열, 실제론 숫자로 옴
            return digits.isEmpty() ? 0 : Long.parseLong(digits);
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    @Override
    public void onRevoked(String channelId) {
        Link l = links.byChannel(channelId);
        if (l != null) tokens.markRelink(l, "스트리머가 치지직에서 권한을 철회함");
    }

    private void needsRelink(Link l) {
        if (sessions != null) sessions.remove(l.channelId(), null);
        tell(l.uuid(), "&c치지직 연동이 풀렸어요. &e/후원 연동 &c으로 다시 연결해 주세요.");
        tellAdmins("&e" + l.playerName() + " &7(" + l.channelName() + ") &f재연동이 필요해요.");
    }

    // ── LinkFlow.Events ─────────────────────────────────

    @Override
    public void linked(Link link, Link old) {
        if (sessions != null) {
            if (old != null) sessions.remove(old.channelId(), null);
            sessions.assign(link.channelId());
        }
        tell(link.uuid(), "&a치지직 채널 &f" + plain(link.channelName()) + " &a연동 완료! &7이제 방송 후원이 장부에 기록돼요.");
    }

    // ── 명령어에서 쓰는 것들 ────────────────────────────

    boolean configured() { return configured; }
    Links links() { return links; }
    Db db() { return db; }
    SessionManager sessions() { return sessions; }
    LinkFlow linkFlow() { return linkFlow; }

    /** 연동 해제 (본인 또는 관리자). 장부는 그대로 남는다 */
    void unlink(Link l) {
        links.remove(l.uuid());
        db.deleteLink(l.uuid());
        db.log("unlinked", l.channelName() + " ↔ " + l.playerName());
        if (sessions != null) sessions.remove(l.channelId(), l.accessToken());
        if (net != null && api != null) {
            ChzzkApi a = api;
            net.execute(() -> {
                try { a.revoke(l.accessToken()); } catch (Exception ignored) { }
            });
        }
    }

    // ── 메시지 ──────────────────────────────────────────

    @Override
    public void tell(UUID uuid, String msg) {
        Bukkit.getScheduler().runTask(this, () -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) p.sendMessage(color(PREFIX + msg));
        });
    }

    void tellAdmins(String msg) {
        Bukkit.getScheduler().runTask(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("poki.donate.admin")) p.sendMessage(color(PREFIX + msg));
            }
        });
    }

    static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    static String won(long amount) {
        return String.format("%,d원", amount);
    }

    /** 닉네임에 섞인 & 색코드가 먹지 않게 (& 뒤에 폭 0 공백) */
    static String nick(String s) {
        return s == null || s.isEmpty() ? "익명" : plain(s);
    }

    static String plain(String s) {
        return s == null ? "" : s.replace("&", "&​").replace("§", "");
    }
}

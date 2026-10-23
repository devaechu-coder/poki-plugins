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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 포키 운영 통계 (1단계): 동접자 추이 · 접속 기록(신규/플레이타임/재접속) · 후원 통계.
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

    @Override
    public void onEnable() {
        saveDefaultConfig();
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
        String ep = getConfig().getString("endpoint", "");
        getLogger().info(ep.isBlank() ? "endpoint 가 비어 있어서 기록만 쌓아둬요 (config.yml)" : "통계 서버로 " + interval() + "분마다 보냄");
    }

    @Override
    public void onDisable() {
        if (task != null) task.cancel();
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) queue.add(quit(p, now, "shutdown").toString());
        queue.add(Json.event("shutdown", now).toString());
        // 끌 때는 안 보내고 저장만 → 다음에 켜질 때 보냄 (서버 종료가 느려지지 않게)
        queue.save();
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
        final String endpoint = getConfig().getString("endpoint", "");
        final String secret = getConfig().getString("secret", "");
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
        String sub = args.length == 0 ? "상태" : args[0];
        switch (sub) {
            case "상태", "status" -> {
                String ep = getConfig().getString("endpoint", "");
                tell(s, "통계 서버: " + (ep.isBlank() ? "&c없음 (기록만 쌓는 중)" : "&a설정됨"));
                tell(s, "못 보낸 기록: &e" + queue.size() + "개" + (queue.dropped() > 0 ? " &c(넘쳐서 버린 것 " + queue.dropped() + "개)" : ""));
                tell(s, "마지막 결과: &7" + sender.lastResult);
                if (sender.lastOkAt > 0)
                    tell(s, "마지막 성공: &7" + new SimpleDateFormat("MM-dd HH:mm:ss").format(new Date(sender.lastOkAt)));
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
                tell(s, "config.yml 다시 읽음 · " + interval() + "분마다");
            }
            default -> tell(s, "/통계 [상태|보내기|리로드]");
        }
        return true;
    }

    private static void tell(CommandSender s, String msg) {
        s.sendMessage(ChatColor.translateAlternateColorCodes('&', PREFIX + msg));
    }
}

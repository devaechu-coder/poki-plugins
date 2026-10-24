package kr.devaechu.pokistats.rank;

import com.google.gson.JsonObject;

import java.io.File;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 순위 계산 담당. DB 작업은 전부 한 스레드에서, 화면(GUI·홀로그램·PAPI)은 마지막 계산 결과(Snapshot)만 읽음.
 */
public final class Ranks {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 마지막 계산 결과. all = 전체, week = 이번 주 */
    public record Snapshot(Map<Category, List<RankDb.Row>> all, Map<Category, List<RankDb.Row>> week, long at) {
        public List<RankDb.Row> get(Category c, boolean weekly) {
            Map<Category, List<RankDb.Row>> m = weekly && c.hasWeekly() ? week : all;
            List<RankDb.Row> l = m == null ? null : m.get(c);
            return l == null ? List.of() : l;
        }
    }

    private final Logger log;
    private final RankDb db = new RankDb();
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PokiStats-ranks");
        t.setDaemon(true);
        return t;
    });
    private volatile Snapshot snapshot = new Snapshot(Map.of(), Map.of(), 0);
    private volatile boolean ok;

    public Ranks(Logger log) {
        this.log = log;
    }

    public void open(File file) {
        exec.submit(() -> {
            try {
                db.open(file);
                ok = true;
            } catch (Exception ex) {
                log.log(Level.SEVERE, "순위 DB(ranks.db)를 못 열어서 인게임 순위를 끕니다", ex);
            }
        });
    }

    public boolean isOk() {
        return ok;
    }

    public void close() {
        exec.shutdown();
        try {
            exec.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        db.close();
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    /** 이번 주 시작 (한국시간 월요일 0시) */
    public static long weekStart() {
        return ZonedDateTime.now(KST).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .truncatedTo(ChronoUnit.DAYS).toInstant().toEpochMilli();
    }

    public void apply(Collection<JsonObject> events) {
        if (events.isEmpty()) return;
        run("이벤트 반영", () -> db.apply(events));
    }

    public void player(String uuid, String name, boolean hidden) {
        run("플레이어 저장", () -> { db.setPlayer(uuid, name, hidden); return null; });
    }

    public void wealth(Collection<RankDb.Wealth> list, long at) {
        run("돈 저장", () -> { db.setWealth(list, at); return null; });
    }

    /**
     * 다시 계산. hiddenNames: config 의 rank.hiddenPlayers (uuid 또는 닉네임)
     */
    public void refresh(Collection<String> hiddenConfig, Runnable after) {
        run("순위 계산", () -> {
            Map<String, String> names = db.names();
            Set<String> hidden = new HashSet<>(db.hiddenUuids());
            for (String h : hiddenConfig) {
                if (h == null || h.isBlank()) continue;
                String s = h.trim();
                if (s.length() == 36 && s.charAt(8) == '-') hidden.add(s.toLowerCase(Locale.ROOT));
                else names.forEach((u, n) -> { if (n != null && n.equalsIgnoreCase(s)) hidden.add(u); });
            }
            snapshot = new Snapshot(db.rankAll(0, names, hidden), db.rankAll(weekStart(), names, hidden), System.currentTimeMillis());
            if (after != null) after.run();
            return null;
        });
    }

    public void reset(Consumer<String> done) {
        run("초기화", () -> {
            int n = db.reset();
            done.accept("순위 기록 " + n + "개를 지웠어요");
            return null;
        });
    }

    private interface Job { Object call() throws Exception; }

    private void run(String what, Job job) {
        if (exec.isShutdown()) return;
        exec.submit(() -> {
            if (!ok) return;
            try {
                job.call();
            } catch (Exception ex) {
                log.warning("순위 " + what + " 실패: " + ex);
            }
        });
    }
}

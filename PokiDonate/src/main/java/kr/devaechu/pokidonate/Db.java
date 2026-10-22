package kr.devaechu.pokidonate;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 장부 (plugins/PokiDonate/donate.db, SQLite).
 * SQLite 드라이버는 서버에 들어있는 것(CoreProtect 와 같은 것)을 쓴다.
 * 모든 쿼리는 전용 스레드 하나에서 순서대로 돈다.
 */
class Db {

    interface SqlWork<T> { T run(Connection c) throws SQLException; }

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PokiDonate-db");
        t.setDaemon(true);
        return t;
    });
    private Connection conn;

    void open(File file) throws Exception {
        Class.forName("org.sqlite.JDBC");
        exec.submit(() -> {
            conn = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
            try (Statement s = conn.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA busy_timeout=5000");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS chzzk_link (
                          uuid TEXT PRIMARY KEY,
                          player_name TEXT NOT NULL,
                          channel_id TEXT NOT NULL UNIQUE,
                          channel_name TEXT NOT NULL,
                          access_token TEXT NOT NULL,
                          refresh_token TEXT NOT NULL,
                          expires_at INTEGER NOT NULL,
                          status TEXT NOT NULL,
                          linked_at INTEGER NOT NULL)""");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS donation (
                          id INTEGER PRIMARY KEY AUTOINCREMENT,
                          received_at INTEGER NOT NULL,
                          channel_id TEXT NOT NULL,
                          streamer_uuid TEXT,
                          streamer_name TEXT,
                          donator_channel_id TEXT,
                          donator_nickname TEXT,
                          amount INTEGER NOT NULL,
                          type TEXT,
                          message TEXT,
                          status TEXT NOT NULL,
                          test INTEGER NOT NULL DEFAULT 0,
                          note TEXT)""");
                s.execute("CREATE INDEX IF NOT EXISTS donation_channel ON donation(channel_id, id)");
                s.execute("CREATE INDEX IF NOT EXISTS donation_status ON donation(status)");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS session_log (
                          id INTEGER PRIMARY KEY AUTOINCREMENT,
                          at INTEGER NOT NULL,
                          event TEXT NOT NULL,
                          detail TEXT)""");
            }
            return null;
        }).get();
    }

    void close() {
        exec.shutdown();
        try { exec.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        try { if (conn != null) conn.close(); } catch (SQLException ignored) { }
    }

    <T> CompletableFuture<T> submit(SqlWork<T> work) {
        CompletableFuture<T> f = new CompletableFuture<>();
        exec.execute(() -> {
            try { f.complete(work.run(conn)); } catch (Throwable e) { f.completeExceptionally(e); }
        });
        return f;
    }

    // ── 연동 ────────────────────────────────────────────

    CompletableFuture<List<Link>> loadLinks() {
        return submit(c -> {
            List<Link> out = new ArrayList<>();
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT * FROM chzzk_link")) {
                while (r.next()) {
                    out.add(new Link(UUID.fromString(r.getString("uuid")), r.getString("player_name"),
                            r.getString("channel_id"), r.getString("channel_name"),
                            r.getString("access_token"), r.getString("refresh_token"), r.getLong("expires_at"),
                            Link.Status.valueOf(r.getString("status")), r.getLong("linked_at")));
                }
            }
            return out;
        });
    }

    CompletableFuture<Void> saveLink(Link l) {
        return submit(c -> {
            try (PreparedStatement p = c.prepareStatement("""
                    INSERT INTO chzzk_link (uuid, player_name, channel_id, channel_name, access_token, refresh_token, expires_at, status, linked_at)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    ON CONFLICT(uuid) DO UPDATE SET player_name=excluded.player_name, channel_id=excluded.channel_id,
                      channel_name=excluded.channel_name, access_token=excluded.access_token, refresh_token=excluded.refresh_token,
                      expires_at=excluded.expires_at, status=excluded.status""")) {
                p.setString(1, l.uuid().toString());
                p.setString(2, l.playerName());
                p.setString(3, l.channelId());
                p.setString(4, l.channelName());
                p.setString(5, l.accessToken());
                p.setString(6, l.refreshToken());
                p.setLong(7, l.expiresAt());
                p.setString(8, l.status().name());
                p.setLong(9, l.linkedAt());
                p.executeUpdate();
            }
            return null;
        });
    }

    CompletableFuture<Void> deleteLink(UUID uuid) {
        return submit(c -> {
            try (PreparedStatement p = c.prepareStatement("DELETE FROM chzzk_link WHERE uuid = ?")) {
                p.setString(1, uuid.toString());
                p.executeUpdate();
            }
            return null;
        });
    }

    // ── 장부 ────────────────────────────────────────────

    /** 기록하고 장부 번호를 돌려준다 */
    CompletableFuture<Long> insertDonation(Donation d) {
        return submit(c -> {
            try (PreparedStatement p = c.prepareStatement("""
                    INSERT INTO donation (received_at, channel_id, streamer_uuid, streamer_name, donator_channel_id,
                      donator_nickname, amount, type, message, status, test, note) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",
                    Statement.RETURN_GENERATED_KEYS)) {
                p.setLong(1, d.receivedAt());
                p.setString(2, d.channelId());
                p.setString(3, d.streamerUuid() == null ? null : d.streamerUuid().toString());
                p.setString(4, d.streamerName());
                p.setString(5, d.donatorChannelId());
                p.setString(6, d.donatorNickname());
                p.setLong(7, d.amount());
                p.setString(8, d.type());
                p.setString(9, d.message());
                p.setString(10, d.status().name());
                p.setInt(11, d.test() ? 1 : 0);
                p.setString(12, d.note());
                p.executeUpdate();
                try (ResultSet k = p.getGeneratedKeys()) { return k.next() ? k.getLong(1) : -1L; }
            }
        });
    }

    /** 최신순. channelId 가 null 이면 전체 */
    CompletableFuture<List<Donation>> donations(String channelId, int limit, int offset) {
        return submit(c -> {
            String sql = "SELECT * FROM donation" + (channelId == null ? "" : " WHERE channel_id = ?") + " ORDER BY id DESC LIMIT ? OFFSET ?";
            try (PreparedStatement p = c.prepareStatement(sql)) {
                int i = 1;
                if (channelId != null) p.setString(i++, channelId);
                p.setInt(i++, limit);
                p.setInt(i, offset);
                List<Donation> out = new ArrayList<>();
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) {
                        String su = r.getString("streamer_uuid");
                        out.add(new Donation(r.getLong("id"), r.getLong("received_at"), r.getString("channel_id"),
                                su == null ? null : UUID.fromString(su), r.getString("streamer_name"),
                                r.getString("donator_channel_id"), r.getString("donator_nickname"), r.getLong("amount"),
                                r.getString("type"), r.getString("message"), Donation.Status.valueOf(r.getString("status")),
                                r.getInt("test") == 1, r.getString("note")));
                    }
                }
                return out;
            }
        });
    }

    CompletableFuture<Void> setStatus(long id, Donation.Status status, String note) {
        return submit(c -> {
            try (PreparedStatement p = c.prepareStatement("UPDATE donation SET status = ?, note = ? WHERE id = ?")) {
                p.setString(1, status.name());
                p.setString(2, note);
                p.setLong(3, id);
                p.executeUpdate();
            }
            return null;
        });
    }

    CompletableFuture<Donation> donation(long id) {
        return query("SELECT * FROM donation WHERE id = ?", p -> p.setLong(1, id)).thenApply(l -> l.isEmpty() ? null : l.get(0));
    }

    /** 이 스트리머 앞으로 아직 지급 안 된 것 (접속하면 지급) */
    CompletableFuture<List<Donation>> pendingFor(UUID uuid) {
        return query("SELECT * FROM donation WHERE streamer_uuid = ? AND status = 'PENDING' ORDER BY id", p -> p.setString(1, uuid.toString()));
    }

    /** 대기·실패 (관리자 /후원 미지급) */
    CompletableFuture<List<Donation>> unpaid(int limit, int offset) {
        return query("SELECT * FROM donation WHERE status IN ('PENDING','FAILED') ORDER BY id DESC LIMIT ? OFFSET ?",
                p -> { p.setInt(1, limit); p.setInt(2, offset); });
    }

    CompletableFuture<Integer> countUnpaid() {
        return submit(c -> {
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM donation WHERE status IN ('PENDING','FAILED')")) {
                return r.next() ? r.getInt(1) : 0;
            }
        });
    }

    interface Binder { void bind(PreparedStatement p) throws SQLException; }

    private CompletableFuture<List<Donation>> query(String sql, Binder binder) {
        return submit(c -> {
            try (PreparedStatement p = c.prepareStatement(sql)) {
                binder.bind(p);
                List<Donation> out = new ArrayList<>();
                try (ResultSet r = p.executeQuery()) { while (r.next()) out.add(row(r)); }
                return out;
            }
        });
    }

    private static Donation row(ResultSet r) throws SQLException {
        String su = r.getString("streamer_uuid");
        return new Donation(r.getLong("id"), r.getLong("received_at"), r.getString("channel_id"),
                su == null ? null : UUID.fromString(su), r.getString("streamer_name"),
                r.getString("donator_channel_id"), r.getString("donator_nickname"), r.getLong("amount"),
                r.getString("type"), r.getString("message"), Donation.Status.valueOf(r.getString("status")),
                r.getInt("test") == 1, r.getString("note"));
    }

    CompletableFuture<Integer> countDonations(String channelId) {
        return submit(c -> {
            String sql = "SELECT COUNT(*) FROM donation" + (channelId == null ? "" : " WHERE channel_id = ?");
            try (PreparedStatement p = c.prepareStatement(sql)) {
                if (channelId != null) p.setString(1, channelId);
                try (ResultSet r = p.executeQuery()) { return r.next() ? r.getInt(1) : 0; }
            }
        });
    }

    // ── 연결 기록 ───────────────────────────────────────

    void log(String event, String detail) {
        submit(c -> {
            try (PreparedStatement p = c.prepareStatement("INSERT INTO session_log (at, event, detail) VALUES (?,?,?)")) {
                p.setLong(1, System.currentTimeMillis());
                p.setString(2, event);
                p.setString(3, detail);
                p.executeUpdate();
            }
            return null;
        });
    }
}

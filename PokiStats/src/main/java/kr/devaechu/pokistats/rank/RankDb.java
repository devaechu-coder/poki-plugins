package kr.devaechu.pokistats.rank;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 인게임 순위용 로컬 저장소 (plugins/PokiStats/ranks.db, SQLite).
 * 통계 서버로 보내는 이벤트와 같은 걸 같은 순간에 여기에도 넣어서 웹과 숫자를 맞춤.
 * 이벤트 id 가 같으면 한 번만 들어감 (재전송·중복 읽기 안전).
 *
 * 모든 메서드는 한 스레드(Ranks 의 DB 스레드)에서만 부름.
 */
public final class RankDb {

    /** 순위 한 줄 */
    public record Row(String uuid, String name, double value, long reached, double extra) { }

    private static final Set<String> RANKED = Set.of("capture", "hatch", "evolve", "battle", "gym", "tower", "raid");

    private Connection conn;

    public void open(File file) throws SQLException, ClassNotFoundException {
        Class.forName("org.sqlite.JDBC");
        conn = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement s = conn.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS ev (
                      id TEXT PRIMARY KEY,
                      t TEXT NOT NULL,
                      at INTEGER NOT NULL,
                      uuid TEXT NOT NULL,
                      kind TEXT, result TEXT, species TEXT,
                      shiny INTEGER DEFAULT 0, legendary INTEGER DEFAULT 0, mythical INTEGER DEFAULT 0,
                      ball TEXT, floor INTEGER, trainer TEXT)""");
            s.execute("CREATE INDEX IF NOT EXISTS ev_t ON ev(t, uuid, at)");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS players (
                      uuid TEXT PRIMARY KEY,
                      name TEXT,
                      hidden INTEGER NOT NULL DEFAULT 0,
                      seen INTEGER)""");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS wealth (
                      uuid TEXT PRIMARY KEY,
                      name TEXT,
                      money REAL NOT NULL DEFAULT 0,
                      coin REAL NOT NULL DEFAULT 0,
                      at INTEGER NOT NULL)""");
        }
        conn.setAutoCommit(true);
    }

    public void close() {
        try {
            if (conn != null) conn.close();
        } catch (SQLException ignored) {
        }
    }

    /** 이벤트들을 넣기 (순위와 관계없는 종류는 무시) */
    public int apply(Collection<JsonObject> events) throws SQLException {
        int n = 0;
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO ev(id,t,at,uuid,kind,result,species,shiny,legendary,mythical,ball,floor,trainer) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            for (JsonObject o : events) {
                String t = str(o, "t");
                String id = str(o, "id");
                String uuid = str(o, "uuid");
                if (t == null || id == null || uuid == null || !RANKED.contains(t)) continue;
                String species = "evolve".equals(t) ? str(o, "to") : str(o, "species");
                if ("raid".equals(t)) species = str(o, "boss");
                ps.setString(1, id);
                ps.setString(2, t);
                ps.setLong(3, num(o, "at"));
                ps.setString(4, uuid);
                ps.setString(5, str(o, "kind"));
                ps.setString(6, str(o, "result"));
                ps.setString(7, species == null ? "" : species);
                ps.setInt(8, bool(o, "shiny"));
                ps.setInt(9, bool(o, "legendary"));
                ps.setInt(10, bool(o, "mythical"));
                ps.setString(11, str(o, "ball"));
                ps.setLong(12, num(o, "floor"));
                ps.setString(13, str(o, "trainerId"));
                ps.addBatch();
                n++;
            }
            ps.executeBatch();
            conn.commit();
        } catch (SQLException ex) {
            conn.rollback();
            throw ex;
        } finally {
            conn.setAutoCommit(true);
        }
        return n;
    }

    public void setPlayer(String uuid, String name, boolean hidden) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO players(uuid,name,hidden,seen) VALUES (?,?,?,?) "
                        + "ON CONFLICT(uuid) DO UPDATE SET name=excluded.name, hidden=excluded.hidden, seen=excluded.seen")) {
            ps.setString(1, uuid);
            ps.setString(2, name);
            ps.setInt(3, hidden ? 1 : 0);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    public record Wealth(String uuid, String name, double money, double coin) { }

    public void setWealth(Collection<Wealth> list, long at) throws SQLException {
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO wealth(uuid,name,money,coin,at) VALUES (?,?,?,?,?) "
                        + "ON CONFLICT(uuid) DO UPDATE SET name=excluded.name, money=excluded.money, coin=excluded.coin, at=excluded.at")) {
            for (Wealth w : list) {
                ps.setString(1, w.uuid());
                ps.setString(2, w.name());
                ps.setDouble(3, w.money());
                ps.setDouble(4, w.coin());
                ps.setLong(5, at);
                ps.addBatch();
            }
            ps.executeBatch();
            conn.commit();
        } catch (SQLException ex) {
            conn.rollback();
            throw ex;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    /** 시즌 초기화 — 이벤트 기록만 지움 (돈은 지금 잔액이라 그대로) */
    public int reset() throws SQLException {
        try (Statement s = conn.createStatement()) {
            int n = s.executeUpdate("DELETE FROM ev");
            s.execute("VACUUM");
            return n;
        }
    }

    /** 이름 (players → wealth 순으로) */
    public Map<String, String> names() throws SQLException {
        Map<String, String> m = new HashMap<>();
        try (Statement s = conn.createStatement()) {
            try (ResultSet r = s.executeQuery("SELECT uuid, name FROM wealth WHERE name IS NOT NULL")) {
                while (r.next()) m.put(r.getString(1), r.getString(2));
            }
            try (ResultSet r = s.executeQuery("SELECT uuid, name FROM players WHERE name IS NOT NULL")) {
                while (r.next()) m.put(r.getString(1), r.getString(2));
            }
        }
        return m;
    }

    public Set<String> hiddenUuids() throws SQLException {
        Set<String> out = new HashSet<>();
        try (Statement s = conn.createStatement(); ResultSet r = s.executeQuery("SELECT uuid FROM players WHERE hidden=1")) {
            while (r.next()) out.add(r.getString(1));
        }
        return out;
    }

    /**
     * 한 순위 계산. since=0 이면 전체, 아니면 그 시각(ms) 이후만.
     * 정렬: 값 큰 순 → 먼저 도달한 순. 이름 없는 줄은 uuid 앞부분.
     */
    public List<Row> rank(Category c, long since, Map<String, String> names, Set<String> hidden) throws SQLException {
        List<Row> rows = switch (c.kind) {
            case COUNT -> grouped("SELECT uuid, COUNT(*), MAX(at) FROM ev WHERE " + c.where + " AND at>=? GROUP BY uuid", since);
            case DISTINCT_SPECIES -> grouped("SELECT uuid, COUNT(*), MAX(f) FROM (SELECT uuid, species, MIN(at) f FROM ev WHERE "
                    + c.where + " AND species<>'' AND at>=? GROUP BY uuid, species) GROUP BY uuid", since);
            case DISTINCT_TRAINER -> grouped("SELECT uuid, COUNT(*), MAX(f) FROM (SELECT uuid, trainer, MIN(at) f FROM ev WHERE "
                    + c.where + " AND trainer IS NOT NULL AND at>=? GROUP BY uuid, trainer) GROUP BY uuid", since);
            case MAX_FLOOR -> grouped("SELECT e.uuid, m.mx, MIN(e.at) FROM ev e JOIN "
                    + "(SELECT uuid, MAX(floor) mx FROM ev WHERE t='tower' AND result='win' AND at>=? GROUP BY uuid) m "
                    + "ON e.uuid=m.uuid AND e.floor=m.mx WHERE e.t='tower' AND e.result='win' AND e.at>=? GROUP BY e.uuid", since, since);
            case STREAK -> streak(since);
            case WEALTH -> grouped("SELECT uuid, " + c.where + ", 0 FROM wealth WHERE " + c.where + " > 0");
        };
        Map<String, Double> extra = c == Category.PVP ? pvpLosses(since) : Map.of();
        List<Row> out = new ArrayList<>(rows.size());
        for (Row r : rows) {
            if (hidden.contains(r.uuid())) continue;
            String name = names.getOrDefault(r.uuid(), r.uuid().length() > 8 ? r.uuid().substring(0, 8) : r.uuid());
            out.add(new Row(r.uuid(), name, r.value(), r.reached(), extra.getOrDefault(r.uuid(), 0d)));
        }
        out.sort(Comparator.comparingDouble(Row::value).reversed().thenComparingLong(Row::reached));
        return out;
    }

    private List<Row> grouped(String sql, long... params) throws SQLException {
        List<Row> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setLong(i + 1, params[i]);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    double v = r.getDouble(2);
                    if (v <= 0) continue;
                    out.add(new Row(r.getString(1), null, v, r.getLong(3), 0));
                }
            }
        }
        return out;
    }

    /** NPC·PvP 배틀만, 시간순으로 win 이 끊기지 않은 최대 길이 (lose 에서 끊김, flee·야생은 무시) */
    private List<Row> streak(long since) throws SQLException {
        List<Row> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT uuid, at, result FROM ev WHERE t='battle' AND kind IN ('npc','pvp') AND result IN ('win','lose') AND at>=? ORDER BY uuid, at, id")) {
            ps.setLong(1, since);
            try (ResultSet r = ps.executeQuery()) {
                String cur = null;
                int run = 0, best = 0;
                long bestAt = 0;
                while (r.next()) {
                    String u = r.getString(1);
                    if (!u.equals(cur)) {
                        if (cur != null && best > 0) out.add(new Row(cur, null, best, bestAt, 0));
                        cur = u;
                        run = 0;
                        best = 0;
                        bestAt = 0;
                    }
                    if ("win".equals(r.getString(3))) {
                        run++;
                        if (run > best) {
                            best = run;
                            bestAt = r.getLong(2);
                        }
                    } else {
                        run = 0;
                    }
                }
                if (cur != null && best > 0) out.add(new Row(cur, null, best, bestAt, 0));
            }
        }
        return out;
    }

    private Map<String, Double> pvpLosses(long since) throws SQLException {
        Map<String, Double> m = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT uuid, COUNT(*) FROM ev WHERE t='battle' AND kind='pvp' AND result='lose' AND at>=? GROUP BY uuid")) {
            ps.setLong(1, since);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) m.put(r.getString(1), r.getDouble(2));
            }
        }
        return m;
    }

    /** 전 종류 한 번에 */
    public Map<Category, List<Row>> rankAll(long since, Map<String, String> names, Set<String> hidden) throws SQLException {
        Map<Category, List<Row>> m = new EnumMap<>(Category.class);
        for (Category c : Category.values()) {
            if (since > 0 && !c.hasWeekly()) continue;
            m.put(c, rank(c, since, names, hidden));
        }
        return m;
    }

    // ── JSON 꺼내기 ──

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static long num(JsonObject o, String k) {
        JsonElement e = o.get(k);
        try {
            return e == null || e.isJsonNull() ? 0 : e.getAsLong();
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    private static int bool(JsonObject o, String k) {
        JsonElement e = o.get(k);
        try {
            return e != null && !e.isJsonNull() && e.getAsBoolean() ? 1 : 0;
        } catch (RuntimeException ex) {
            return 0;
        }
    }
}

package kr.devaechu.pokistats;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * PokiDonate 장부(donate.db)를 읽기만 해서 후원 통계 기록을 만든다. PokiDonate 코드는 안 건드림.
 * 지급이 끝난 줄(PENDING 아닌 것)만, 상태가 바뀌면 다시 보냄 (통계 서버는 donationId 로 덮어씀).
 */
final class DonateReader {

    private final File stateFile;
    private final Logger log;
    /** 장부 번호 → 마지막으로 보낸 상태 */
    private final Map<Long, String> sent = new HashMap<>();
    private boolean warnedMissing;

    DonateReader(File stateFile, Logger log) {
        this.stateFile = stateFile;
        this.log = log;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(stateFile);
        if (y.isConfigurationSection("sent")) {
            for (String k : y.getConfigurationSection("sent").getKeys(false)) {
                try {
                    sent.put(Long.parseLong(k), y.getString("sent." + k));
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }

    /** @return 새로 큐에 넣은 개수 */
    int poll(File db, boolean includeTest, EventQueue queue) {
        if (!db.isFile()) {
            if (!warnedMissing) log.info("후원 장부가 없어서 후원 통계는 건너뜀: " + db.getPath());
            warnedMissing = true;
            return 0;
        }
        warnedMissing = false;
        int added = 0;
        try {
            Class.forName("org.sqlite.JDBC");
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath())) {
                try (Statement s = c.createStatement()) {
                    s.execute("PRAGMA busy_timeout=5000");
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT id, received_at, channel_id, streamer_uuid, streamer_name, amount, type, status, test, note "
                                + "FROM donation WHERE status <> 'PENDING' ORDER BY id");
                     ResultSet r = ps.executeQuery()) {
                    while (r.next()) {
                        long id = r.getLong("id");
                        String status = r.getString("status");
                        boolean test = r.getInt("test") != 0;
                        if (test && !includeTest) continue;
                        if (status.equals(sent.get(id))) continue;
                        queue.add(Json.event("donate", r.getLong("received_at"))
                                .put("id", "donate-" + id + "-" + status)
                                .put("donationId", id)
                                .put("amount", r.getLong("amount"))
                                .put("status", status)
                                .put("note", r.getString("note"))
                                .put("type", r.getString("type"))
                                .put("test", test)
                                .put("channelId", r.getString("channel_id"))
                                .put("streamerUuid", r.getString("streamer_uuid"))
                                .put("streamerName", r.getString("streamer_name"))
                                .toString());
                        sent.put(id, status);
                        added++;
                    }
                }
            }
        } catch (Exception ex) {
            log.warning("후원 장부 읽기 실패 (다음에 다시): " + ex);
            return added;
        }
        if (added > 0) save();
        return added;
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        sent.forEach((k, v) -> y.set("sent." + k, v));
        try {
            y.save(stateFile);
        } catch (Exception e) {
            log.warning("donate-state.yml 저장 실패: " + e);
        }
    }
}

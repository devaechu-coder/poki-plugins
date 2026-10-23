package kr.devaechu.pokistats;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.logging.Logger;

/**
 * 통계 서버로 POST (서버 → 밖으로만). 한 번에 최대 500개씩, 2xx 를 받은 만큼만 큐에서 지움.
 */
final class Sender {

    static final int BATCH = 500;
    private static final int MAX_BATCHES_PER_FLUSH = 40;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Logger log;
    private final String userAgent;

    volatile String lastResult = "아직 안 보냄";
    volatile long lastOkAt;
    volatile int lastSent;

    Sender(Logger log, String version) {
        this.log = log;
        this.userAgent = "PokiStats/" + version;
    }

    /**
     * 큐에 있는 걸 보냄. 실패하면 남겨두고 다음에 다시.
     * @return 보낸 개수
     */
    int flush(EventQueue queue, String endpoint, String secret, String serverId, boolean debug) {
        if (endpoint == null || endpoint.isBlank()) {
            lastResult = "endpoint 비어 있음 — 기록만 쌓는 중 (" + queue.size() + "개)";
            return 0;
        }
        if (secret == null || secret.isBlank()) {
            lastResult = "secret 비어 있음 — 안 보냄";
            return 0;
        }
        int total = 0;
        for (int b = 0; b < MAX_BATCHES_PER_FLUSH; b++) {
            List<String> batch = queue.peek(BATCH);
            if (batch.isEmpty()) break;
            String body = "{\"server\":" + Json.str(serverId) + ",\"sentAt\":" + System.currentTimeMillis()
                    + ",\"plugin\":" + Json.str(userAgent) + ",\"events\":[" + String.join(",", batch) + "]}";
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint.strip()))
                        .timeout(Duration.ofSeconds(20))
                        .header("Content-Type", "application/json; charset=utf-8")
                        .header("Authorization", "Bearer " + secret.strip())
                        .header("User-Agent", userAgent)
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                int code = res.statusCode();
                if (code / 100 == 2) {
                    queue.drop(batch.size());
                    total += batch.size();
                    continue;
                }
                String msg = res.body() == null ? "" : res.body().strip();
                if (msg.length() > 120) msg = msg.substring(0, 120) + "…";
                lastResult = "실패 HTTP " + code + (code == 401 || code == 403 ? " (secret 확인)" : "") + (msg.isEmpty() ? "" : " — " + msg);
                log.warning("통계 전송 " + lastResult + " · 남은 기록 " + queue.size() + "개");
                return total;
            } catch (IllegalArgumentException ex) {
                lastResult = "endpoint 주소가 이상해요: " + endpoint;
                log.warning(lastResult);
                return total;
            } catch (Exception ex) {
                lastResult = "실패 — " + ex.getClass().getSimpleName() + (ex.getMessage() == null ? "" : ": " + ex.getMessage());
                log.warning("통계 전송 " + lastResult + " · 남은 기록 " + queue.size() + "개 (다음에 다시)");
                if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
                return total;
            }
        }
        lastOkAt = System.currentTimeMillis();
        lastSent = total;
        lastResult = "성공 — " + total + "개 보냄" + (queue.size() > 0 ? " · 남은 " + queue.size() + "개" : "");
        if (debug && total > 0) log.info("통계 " + lastResult);
        return total;
    }
}

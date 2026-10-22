package kr.devaechu.pokidonate;

import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * /후원 연동 흐름.
 *   1. 일회용 state 를 만들어 인증 페이지 링크로 보여준다
 *   2. 스트리머가 치지직에서 허용하면 인증 페이지(Worker)가 코드를 잠깐 보관
 *   3. 여기서 3초마다 인증 페이지에 "이 state 코드 왔어?" 물어보고 가져온다
 *   4. 코드 → 토큰 → 채널 확인 → 저장 → 후원 구독 시작
 */
final class LinkFlow {

    interface Events {
        void tell(UUID player, String msg);

        /** 연동 성공. 이전 연동(다른 채널)이 있었으면 old 로 넘어온다 */
        void linked(Link link, Link old);
    }

    record Pending(UUID uuid, String playerName, long expiresAt) { }

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build();
    private final ChzzkApi api;
    private final Links links;
    private final Db db;
    private final Logger log;
    private final Events events;
    private final String bridgeUrl;
    private final String bridgeSecret;
    private final long expireMs;
    private long lastWarnAt;

    LinkFlow(ChzzkApi api, Links links, Db db, Logger log, Events events, String bridgeUrl, String bridgeSecret, int expireMinutes) {
        this.api = api;
        this.links = links;
        this.db = db;
        this.log = log;
        this.events = events;
        this.bridgeUrl = bridgeUrl.replaceAll("/+$", "");
        this.bridgeSecret = bridgeSecret;
        this.expireMs = expireMinutes * 60_000L;
    }

    /** 새 연동 링크. 같은 사람의 이전 링크는 무효 */
    String begin(UUID uuid, String playerName) {
        pending.values().removeIf(p -> p.uuid().equals(uuid));
        byte[] b = new byte[24];
        random.nextBytes(b);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(b); // 32자
        pending.put(state, new Pending(uuid, playerName, System.currentTimeMillis() + expireMs));
        return bridgeUrl + "/chzzk/start?state=" + state;
    }

    boolean hasPending() {
        return !pending.isEmpty();
    }

    /** 3초마다 (비동기 스레드) */
    void poll() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Pending> e : pending.entrySet()) {
            String state = e.getKey();
            Pending p = e.getValue();
            if (p.expiresAt() < now) {
                if (pending.remove(state, p)) events.tell(p.uuid(), "&7연동 링크가 만료됐어요. 필요하면 &e/후원 연동 &7을 다시 입력해 주세요.");
                continue;
            }
            String code = claim(state);
            if (code != null && pending.remove(state, p)) complete(p, state, code);
        }
    }

    private String claim(String state) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(bridgeUrl + "/chzzk/claim?state=" + URLEncoder.encode(state, StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(10))
                    .header("X-Poki-Key", bridgeSecret)
                    .GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() == 200) return JsonParser.parseString(res.body()).getAsJsonObject().get("code").getAsString();
            if (res.statusCode() != 204) warn("인증 페이지 응답 " + res.statusCode() + (res.statusCode() == 401 ? " — bridge.secret 이 인증 페이지와 달라요" : ""));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            warn("인증 페이지에 접속 못 함: " + e.getMessage());
        }
        return null;
    }

    private void complete(Pending p, String state, String code) {
        ChzzkApi.Tokens t;
        ChzzkApi.Me me;
        try {
            t = api.exchangeCode(code, state);
            me = api.me(t.accessToken());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warning("[PokiDonate] " + p.playerName() + " 연동 실패: " + e.getMessage());
            events.tell(p.uuid(), "&c연동에 실패했어요. &e/후원 연동 &c으로 다시 시도해 주세요. &8(" + e.getMessage() + ")");
            return;
        }
        if (me.channelId().isEmpty()) {
            events.tell(p.uuid(), "&c치지직 채널 정보를 못 가져왔어요. 앱 권한에 '유저 조회'가 있는지 운영진에게 알려주세요.");
            return;
        }

        Link other = links.byChannel(me.channelId());
        if (other != null && !other.uuid().equals(p.uuid())) {
            events.tell(p.uuid(), "&c'" + me.channelName() + "' 채널은 이미 &f" + other.playerName() + " &c계정에 연동돼 있어요. 운영진에게 문의해 주세요.");
            return;
        }

        Link old = links.get(p.uuid());
        Link link = new Link(p.uuid(), p.playerName(), me.channelId(), me.channelName(),
                t.accessToken(), t.refreshToken(), t.expiresAt(), Link.Status.ACTIVE,
                old != null && old.channelId().equals(me.channelId()) ? old.linkedAt() : System.currentTimeMillis());
        links.put(link);
        db.saveLink(link).join();
        db.log("linked", me.channelName() + " ↔ " + p.playerName());
        log.info("[PokiDonate] " + p.playerName() + " ↔ 치지직 '" + me.channelName() + "' 연동 완료");
        events.linked(link, old != null && !old.channelId().equals(me.channelId()) ? old : null);
    }

    private void warn(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastWarnAt < 60_000) return; // 1분에 한 번만
        lastWarnAt = now;
        log.warning("[PokiDonate] " + msg);
    }
}

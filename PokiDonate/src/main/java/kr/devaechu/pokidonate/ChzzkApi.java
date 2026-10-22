package kr.devaechu.pokidonate;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 치지직 공식 Open API (openapi.chzzk.naver.com) 호출.
 * 모든 메서드는 막히는(blocking) 호출이라 비동기 스레드에서만 부른다.
 */
public final class ChzzkApi {

    public static final String DEFAULT_BASE = "https://openapi.chzzk.naver.com";

    /** access token 과 만료 시각 */
    public record Tokens(String accessToken, String refreshToken, long expiresAt) { }

    /** 토큰 주인의 채널 */
    public record Me(String channelId, String channelName) { }

    /** API 오류. httpStatus 401 + message INVALID_TOKEN 이면 토큰 문제 */
    public static final class ApiException extends IOException {
        public final int httpStatus;
        public final String apiMessage;

        ApiException(int httpStatus, String apiMessage) {
            super("치지직 API " + httpStatus + " " + apiMessage);
            this.httpStatus = httpStatus;
            this.apiMessage = apiMessage;
        }

        public boolean isInvalidToken() {
            return httpStatus == 401 && apiMessage != null && apiMessage.contains("INVALID_TOKEN");
        }
    }

    private final String base;
    private final String clientId;
    private final String clientSecret;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build();

    public ChzzkApi(String base, String clientId, String clientSecret) {
        this.base = base;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    // ── 인증 ────────────────────────────────────────────

    /** 인증 코드 → 토큰 */
    public Tokens exchangeCode(String code, String state) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("grantType", "authorization_code");
        body.addProperty("clientId", clientId);
        body.addProperty("clientSecret", clientSecret);
        body.addProperty("code", code);
        body.addProperty("state", state);
        return tokens(call("POST", "/auth/v1/token", body, null, false));
    }

    /** 토큰 갱신. refresh token 은 일회용이라 응답의 새 값을 반드시 저장해야 한다. */
    public Tokens refresh(String refreshToken) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("grantType", "refresh_token");
        body.addProperty("refreshToken", refreshToken);
        body.addProperty("clientId", clientId);
        body.addProperty("clientSecret", clientSecret);
        return tokens(call("POST", "/auth/v1/token", body, null, false));
    }

    /** 토큰 폐기 (연동 해제 때). 실패해도 무시해도 된다. */
    public void revoke(String token) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("clientId", clientId);
        body.addProperty("clientSecret", clientSecret);
        body.addProperty("token", token);
        body.addProperty("tokenTypeHint", "access_token");
        call("POST", "/auth/v1/token/revoke", body, null, false);
    }

    /** 토큰 주인의 채널 ID·이름 (API Scope: 유저 조회) */
    public Me me(String accessToken) throws IOException, InterruptedException {
        JsonObject c = call("GET", "/open/v1/users/me", null, accessToken, false).getAsJsonObject();
        return new Me(str(c, "channelId"), str(c, "channelName"));
    }

    // ── 세션 ────────────────────────────────────────────

    /** 클라이언트 세션 URL 발급 (연결 최대 10개, 세션당 구독 최대 30개) */
    public String createClientSession() throws IOException, InterruptedException {
        return str(call("GET", "/open/v1/sessions/auth/client", null, null, true).getAsJsonObject(), "url");
    }

    /** 이 세션에서 토큰 주인 채널의 후원 이벤트 받기 (API Scope: 후원 조회) */
    public void subscribeDonation(String sessionKey, String accessToken) throws IOException, InterruptedException {
        call("POST", "/open/v1/sessions/events/subscribe/donation?sessionKey=" + enc(sessionKey), null, accessToken, true);
    }

    public void unsubscribeDonation(String sessionKey, String accessToken) throws IOException, InterruptedException {
        call("POST", "/open/v1/sessions/events/unsubscribe/donation?sessionKey=" + enc(sessionKey), null, accessToken, true);
    }

    // ── 공통 ────────────────────────────────────────────

    /** 응답 {code, message, content} 에서 content 를 꺼낸다 */
    private JsonElement call(String method, String path, JsonObject body, String bearer, boolean clientAuth)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json");
        if (clientAuth) {
            b.header("Client-Id", clientId).header("Client-Secret", clientSecret);
        }
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));

        HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonObject env = null;
        try {
            if (res.body() != null && !res.body().isBlank()) env = JsonParser.parseString(res.body()).getAsJsonObject();
        } catch (RuntimeException ignored) { }

        String msg = env != null && env.has("message") && !env.get("message").isJsonNull() ? env.get("message").getAsString() : "";
        int code = env != null && env.has("code") && env.get("code").isJsonPrimitive() ? env.get("code").getAsInt() : res.statusCode();
        if (res.statusCode() != 200 || code != 200) {
            throw new ApiException(res.statusCode() != 200 ? res.statusCode() : code, msg.isEmpty() ? String.valueOf(res.statusCode()) : msg);
        }
        if (env == null || !env.has("content") || env.get("content").isJsonNull()) return JsonNull.INSTANCE;
        return env.get("content");
    }

    private static Tokens tokens(JsonElement content) throws IOException {
        if (!content.isJsonObject()) throw new IOException("토큰 응답이 비어 있음");
        JsonObject c = content.getAsJsonObject();
        long expiresIn = 86_400;
        try {
            if (c.has("expiresIn")) expiresIn = Long.parseLong(c.get("expiresIn").getAsString().trim()); // 문자열·숫자 둘 다
        } catch (RuntimeException ignored) { }
        return new Tokens(str(c, "accessToken"), str(c, "refreshToken"), System.currentTimeMillis() + expiresIn * 1000L);
    }

    static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}

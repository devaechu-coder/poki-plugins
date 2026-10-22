package kr.devaechu.pokidonate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 치지직 세션 서버용 최소 Socket.IO 2.x 클라이언트 (Engine.IO v3, websocket 전송만).
 * 외부 라이브러리 없이 자바 기본 WebSocket 으로 붙는다 — 아크라이트에서 라이브러리 충돌이 없게.
 *
 * 프레임: "0{…}" 열기(핑 주기) · "40" 연결 · "2" 핑(우리가 보냄) · "3" 퐁 · "42[이름, 데이터]" 이벤트.
 */
final class SessionSocket implements WebSocket.Listener {

    interface Handler {
        /** SYSTEM, DONATION 등. 데이터가 JSON 문자열로 오면 한 번 더 풀어서 넘긴다. */
        void onEvent(String name, JsonElement data);

        /** 연결이 끝났을 때 딱 한 번 */
        void onClosed(String reason);
    }

    private static final ScheduledExecutorService PINGER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "PokiDonate-ping");
        t.setDaemon(true);
        return t;
    });

    private final Handler handler;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final StringBuilder partial = new StringBuilder();
    private final Object sendLock = new Object();
    private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);
    private volatile WebSocket ws;
    private volatile ScheduledFuture<?> pingTask;
    private volatile long lastPong = System.currentTimeMillis();
    private volatile long pingInterval = 25_000, pingTimeout = 60_000;

    SessionSocket(Handler handler) {
        this.handler = handler;
    }

    /** 세션 URL(https://…?auth=…) 로 접속. 세션 URL 은 한 번만 쓸 수 있다. */
    CompletableFuture<WebSocket> connect(String sessionUrl) {
        URI uri = toEngineUri(sessionUrl);
        return HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(uri, this)
                .whenComplete((w, e) -> {
                    if (e != null) finish("접속 실패: " + e.getMessage());
                    else ws = w;
                });
    }

    static URI toEngineUri(String sessionUrl) {
        URI u = URI.create(sessionUrl);
        String scheme = "http".equalsIgnoreCase(u.getScheme()) ? "ws" : "wss";
        String q = u.getRawQuery() == null ? "" : u.getRawQuery() + "&";
        String port = u.getPort() > 0 ? ":" + u.getPort() : "";
        return URI.create(scheme + "://" + u.getHost() + port + "/socket.io/?" + q + "EIO=3&transport=websocket");
    }

    void close(String reason) {
        WebSocket w = ws;
        if (w != null && !w.isOutputClosed()) {
            try { w.sendClose(WebSocket.NORMAL_CLOSURE, reason); } catch (RuntimeException ignored) { }
        }
        finish(reason);
    }

    boolean isOpen() {
        return !closed.get();
    }

    // ── WebSocket.Listener ─────────────────────────────

    @Override
    public void onOpen(WebSocket webSocket) {
        ws = webSocket;
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        partial.append(data);
        if (last) {
            String frame = partial.toString();
            partial.setLength(0);
            try {
                handleFrame(frame);
            } catch (RuntimeException e) {
                // 이상한 프레임 하나 때문에 연결을 끊지 않는다
            }
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        finish("서버가 연결 종료 (" + statusCode + (reason == null || reason.isEmpty() ? "" : " " + reason) + ")");
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        finish("연결 오류: " + error.getMessage());
    }

    // ── Engine.IO / Socket.IO ──────────────────────────

    private void handleFrame(String f) {
        if (f.isEmpty()) return;
        switch (f.charAt(0)) {
            case '0' -> { // open
                JsonObject o = JsonParser.parseString(f.substring(1)).getAsJsonObject();
                if (o.has("pingInterval")) pingInterval = o.get("pingInterval").getAsLong();
                if (o.has("pingTimeout")) pingTimeout = o.get("pingTimeout").getAsLong();
                startPing();
            }
            case '1' -> finish("서버가 닫음");
            case '3' -> lastPong = System.currentTimeMillis();
            case '4' -> handleMessage(f.substring(1));
            default -> { }
        }
    }

    private void handleMessage(String m) {
        if (m.isEmpty()) return;
        char type = m.charAt(0);
        if (type == '1') { // namespace disconnect
            finish("세션 서버가 네임스페이스 종료");
            return;
        }
        if (type != '2') return; // 0 connect 등은 그냥 넘긴다
        String body = m.substring(1);
        int bracket = body.indexOf('[');
        if (bracket < 0) return;
        JsonArray arr = JsonParser.parseString(body.substring(bracket)).getAsJsonArray();
        if (arr.isEmpty()) return;
        String name = arr.get(0).getAsString();
        JsonElement data = arr.size() > 1 ? arr.get(1) : null;
        if (data != null && data.isJsonPrimitive() && data.getAsJsonPrimitive().isString()) {
            String s = data.getAsString().trim();
            if (s.startsWith("{") || s.startsWith("[")) data = JsonParser.parseString(s);
        }
        handler.onEvent(name, data);
    }

    private void startPing() {
        if (pingTask != null) pingTask.cancel(false);
        lastPong = System.currentTimeMillis();
        pingTask = PINGER.scheduleAtFixedRate(() -> {
            if (closed.get()) return;
            if (System.currentTimeMillis() - lastPong > pingInterval + pingTimeout) {
                close("퐁 응답 없음");
                return;
            }
            send("2");
        }, pingInterval, pingInterval, TimeUnit.MILLISECONDS);
    }

    private void send(String text) {
        WebSocket w = ws;
        if (w == null || closed.get()) return;
        synchronized (sendLock) { // 자바 WebSocket 은 보내기를 겹치면 안 된다
            sendChain = sendChain.handle((x, e) -> null).thenCompose(x -> w.sendText(text, true));
        }
    }

    private void finish(String reason) {
        if (!closed.compareAndSet(false, true)) return;
        if (pingTask != null) pingTask.cancel(false);
        WebSocket w = ws;
        if (w != null) {
            try { w.abort(); } catch (RuntimeException ignored) { }
        }
        handler.onClosed(reason);
    }
}

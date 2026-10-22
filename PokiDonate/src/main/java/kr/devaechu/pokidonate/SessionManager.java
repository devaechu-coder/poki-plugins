package kr.devaechu.pokidonate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * 치지직 세션 연결과 후원 구독 관리.
 *
 * 클라이언트 세션 하나에 여러 스트리머를 묶어서 구독한다 (세션당 최대 30개라 25개씩 나눔, 연결은 최대 10개).
 * 각 스트리머의 구독은 그 스트리머 본인 토큰으로 요청한다.
 * 연결이 끊기면 새 세션 URL 을 받아 다시 붙고, 구독도 자동으로 다시 한다.
 */
final class SessionManager {

    interface Sink {
        void onDonation(JsonObject data);

        /** 스트리머가 권한을 철회함 → 재연동 필요 */
        void onRevoked(String channelId);
    }

    static final int PER_SHARD = 25;
    static final int MAX_SHARDS = 10;

    private final ChzzkApi api;
    private final Links links;
    private final TokenKeeper tokens;
    private final Db db;
    private final Logger log;
    private final Sink sink;
    private final ExecutorService net;
    private final ScheduledExecutorService timer;
    private final boolean debug;
    private final List<Shard> shards = new CopyOnWriteArrayList<>();
    private volatile boolean running;

    final class Shard {
        final int no;
        final Set<String> channels = ConcurrentHashMap.newKeySet();
        final Set<String> subscribed = ConcurrentHashMap.newKeySet();
        volatile SessionSocket socket;
        volatile String sessionKey;
        volatile boolean connecting;
        volatile int failures;
        volatile long connectedAt;

        Shard(int no) { this.no = no; }
    }

    SessionManager(ChzzkApi api, Links links, TokenKeeper tokens, Db db, Logger log, Sink sink,
                   ExecutorService net, ScheduledExecutorService timer, boolean debug) {
        this.api = api;
        this.links = links;
        this.tokens = tokens;
        this.db = db;
        this.log = log;
        this.sink = sink;
        this.net = net;
        this.timer = timer;
        this.debug = debug;
    }

    void start() {
        running = true;
        for (Link l : links.all()) if (l.status() == Link.Status.ACTIVE) assign(l.channelId());
    }

    void stop() {
        running = false;
        for (Shard s : shards) {
            SessionSocket sock = s.socket;
            if (sock != null) sock.close("플러그인 종료");
        }
        shards.clear();
    }

    /** 이 채널의 후원을 받기 시작 */
    synchronized void assign(String channelId) {
        if (!running) return;
        Shard home = find(channelId);
        if (home == null) {
            for (Shard s : shards) if (s.channels.size() < PER_SHARD) { home = s; break; }
            if (home == null) {
                if (shards.size() >= MAX_SHARDS) {
                    log.severe("[PokiDonate] 치지직 연결 한도(10개 × 25명)를 넘었어요. " + channelId + " 구독 못 함");
                    return;
                }
                home = new Shard(shards.size() + 1);
                shards.add(home);
            }
            home.channels.add(channelId);
        }
        if (home.sessionKey != null) subscribe(home, channelId, 0);
        else if (home.socket == null && !home.connecting) connect(home, 0);
    }

    /** 이 채널 후원 그만 받기. accessToken 이 있으면 구독 해제 요청도 보낸다 */
    synchronized void remove(String channelId, String accessToken) {
        Shard s = find(channelId);
        if (s == null) return;
        s.channels.remove(channelId);
        boolean wasSubscribed = s.subscribed.remove(channelId);
        String key = s.sessionKey;
        if (wasSubscribed && key != null && accessToken != null) {
            net.execute(() -> {
                try { api.unsubscribeDonation(key, accessToken); } catch (Exception ignored) { }
            });
        }
        if (s.channels.isEmpty() && s.socket != null) s.socket.close("구독할 채널 없음");
    }

    /** /후원 현황 표시용 */
    String state(String channelId) {
        Shard s = find(channelId);
        if (s == null) return "꺼짐";
        if (s.subscribed.contains(channelId)) return "받는 중";
        if (s.sessionKey != null) return "구독 요청 중";
        return s.connecting || s.socket != null ? "연결 중" : "재연결 대기";
    }

    List<String> summary() {
        List<String> out = new ArrayList<>();
        for (Shard s : shards) {
            out.add("연결 " + s.no + ": " + (s.sessionKey != null ? "연결됨" : s.connecting ? "연결 중" : "끊김")
                    + " · 채널 " + s.channels.size() + " · 구독 " + s.subscribed.size());
        }
        return out;
    }

    private Shard find(String channelId) {
        for (Shard s : shards) if (s.channels.contains(channelId)) return s;
        return null;
    }

    // ── 연결 ────────────────────────────────────────────

    private void connect(Shard s, long delaySec) {
        s.connecting = true;
        timer.schedule(() -> net.execute(() -> doConnect(s)), delaySec, TimeUnit.SECONDS);
    }

    private void doConnect(Shard s) {
        if (!running || s.channels.isEmpty()) { s.connecting = false; return; }
        try {
            String url = api.createClientSession();
            SessionSocket sock = new SessionSocket(new SessionSocket.Handler() {
                @Override public void onEvent(String name, JsonElement data) { handle(s, name, data); }
                @Override public void onClosed(String reason) { closed(s, reason); }
            });
            s.socket = sock;
            sock.connect(url);
        } catch (Exception e) {
            s.connecting = false;
            s.socket = null;
            db.log("session_error", "연결 " + s.no + ": " + e.getMessage());
            log.warning("[PokiDonate] 치지직 세션 연결 실패 (연결 " + s.no + "): " + e.getMessage());
            retry(s);
        }
    }

    private void handle(Shard s, String name, JsonElement data) {
        if (debug) log.info("[PokiDonate] 연결 " + s.no + " ← " + name + " " + data);
        if ("SYSTEM".equalsIgnoreCase(name) && data != null && data.isJsonObject()) {
            JsonObject o = data.getAsJsonObject();
            String type = ChzzkApi.str(o, "type");
            JsonObject d = o.has("data") && o.get("data").isJsonObject() ? o.getAsJsonObject("data") : new JsonObject();
            switch (type) {
                case "connected" -> {
                    s.sessionKey = ChzzkApi.str(d, "sessionKey");
                    s.connecting = false;
                    s.failures = 0;
                    s.connectedAt = System.currentTimeMillis();
                    db.log("connected", "연결 " + s.no);
                    log.info("[PokiDonate] 치지직 세션 연결됨 (연결 " + s.no + ", 채널 " + s.channels.size() + "개)");
                    for (String ch : s.channels) subscribe(s, ch, 0);
                }
                case "subscribed" -> s.subscribed.add(ChzzkApi.str(d, "channelId"));
                case "unsubscribed" -> s.subscribed.remove(ChzzkApi.str(d, "channelId"));
                case "revoked" -> {
                    String ch = ChzzkApi.str(d, "channelId");
                    s.subscribed.remove(ch);
                    s.channels.remove(ch);
                    db.log("revoked", ch);
                    sink.onRevoked(ch);
                }
                default -> { }
            }
        } else if ("DONATION".equalsIgnoreCase(name) && data != null && data.isJsonObject()) {
            sink.onDonation(data.getAsJsonObject());
        }
    }

    private void closed(Shard s, String reason) {
        s.sessionKey = null;
        s.subscribed.clear();
        s.socket = null;
        s.connecting = false;
        if (!running) return;
        long alive = s.connectedAt == 0 ? 0 : (System.currentTimeMillis() - s.connectedAt) / 1000;
        s.connectedAt = 0;
        db.log("disconnected", "연결 " + s.no + ": " + reason + " (유지 " + alive + "초)");
        if (s.channels.isEmpty()) return;
        log.warning("[PokiDonate] 치지직 세션 끊김 (연결 " + s.no + "): " + reason + " — 다시 연결할게요");
        retry(s);
    }

    private void retry(Shard s) {
        if (!running) return;
        long delay = Math.min(120, 5L << Math.min(s.failures, 5)); // 5,10,20,40,80,120초
        s.failures++;
        connect(s, delay);
    }

    // ── 구독 ────────────────────────────────────────────

    private void subscribe(Shard s, String channelId, int attempt) {
        String key = s.sessionKey;
        if (key == null) return;
        net.execute(() -> {
            Link l = links.byChannel(channelId);
            if (l == null || l.status() != Link.Status.ACTIVE || !key.equals(s.sessionKey)) return;
            String token = tokens.fresh(l.uuid());
            if (token == null) return; // 재연동 필요로 표시됨
            try {
                api.subscribeDonation(key, token);
                s.subscribed.add(channelId);
            } catch (ChzzkApi.ApiException e) {
                if (e.isInvalidToken()) {
                    tokens.markRelink(l, "구독 요청 거부: " + e.apiMessage);
                    return;
                }
                failSubscribe(s, l, channelId, attempt, e.getMessage());
            } catch (Exception e) {
                failSubscribe(s, l, channelId, attempt, e.getMessage());
            }
        });
    }

    private void failSubscribe(Shard s, Link l, String channelId, int attempt, String why) {
        db.log("subscribe_error", l.channelName() + ": " + why);
        log.warning("[PokiDonate] " + l.channelName() + " 후원 구독 실패: " + why);
        if (attempt < 3) timer.schedule(() -> subscribe(s, channelId, attempt + 1), 30L * (attempt + 1), TimeUnit.SECONDS);
    }
}

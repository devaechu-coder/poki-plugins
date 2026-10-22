package kr.devaechu.pokidonate;

import java.io.IOException;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 스트리머 토큰을 살아있게 유지한다.
 * access token 은 하루, refresh token 은 30일짜리고 갱신할 때마다 새로 바뀐다.
 * 서버가 켜져 있는 동안 주기적으로 갱신하니까 사실상 재연동할 일이 없다.
 */
final class TokenKeeper {

    private static final long REFRESH_BEFORE_MS = 60 * 60 * 1000L;       // 만료 1시간 전이면 갱신
    private static final long SWEEP_WINDOW_MS = 3 * 60 * 60 * 1000L;     // 주기 점검 때는 3시간 안에 만료되면 갱신

    private final ChzzkApi api;
    private final Links links;
    private final Db db;
    private final Logger log;
    private final Consumer<Link> onNeedsRelink;

    TokenKeeper(ChzzkApi api, Links links, Db db, Logger log, Consumer<Link> onNeedsRelink) {
        this.api = api;
        this.links = links;
        this.db = db;
        this.log = log;
        this.onNeedsRelink = onNeedsRelink;
    }

    /** 쓸 수 있는 access token. 못 구하면 null (재연동 필요로 표시됨) */
    String fresh(UUID uuid) {
        return refreshIfWithin(uuid, REFRESH_BEFORE_MS);
    }

    /** 곧 만료될 토큰 모두 갱신 (30분마다) */
    void sweep() {
        for (Link l : links.all()) {
            if (l.status() == Link.Status.ACTIVE) refreshIfWithin(l.uuid(), SWEEP_WINDOW_MS);
        }
    }

    private synchronized String refreshIfWithin(UUID uuid, long window) {
        Link l = links.get(uuid);
        if (l == null || l.status() != Link.Status.ACTIVE) return null;
        if (l.expiresAt() - System.currentTimeMillis() > window) return l.accessToken();
        try {
            Link updated = l.withTokens(api.refresh(l.refreshToken()));
            links.put(updated);
            db.saveLink(updated).join(); // refresh token 이 일회용이라 바로 저장
            return updated.accessToken();
        } catch (ChzzkApi.ApiException e) {
            if (e.isInvalidToken() || e.httpStatus == 400) {
                markRelink(l, "토큰 갱신 거부: " + e.apiMessage);
                return null;
            }
            log.log(Level.WARNING, "[PokiDonate] " + l.channelName() + " 토큰 갱신 실패 (다음에 다시 시도): " + e.getMessage());
            return l.expiresAt() > System.currentTimeMillis() ? l.accessToken() : null;
        } catch (IOException e) {
            log.log(Level.WARNING, "[PokiDonate] " + l.channelName() + " 토큰 갱신 중 네트워크 오류: " + e.getMessage());
            return l.expiresAt() > System.currentTimeMillis() ? l.accessToken() : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    void markRelink(Link l, String why) {
        Link cur = links.get(l.uuid());
        if (cur == null || cur.status() == Link.Status.RELINK) return;
        Link r = cur.withStatus(Link.Status.RELINK);
        links.put(r);
        db.saveLink(r);
        db.log("relink", cur.channelName() + " (" + cur.playerName() + "): " + why);
        log.warning("[PokiDonate] " + cur.channelName() + " (" + cur.playerName() + ") 재연동 필요 — " + why);
        onNeedsRelink.accept(r);
    }
}

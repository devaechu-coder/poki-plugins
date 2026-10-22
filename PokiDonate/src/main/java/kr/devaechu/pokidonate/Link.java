package kr.devaechu.pokidonate;

import java.util.UUID;

/** 마크 계정 ↔ 치지직 채널 연동 한 건. 토큰이 바뀌면 새 객체로 바꿔 끼운다. */
public record Link(
        UUID uuid,
        String playerName,
        String channelId,
        String channelName,
        String accessToken,
        String refreshToken,
        long expiresAt,   // access token 만료 시각 (epoch ms)
        Status status,
        long linkedAt
) {
    public enum Status {
        /** 정상 */
        ACTIVE,
        /** 토큰 만료·권한 철회 등으로 다시 /후원 연동 필요 */
        RELINK
    }

    public Link withTokens(ChzzkApi.Tokens t) {
        return new Link(uuid, playerName, channelId, channelName, t.accessToken(), t.refreshToken(), t.expiresAt(), Status.ACTIVE, linkedAt);
    }

    public Link withStatus(Status s) {
        return new Link(uuid, playerName, channelId, channelName, accessToken, refreshToken, expiresAt, s, linkedAt);
    }
}

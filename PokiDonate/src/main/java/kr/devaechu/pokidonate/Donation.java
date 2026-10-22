package kr.devaechu.pokidonate;

import java.util.UUID;

/** 장부 한 줄. 치지직 후원 이벤트에는 고유 번호가 없어서, 받는 순간 id(장부 번호)를 우리가 붙인다. */
public record Donation(
        long id,
        long receivedAt,          // epoch ms
        String channelId,         // 후원 받은 채널
        UUID streamerUuid,        // 연동된 마크 계정 (연동 안 된 채널이면 null)
        String streamerName,
        String donatorChannelId,
        String donatorNickname,
        long amount,              // 원
        String type,              // CHAT / VIDEO
        String message,
        Status status,
        boolean test,
        String note               // 대상 아님 사유 등 (없으면 null)
) {
    public enum Status {
        /** 기록됨, 아직 지급 전 */
        PENDING("대기"),
        /** 보상·이벤트 실행 완료 */
        PAID("지급됨"),
        /** 지급 중 오류 */
        FAILED("실패"),
        /** 보상 대상 아님 (스트리머가 마크 미접속, 미연동 채널, 최소 금액 미만 등) */
        SKIPPED("대상 아님"),
        /** 관리자가 직접 처리 */
        MANUAL("수동");

        public final String label;
        Status(String label) { this.label = label; }
    }
}

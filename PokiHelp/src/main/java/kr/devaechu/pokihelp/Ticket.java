package kr.devaechu.pokihelp;

import org.bukkit.Material;

import java.util.Locale;
import java.util.UUID;

/** 문의 한 건 */
final class Ticket {

    enum Kind {
        BUG("버그", "&c", Material.REDSTONE, 8301),
        REPORT("신고", "&6", Material.TNT, 8302),
        QUESTION("질문", "&b", Material.BOOK, 8303),
        OTHER("기타", "&7", Material.PAPER, 8304);

        final String label;
        final String color;
        final Material plain;
        final int model;

        Kind(String label, String color, Material plain, int model) {
            this.label = label;
            this.color = color;
            this.plain = plain;
            this.model = model;
        }

        String tag() {
            return color + "[" + label + "]";
        }

        /** "버그" / "bug" → BUG, 모르면 null */
        static Kind parse(String s) {
            if (s == null) return null;
            for (Kind k : values()) {
                if (k.label.equals(s) || k.name().equalsIgnoreCase(s)) return k;
            }
            return switch (s.toLowerCase(Locale.ROOT)) {
                case "오류", "버그신고" -> BUG;
                case "질문하기", "도움" -> QUESTION;
                default -> null;
            };
        }
    }

    final int id;
    final UUID uuid;
    final String name;
    final String nick;
    final Kind kind;
    final String message;
    final String world;
    final double x, y, z;
    final long created;
    boolean done;
    String doneBy = "";
    long doneAt;
    /** 처리 완료를 보낸 사람한테 알렸는지 (오프라인이면 다음 접속 때) */
    boolean notified = true;
    /** 신고 대상 마크닉 · 사유 (신고일 때만) */
    String target = "";
    String reason = "";
    /** 운영진 답변 */
    String reply = "";
    String replyBy = "";
    long replyAt;
    /** 보낸 사람이 포키폰에서 답변을 봤는지 */
    boolean replySeen = true;
    /** 담당 운영진 (확인 중) */
    String handler = "";

    /** 포키폰 고객센터용 상태: NEW 접수됨 · CHECKING 확인 중 · ANSWERED 답변 완료 · DONE 처리 완료 */
    String state() {
        if (!reply.isEmpty()) return "ANSWERED";
        if (done) return "DONE";
        return handler.isEmpty() ? "NEW" : "CHECKING";
    }

    Ticket(int id, UUID uuid, String name, String nick, Kind kind, String message,
           String world, double x, double y, double z, long created) {
        this.id = id;
        this.uuid = uuid;
        this.name = name;
        this.nick = nick;
        this.kind = kind;
        this.message = message;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.created = created;
    }

    /** 지니(Genie0424) — 한글닉이 마크닉과 같으면 하나만 */
    String who() {
        return nick == null || nick.isBlank() || nick.equals(name) ? name : nick + "&7(" + name + ")";
    }

    String where() {
        return world + " " + (int) Math.floor(x) + " " + (int) Math.floor(y) + " " + (int) Math.floor(z);
    }
}

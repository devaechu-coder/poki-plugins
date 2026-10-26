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

package kr.devaechu.pokihelp;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 포키폰 고객센터 앱이 부르는 입구 (모드 쪽에서 이름으로 찾아 부름 — 바꿀 땐 포키폰 SupportServer 도 같이).
 * 서버 메인 스레드에서만 부를 것. 문의 한 줄 = 탭(\t)으로 나눈 칸:
 * 번호, 종류(BUG/REPORT/QUESTION/OTHER), 상태(NEW/CHECKING/ANSWERED/DONE), 내용, 신고 대상, 사유,
 * 보낸 시각, 답변, 답변한 사람, 답변 시각, 담당자, 보낸 사람 표시, 위치
 */
public final class PokiHelpApi {
    private PokiHelpApi() {}

    private static PokiHelp plugin() { return PokiHelp.instance; }

    public static boolean available() { return plugin() != null; }

    public static boolean isStaff(UUID id) {
        Player p = Bukkit.getPlayer(id);
        return p != null && plugin() != null && plugin().isStaff(p);
    }

    /** 문의·신고 보내기. 성공 "OK:번호", 안 되면 "ERR:이유" */
    public static String submit(UUID id, String kind, String message, String target, String reason) {
        PokiHelp pl = plugin();
        Player p = Bukkit.getPlayer(id);
        if (pl == null || p == null) return "ERR:지금은 문의를 받을 수 없어요.";
        Ticket.Kind k = Ticket.Kind.parse(kind);
        if (k == null) k = Ticket.Kind.OTHER;
        Object r = pl.create(p, k, message, target, reason);
        return r instanceof Ticket t ? "OK:" + t.id : "ERR:" + r;
    }

    /** 내가 보낸 문의 (최신순 20개) */
    public static List<String> mine(UUID id) {
        List<String> out = new ArrayList<>();
        if (plugin() == null) return out;
        for (Ticket t : plugin().store().of(id)) {
            out.add(line(t));
            if (out.size() >= 20) break;
        }
        return out;
    }

    /** 아직 안 본 답변 수 */
    public static int unseen(UUID id) {
        if (plugin() == null) return 0;
        int n = 0;
        for (Ticket t : plugin().store().of(id)) if (!t.reply.isEmpty() && !t.replySeen) n++;
        return n;
    }

    public static void markSeen(UUID id) {
        if (plugin() == null) return;
        boolean changed = false;
        for (Ticket t : plugin().store().of(id)) if (!t.reply.isEmpty() && !t.replySeen) { t.replySeen = true; t.notified = true; changed = true; }
        if (changed) plugin().store().save();
    }

    /** 운영진 문의함: done=false 미처리 / true 처리됨 (최신순 50개). 운영진 아니면 빈 목록 */
    public static List<String> box(UUID staff, boolean done) {
        List<String> out = new ArrayList<>();
        if (!isStaff(staff)) return out;
        for (Ticket t : plugin().store().list(done, null)) {
            out.add(line(t));
            if (out.size() >= 50) break;
        }
        return out;
    }

    public static int openCount(UUID staff) {
        return isStaff(staff) ? (int) plugin().store().openCount() : 0;
    }

    /** 운영진 처리: claim 담당 · reply 답변 · resolve 처리 완료 · reopen 되돌리기 · tp 이동. 성공 null, 안 되면 이유 */
    public static String action(UUID staff, String act, int ticket, String text) {
        PokiHelp pl = plugin();
        Player p = Bukkit.getPlayer(staff);
        if (pl == null || p == null || !pl.isStaff(p)) return "운영진만 할 수 있어요.";
        Ticket t = pl.store().get(ticket);
        if (t == null) return "없는 문의예요.";
        switch (act) {
            case "claim" -> pl.claim(p, t);
            case "reply" -> { return pl.reply(p, t, text); }
            case "resolve" -> pl.resolve(p, t);
            case "reopen" -> pl.reopen(p, t);
            case "tp" -> pl.teleport(p, t);
            default -> { return "모르는 동작이에요."; }
        }
        return null;
    }

    private static String c(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ');
    }

    private static String line(Ticket t) {
        return String.join("\t", String.valueOf(t.id), t.kind.name(), t.state(), c(t.message), c(t.target), c(t.reason),
                String.valueOf(t.created), c(t.reply), c(t.replyBy), String.valueOf(t.replyAt), c(t.handler),
                c(PokiHelp.clean(PokiHelp.color(t.who()))), c(t.where()));
    }
}

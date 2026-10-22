package kr.devaechu.pokidonate;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import static kr.devaechu.pokidonate.PokiDonate.color;
import static kr.devaechu.pokidonate.PokiDonate.nick;
import static kr.devaechu.pokidonate.PokiDonate.plain;
import static kr.devaechu.pokidonate.PokiDonate.won;

/**
 * /후원 (= /donate)
 *   연동 · 해제 · 기록                    입주민·운영진 (poki.donate.streamer)
 *   기록 <이름|전체> · 현황 · 강제해제 · 테스트 · 리로드   관리자 (poki.donate.admin)
 */
final class DonateCommand implements TabExecutor {

    private static final String P_STREAMER = "poki.donate.streamer";
    private static final String P_ADMIN = "poki.donate.admin";

    private final PokiDonate plugin;

    DonateCommand(PokiDonate plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        String[] rest = a.length == 0 ? a : Arrays.copyOfRange(a, 1, a.length);
        switch (sub) {
            case "연동", "link" -> link(s);
            case "해제", "unlink" -> unlink(s, rest);
            case "기록", "log" -> log(s, rest);
            case "현황", "status" -> { if (admin(s)) status(s); }
            case "강제해제", "forceunlink" -> { if (admin(s)) forceUnlink(s, rest); }
            case "테스트", "test" -> { if (admin(s)) test(s, rest); }
            case "미지급", "unpaid" -> { if (admin(s)) unpaid(s, rest); }
            case "재지급", "repay" -> { if (admin(s)) repay(s, rest); }
            case "보상표", "rewards" -> rewards(s);
            case "리로드", "reload" -> {
                if (admin(s)) {
                    plugin.reload();
                    msg(s, plugin.configured() ? "&a설정을 다시 불러오고 치지직 연결을 다시 시작했어요." : "&e설정을 불러왔지만 비밀값이 비어 있어서 아직 시작 안 했어요.");
                }
            }
            default -> help(s);
        }
        return true;
    }

    // ── 입주민·운영진 ───────────────────────────────────

    private void link(CommandSender s) {
        if (!(s instanceof Player p)) { msg(s, "&c게임 안에서만 쓸 수 있어요."); return; }
        if (!p.hasPermission(P_STREAMER)) { msg(s, "&c입주민·운영진만 연동할 수 있어요."); return; }
        if (!plugin.configured() || plugin.linkFlow() == null) { msg(s, "&e후원 연동이 아직 준비 중이에요. 운영진에게 문의해 주세요."); return; }
        Link l = plugin.links().get(p.getUniqueId());
        if (l != null && l.status() == Link.Status.ACTIVE) {
            msg(s, "&f이미 치지직 &e" + plain(l.channelName()) + " &f채널과 연동돼 있어요. 바꾸려면 &e/후원 해제 &f먼저 해주세요.");
            return;
        }
        String url = plugin.linkFlow().begin(p.getUniqueId(), p.getName());
        int min = Math.max(1, plugin.getConfig().getInt("link-expire-minutes", 10));
        sendLink(p, color(PokiDonate.PREFIX + "&f아래 버튼을 눌러 치지직에서 &e권한 허용&f을 해주세요. &7(" + min + "분 안에)"),
                color("&a&n▶ 치지직 연동하기"), url, "치지직 로그인 화면으로 이동해요");
        msg(s, "&7허용하고 나면 몇 초 안에 여기 채팅으로 완료 알림이 와요.");
    }

    private void unlink(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { msg(s, "&c게임 안에서만 쓸 수 있어요. 관리자는 /후원 강제해제 <이름>"); return; }
        Link l = plugin.links().get(p.getUniqueId());
        if (l == null) { msg(s, "&7연동된 채널이 없어요."); return; }
        if (a.length == 0 || !a[0].equals("확인")) {
            msg(s, "&f치지직 &e" + plain(l.channelName()) + " &f연동을 해제할까요? 해제하면 후원이 더 이상 기록되지 않아요.");
            msg(s, "&f해제하려면 &c/후원 해제 확인 &f을 입력해 주세요. &7(지난 기록은 남아요)");
            return;
        }
        plugin.unlink(l);
        msg(s, "&a연동을 해제했어요.");
    }

    /** /후원 기록 [이름|전체] [페이지] */
    private void log(CommandSender s, String[] a) {
        boolean isAdmin = s.hasPermission(P_ADMIN);
        if (!isAdmin && !s.hasPermission(P_STREAMER)) { msg(s, "&c입주민·운영진만 볼 수 있어요."); return; }

        int page = 1;
        String target = null;
        for (String x : a) {
            if (x.matches("\\d+")) page = Math.max(1, Integer.parseInt(x));
            else target = x;
        }

        String channelId;
        String title;
        if (isAdmin && (target == null || target.equals("전체") || target.equalsIgnoreCase("all"))) {
            channelId = null;
            title = "전체";
        } else if (isAdmin) {
            Link l = plugin.links().byName(target);
            if (l == null) { msg(s, "&c'" + target + "' 으로 연동된 스트리머가 없어요."); return; }
            channelId = l.channelId();
            title = l.playerName();
        } else {
            if (!(s instanceof Player p)) return;
            Link l = plugin.links().get(p.getUniqueId());
            if (l == null) { msg(s, "&7연동된 채널이 없어요. &e/후원 연동 &7먼저 해주세요."); return; }
            channelId = l.channelId();
            title = l.playerName();
        }

        int size = Math.max(1, plugin.getConfig().getInt("record-page-size", 10));
        int pg = page;
        boolean all = channelId == null;
        plugin.db().countDonations(channelId).thenCombine(plugin.db().donations(channelId, size, (pg - 1) * size), (total, rows) -> {
            List<String> out = new ArrayList<>();
            int pages = Math.max(1, (total + size - 1) / size);
            out.add("&7── 후원 기록 &f" + title + " &7(" + pg + "/" + pages + " · 총 " + total + "건) ──");
            if (rows.isEmpty()) out.add("&7기록이 없어요.");
            SimpleDateFormat f = new SimpleDateFormat("MM/dd HH:mm");
            for (Donation d : rows) {
                StringBuilder b = new StringBuilder("&8#").append(d.id()).append(" &7").append(f.format(new Date(d.receivedAt())))
                        .append(" &e").append(nick(d.donatorNickname())).append(" &a").append(won(d.amount()));
                if ("VIDEO".equals(d.type())) b.append(" &d[영상]");
                b.append(" ").append(statusColor(d.status())).append("[").append(d.status().label)
                        .append(d.note() == null ? "" : " · " + d.note()).append("]");
                if (d.test()) b.append(" &8(테스트)");
                if (all) b.append(" &8→ &f").append(d.streamerName() == null ? "미연동 채널" : plain(d.streamerName()));
                out.add(b.toString());
                String m = d.message() == null ? "" : d.message().strip();
                if (!m.isEmpty()) out.add("&8   └ &7" + plain(m.length() > 40 ? m.substring(0, 40) + "…" : m));
            }
            if (pg < pages) out.add("&7다음 페이지: &e/후원 기록 " + (all ? "전체 " : isAdmin ? title + " " : "") + (pg + 1));
            return out;
        }).whenComplete((lines, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null) { msg(s, "&c기록을 불러오지 못했어요: " + err.getMessage()); return; }
            for (String line : lines) s.sendMessage(color(line));
        }));
    }

    // ── 관리자 ──────────────────────────────────────────

    private void status(CommandSender s) {
        List<Link> all = plugin.links().all();
        msg(s, "&7── 치지직 연동 현황 (" + all.size() + "명) ──");
        if (!plugin.configured()) msg(s, "&cconfig.yml 비밀값이 비어 있어서 연결 안 함");
        SessionManager sm = plugin.sessions();
        if (sm != null) for (String line : sm.summary()) s.sendMessage(color("&8" + line));
        long now = System.currentTimeMillis();
        for (Link l : all) {
            String state = l.status() == Link.Status.RELINK ? "&c재연동 필요"
                    : sm == null ? "&7꺼짐" : switch (sm.state(l.channelId())) {
                        case "받는 중" -> "&a받는 중";
                        case "꺼짐" -> "&7꺼짐";
                        default -> "&e" + sm.state(l.channelId());
                    };
            long hours = (l.expiresAt() - now) / 3_600_000L;
            s.sendMessage(color("&f" + plain(l.playerName()) + " &8↔ &e" + plain(l.channelName()) + " &8· " + state
                    + " &8· 토큰 " + (hours >= 0 ? hours + "시간 남음" : "만료")));
        }
    }

    private void forceUnlink(CommandSender s, String[] a) {
        if (a.length == 0) { msg(s, "&e/후원 강제해제 <마크닉 또는 채널명>"); return; }
        Link l = plugin.links().byName(String.join(" ", a));
        if (l == null) { msg(s, "&c그 이름으로 연동된 스트리머가 없어요."); return; }
        plugin.unlink(l);
        msg(s, "&a" + plain(l.playerName()) + " (" + plain(l.channelName()) + ") 연동을 해제했어요.");
        plugin.tell(l.uuid(), "&e관리자가 치지직 연동을 해제했어요.");
    }

    /** /후원 테스트 <이름> <금액> [메시지] — 진짜 후원처럼 장부에 들어간다 (테스트 표시) */
    private void test(CommandSender s, String[] a) {
        if (a.length < 2) { msg(s, "&e/후원 테스트 <마크닉 또는 채널명> <금액> [메시지]"); return; }
        Link l = plugin.links().byName(a[0]);
        if (l == null) { msg(s, "&c'" + a[0] + "' 으로 연동된 스트리머가 없어요."); return; }
        long amount;
        try { amount = Long.parseLong(a[1].replace(",", "")); } catch (NumberFormatException e) { msg(s, "&c금액은 숫자로 적어주세요."); return; }
        String text = a.length > 2 ? String.join(" ", Arrays.copyOfRange(a, 2, a.length)) : "테스트 후원";
        String donor = s instanceof Player p ? p.getName() + " (테스트)" : "콘솔 (테스트)";
        if (!plugin.isOnline(l.uuid())) {
            msg(s, "&e" + plain(l.playerName()) + " 님이 마크에 없어서 기록하지 않았어요. &7(실제 후원도 접속 중일 때만 기록돼요)");
            return;
        }
        plugin.record(plugin.donation(l.channelId(), l, "test", donor, amount, "CHAT", text, true));
        msg(s, "&a" + plain(l.playerName()) + " 에게 " + won(amount) + " 테스트 후원을 기록했어요. &7(보상표대로 바로 지급)");
    }

    /** /후원 미지급 [페이지] — 대기·실패 */
    private void unpaid(CommandSender s, String[] a) {
        int page = a.length > 0 && a[0].matches("\\d+") ? Math.max(1, Integer.parseInt(a[0])) : 1;
        int size = Math.max(1, plugin.getConfig().getInt("record-page-size", 10));
        plugin.db().countUnpaid().thenCombine(plugin.db().unpaid(size, (page - 1) * size), (total, rows) -> {
            List<String> out = new ArrayList<>();
            int pages = Math.max(1, (total + size - 1) / size);
            out.add("&7── 미지급 보상 (" + page + "/" + pages + " · " + total + "건) ──");
            if (rows.isEmpty()) out.add("&a밀린 보상이 없어요.");
            SimpleDateFormat f = new SimpleDateFormat("MM/dd HH:mm");
            for (Donation d : rows) {
                out.add("&8#" + d.id() + " &7" + f.format(new Date(d.receivedAt())) + " &f" + plain(d.streamerName())
                        + " &8← &e" + nick(d.donatorNickname()) + " &a" + won(d.amount()) + " " + statusColor(d.status())
                        + "[" + d.status().label + (d.note() == null ? "" : " · " + plain(d.note())) + "]");
            }
            if (!rows.isEmpty()) out.add("&7다시 지급: &e/후원 재지급 <번호> &8(대기는 스트리머가 접속하면 자동 지급)");
            if (page < pages) out.add("&7다음 페이지: &e/후원 미지급 " + (page + 1));
            return out;
        }).whenComplete((lines, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null) { msg(s, "&c불러오지 못했어요: " + err.getMessage()); return; }
            for (String line : lines) s.sendMessage(color(line));
        }));
    }

    /** /후원 재지급 <번호> */
    private void repay(CommandSender s, String[] a) {
        if (a.length == 0 || !a[0].replace("#", "").matches("\\d+")) { msg(s, "&e/후원 재지급 <장부 번호>"); return; }
        long id = Long.parseLong(a[0].replace("#", ""));
        plugin.db().donation(id).whenComplete((d, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null || d == null) { msg(s, "&c#" + id + " 후원을 찾지 못했어요."); return; }
            if (d.status() == Donation.Status.PAID || d.status() == Donation.Status.MANUAL) {
                msg(s, "&e#" + id + " 은 이미 " + d.status().label + " 상태예요.");
                return;
            }
            if (d.streamerUuid() == null) { msg(s, "&c#" + id + " 은 연동된 스트리머가 없는 후원이에요."); return; }
            msg(s, "&f#" + id + " &7→ &f" + plugin.repay(d));
        }));
    }

    /** /후원 보상표 */
    private void rewards(CommandSender s) {
        Rewards r = plugin.rewards();
        if (r == null) { msg(s, "&7보상표가 아직 없어요."); return; }
        msg(s, "&7── 후원 보상표 &8(방송 중 마크 접속해 있을 때만) &7──");
        s.sendMessage(color("&b입주민"));
        tiers(s, r.resident);
        s.sendMessage(color("&d운영진"));
        tiers(s, r.staff);
    }

    private static void tiers(CommandSender s, List<Rewards.Tier> list) {
        if (list.isEmpty()) { s.sendMessage(color("&8  아직 없음")); return; }
        for (Rewards.Tier t : list) {
            String how = t.per() > 0 ? won(t.per()) + "당 1개 (최대 " + t.maxCount() + "개)" : "1번";
            s.sendMessage(color("&7  " + won(t.min()) + " 이상 &8· &f" + how));
        }
    }

    // ── 공통 ────────────────────────────────────────────

    private void help(CommandSender s) {
        msg(s, "&7── 치지직 후원 연동 ──");
        if (s.hasPermission(P_STREAMER)) {
            line(s, "/후원 연동", "내 치지직 채널 연결");
            line(s, "/후원 기록 [페이지]", "내 방송 후원 기록");
            line(s, "/후원 해제", "연동 해제");
            line(s, "/후원 보상표", "금액별 보상 보기");
        }
        if (s.hasPermission(P_ADMIN)) {
            line(s, "/후원 기록 <이름|전체> [페이지]", "스트리머별·전체 기록");
            line(s, "/후원 현황", "연동·연결 상태");
            line(s, "/후원 강제해제 <이름>", "연동 강제 해제");
            line(s, "/후원 테스트 <이름> <금액> [메시지]", "가짜 후원 기록·지급");
            line(s, "/후원 미지급 [페이지]", "대기·실패한 보상 모아 보기");
            line(s, "/후원 재지급 <번호>", "그 후원 보상 다시 지급");
            line(s, "/후원 리로드", "config.yml · rewards.yml 다시 읽고 재연결");
        }
        if (!s.hasPermission(P_STREAMER) && !s.hasPermission(P_ADMIN)) {
            msg(s, "&7입주민·운영진 스트리머가 방송 후원을 서버와 연결하는 기능이에요.");
        }
    }

    private static void line(CommandSender s, String c, String d) {
        s.sendMessage(color("&e" + c + " &7- " + d));
    }

    private static boolean admin(CommandSender s) {
        if (s.hasPermission(P_ADMIN)) return true;
        msg(s, "&c관리자만 쓸 수 있어요.");
        return false;
    }

    private static void msg(CommandSender s, String m) {
        s.sendMessage(color(PokiDonate.PREFIX + m));
    }

    private static String statusColor(Donation.Status st) {
        return switch (st) {
            case PENDING -> "&e";
            case PAID -> "&a";
            case FAILED -> "&c";
            case SKIPPED -> "&8";
            case MANUAL -> "&b";
        };
    }

    /** 클릭 링크 — PokiLinks 처럼 바닐라 /tellraw 로 보낸다 (아크라이트에서도 동작) */
    private static void sendLink(Player p, String text, String button, String url, String hover) {
        String json = "[\"\",{\"text\":" + q(text + "\n") + "},"
                + "{\"text\":" + q(button) + ",\"clickEvent\":{\"action\":\"open_url\",\"value\":" + q(url) + "},"
                + "\"hoverEvent\":{\"action\":\"show_text\",\"contents\":" + q(hover) + "}}]";
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "minecraft:tellraw " + p.getName() + " " + json);
    }

    private static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> { }
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            if (s.hasPermission(P_STREAMER)) out.addAll(List.of("연동", "해제", "기록", "보상표"));
            if (s.hasPermission(P_ADMIN)) out.addAll(List.of("기록", "현황", "미지급", "재지급", "강제해제", "테스트", "리로드"));
        } else if (a.length == 2 && s.hasPermission(P_ADMIN)
                && List.of("기록", "log", "강제해제", "forceunlink", "테스트", "test").contains(a[0].toLowerCase(Locale.ROOT))) {
            if (a[0].equals("기록") || a[0].equalsIgnoreCase("log")) out.add("전체");
            for (Link l : plugin.links().all()) out.add(l.playerName());
        } else if (a.length == 2 && (a[0].equals("해제") || a[0].equalsIgnoreCase("unlink"))) {
            out.add("확인");
        }
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        return out.stream().distinct().filter(x -> x.toLowerCase(Locale.ROOT).startsWith(last)).toList();
    }
}

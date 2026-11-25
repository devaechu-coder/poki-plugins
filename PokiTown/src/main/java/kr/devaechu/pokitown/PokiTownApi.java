package kr.devaechu.pokitown;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.TownySettings;
import com.palmergames.bukkit.towny.TownyUniverse;
import com.palmergames.bukkit.towny.confirmations.ConfirmationHandler;
import com.palmergames.bukkit.towny.invites.Invite;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 다른 플러그인·포키폰 서버가 부르는 API. 리플렉션으로 부르기 쉽게 JDK 타입(UUID·String·List·Map)만 씀.
 * 메인 스레드에서 부르기. 줄 목록은 탭(\t)으로 나눈 칸.
 *
 *  townOf(플레이어)            → {uuid, name, display, color, role, custom(운영진이 정한 이름이면 1)} (마을 없으면 null) — 알바는 마을 없음 취급
 *  me(나)                      → "ME 마을UUID 역할 권한들 목록공개 OP" + 받은 초대 "INV 마을UUID 이름 표시 보낸사람 이장 주민수"
 *  towns(나)                   → "T ... 주민이름들(OP만)" 마을 목록 (유저는 목록공개가 꺼져 있으면 빈 목록)
 *  town(나, 마을UUID)          → "T ... 스폰있음" + 주민 "R ..." + 알바 "A ..."
 *  act(나, 행동, 대상)         → "OK" / "ERR:이유" (spawn_town 은 OP 만) — 그 사람이 Towny 명령어를 친 것처럼 (본인 권한, /confirm 자동)
 *  onChange(리스너)            → 접속 중인 사람의 마을 상태가 바뀌면 그 사람 UUID
 *  onNotify(리스너)            → 포키폰 알림 (받을 사람 UUID, 문구)
 */
public final class PokiTownApi {
    private PokiTownApi() {}

    private static final Pattern PLAYER = Pattern.compile("^[A-Za-z0-9_.]{1,20}$");
    private static final Pattern TOWN = Pattern.compile("^[\\p{L}\\p{N}_\\-]{1,40}$");

    private static PokiTown p() {
        PokiTown p = PokiTown.inst;
        if (p == null) throw new IllegalStateException("PokiTown 꺼져 있음");
        return p;
    }

    // ───────── 리스너 ─────────

    public static void onChange(Consumer<UUID> listener) { p().changeListeners.add(listener); }

    public static void onNotify(BiConsumer<UUID, String> listener) { p().notifyListeners.add(listener); }

    // ───────── 이름표용 ─────────

    /** 주민만 (알바는 null). 표시 이름은 운영진 지정 > 욕설 필터 > Towny 이름 */
    public static Map<String, String> townOf(UUID player) {
        Resident r = TownyAPI.getInstance().getResident(player);
        Town t = r == null ? null : r.getTownOrNull();
        if (t == null) return null;
        Map<String, String> m = new LinkedHashMap<>();
        m.put("uuid", t.getUUID().toString());
        m.put("name", t.getName());
        m.put("display", p().display(t));
        m.put("color", p().color(t));
        m.put("role", PokiTown.role(t, r));
        m.put("custom", p().customDisplay(t) ? "1" : "0");
        return m;
    }

    public static boolean listPublic() { return p().listPublic(); }

    /** OP 만. 결과 "OK" / "ERR:이유" */
    public static String setListPublic(UUID by, boolean on) {
        Player pl = Bukkit.getPlayer(by);
        if (pl == null || !pl.isOp()) return "ERR:운영진만 바꿀 수 있어요";
        p().setListPublic(on, pl.getName());
        return "OK";
    }

    // ───────── 포키폰 마을 앱 ─────────

    public static List<String> me(UUID viewer) {
        List<String> out = new ArrayList<>();
        Player pl = Bukkit.getPlayer(viewer);
        boolean op = pl != null && pl.isOp();
        Resident r = TownyAPI.getInstance().getResident(viewer);
        Town t = r == null ? null : r.getTownOrNull();
        List<String> perms = new ArrayList<>();
        if (t != null && pl != null) {
            var src = TownyUniverse.getInstance().getPermissionSource();
            if (src.testPermission(pl, "towny.command.town.invite.add")) perms.add("invite");
            if (src.testPermission(pl, "towny.command.town.kick")) perms.add("kick");
            if (src.testPermission(pl, "towny.command.town.trust")) perms.add("trust");
            if (src.testPermission(pl, "towny.command.town.rank.assistant")) perms.add("rank");
            if (src.testPermission(pl, "towny.command.town.leave")) perms.add("leave");
            if (src.testPermission(pl, "towny.town.spawn.town")) perms.add("spawn");
        }
        out.add(row("ME", t == null ? "" : t.getUUID().toString(), PokiTown.role(t, r), String.join(",", perms),
                p().listPublic() ? "1" : "0", op ? "1" : "0"));
        if (r != null) {
            int n = 0;
            for (Invite inv : r.getReceivedInvites()) {
                if (!(inv.getSender() instanceof Town it)) continue;
                Resident mayor = it.getMayor();
                out.add(row("INV", it.getUUID().toString(), it.getName(), p().display(it), inv.getSenderName(),
                        mayor == null ? "" : mayor.getName(), String.valueOf(it.getNumResidents())));
                if (++n >= 20) break;
            }
        }
        return out;
    }

    public static List<String> towns(UUID viewer) {
        List<String> out = new ArrayList<>();
        boolean op = isOp(viewer);
        if (!op && !p().listPublic()) return out;
        Town mine = myTown(viewer);
        List<Town> all = new ArrayList<>(TownyAPI.getInstance().getTowns());
        all.sort(Comparator.comparingInt(Town::getNumResidents).reversed().thenComparing(Town::getName));
        for (Town t : all) {
            // 운영진은 주민 이름으로도 찾을 수 있게 맨 끝 칸에 주민·알바 마크닉
            String names = "";
            if (op) {
                List<String> ns = new ArrayList<>();
                for (Resident r : t.getResidents()) ns.add(r.getName());
                for (Resident r : t.getTrustedResidents()) ns.add(r.getName());
                names = String.join(",", ns);
            }
            out.add(townRow(t, op || t == mine) + "\t" + names.replace('\t', ' '));
            if (out.size() >= 400) break;
        }
        return out;
    }

    public static List<String> town(UUID viewer, String townId) {
        List<String> out = new ArrayList<>();
        Town t;
        try { t = TownyAPI.getInstance().getTown(UUID.fromString(townId)); }
        catch (IllegalArgumentException e) { return out; }
        if (t == null) return out;
        boolean op = isOp(viewer);
        Town mine = myTown(viewer);
        boolean own = t == mine;
        if (!own && !op && !p().listPublic()) return out;
        out.add(townRow(t, op || own) + "\t" + (t.getSpawnOrNull() != null ? "1" : "0"));
        List<Resident> rs = new ArrayList<>(t.getResidents());
        rs.sort(Comparator.comparingInt((Resident r) -> order(t, r)).thenComparing(Resident::getName, String.CASE_INSENSITIVE_ORDER));
        for (Resident r : rs) out.add(person("R", t, r, own || op, op));
        List<Resident> tr = new ArrayList<>(t.getTrustedResidents());
        tr.sort(Comparator.comparing(Resident::getName, String.CASE_INSENSITIVE_ORDER));
        for (Resident r : tr) out.add(person("A", t, r, own || op, op));
        return out;
    }

    /**
     * 그 사람이 Towny 명령어를 친 것처럼 실행 (본인 권한 — OP 로 실행하지 않음). 비용 확인(/confirm)은 앱에서 이미 물어봤으니 자동 확정.
     * 결과 메시지는 Towny 가 채팅으로 보냄. 앱은 잠깐 뒤 상태를 다시 읽어서 됐는지 봄.
     */
    public static String act(UUID viewer, String action, String arg) {
        Player pl = Bukkit.getPlayer(viewer);
        if (pl == null) return "ERR:접속 중이 아니에요";
        String a = arg == null ? "" : arg.trim();
        String cmd;
        switch (action) {
            case "invite", "kick", "trust_add", "trust_remove", "assistant_add", "assistant_remove" -> {
                if (!PLAYER.matcher(a).matches()) return "ERR:마크닉을 확인해 주세요";
                cmd = switch (action) {
                    case "invite" -> "towny:town invite " + a;
                    case "kick" -> "towny:town kick " + a;
                    case "trust_add" -> "towny:town trust add " + a;
                    case "trust_remove" -> "towny:town trust remove " + a;
                    case "assistant_add" -> "towny:town rank add " + a + " assistant";
                    default -> "towny:town rank remove " + a + " assistant";
                };
            }
            case "accept", "deny" -> {
                if (!TOWN.matcher(a).matches()) return "ERR:마을 이름을 확인해 주세요";
                cmd = "towny:invite " + (action.equals("accept") ? TownySettings.getAcceptCommand() : TownySettings.getDenyCommand()) + " " + a;
            }
            case "spawn_town" -> {
                // 운영진 전용: 아무 마을 스폰으로 (Towny 관리자라 공개 여부·비용 무시)
                if (!pl.isOp()) return "ERR:운영진만 다른 마을 스폰으로 갈 수 있어요";
                if (!TOWN.matcher(a).matches()) return "ERR:마을 이름을 확인해 주세요";
                cmd = "towny:town spawn " + a;
            }
            case "leave" -> cmd = "towny:town leave";
            case "spawn" -> cmd = "towny:town spawn";
            default -> { return "ERR:모르는 행동: " + action; }
        }
        boolean hadConfirm = ConfirmationHandler.hasConfirmation(pl);
        boolean ok;
        try { ok = Bukkit.dispatchCommand(pl, cmd); }
        catch (Throwable t) {
            p().getLogger().warning("[마을앱] " + pl.getName() + " /" + cmd + " 실패: " + t);
            return "ERR:처리 중 오류가 났어요";
        }
        // 앱에서 확인을 이미 받았으니 이번 명령어로 새로 생긴 확인창은 바로 확정 (원래 떠 있던 건 건드리지 않음)
        if (!hadConfirm && ConfirmationHandler.hasConfirmation(pl)) {
            try { ConfirmationHandler.acceptConfirmation(pl); }
            catch (Throwable t) { p().getLogger().warning("[마을앱] 자동 확정 실패: " + t); }
        }
        p().getLogger().info("[마을앱] " + pl.getName() + " → /" + cmd);
        p().queueRecheck();
        return ok ? "OK" : "ERR:명령어를 실행하지 못했어요";
    }

    // ───────── 도구 ─────────

    private static boolean isOp(UUID id) {
        Player pl = Bukkit.getPlayer(id);
        return pl != null && pl.isOp();
    }

    private static Town myTown(UUID id) {
        Resident r = TownyAPI.getInstance().getResident(id);
        return r == null ? null : r.getTownOrNull();
    }

    private static int order(Town t, Resident r) {
        if (t.isMayor(r)) return 0;
        if (r.hasTownRank("assistant")) return 1;
        return 2;
    }

    /** T 마을UUID Towny이름 표시이름 색 이장UUID 이장 주민수 알바수 청크 잔액(안 보이면 -) 폐허 */
    private static String townRow(Town t, boolean showMoney) {
        Resident mayor = t.getMayor();
        String bal = "-";
        if (showMoney) {
            try { bal = String.valueOf((long) Math.floor(t.getAccount().getCachedBalance())); }
            catch (Throwable ignored) { bal = "-"; }
        }
        return row("T", t.getUUID().toString(), t.getName(), p().display(t), p().color(t),
                mayor == null ? "" : mayor.getUUID().toString(), mayor == null ? "" : mayor.getName(),
                String.valueOf(t.getNumResidents()), String.valueOf(t.getTrustedResidents().size()),
                String.valueOf(t.getNumTownBlocks()), bal, t.isRuined() ? "1" : "0");
    }

    /** R/A 사람UUID 마크닉 역할 접속(1/0, 안 보이면 -) 마지막접속(ms, 안 보이면 0) */
    private static String person(String kind, Town t, Resident r, boolean showOnline, boolean showLast) {
        UUID id = r.getUUID();
        OfflinePlayer op = id == null ? null : Bukkit.getOfflinePlayer(id);
        boolean online = id != null && Bukkit.getPlayer(id) != null;
        String role = kind.equals("A") ? "trusted" : PokiTown.role(t, r);
        return row(kind, id == null ? "" : id.toString(), r.getName(), role,
                showOnline ? (online ? "1" : "0") : "-",
                showLast ? String.valueOf(online ? System.currentTimeMillis() : Math.max(r.getLastOnline(), op == null ? 0 : op.getLastPlayed())) : "0");
    }

    private static String row(String... cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append('\t');
            String c = cells[i] == null ? "" : cells[i];
            sb.append(c.replace('\t', ' ').replace('\n', ' '));
        }
        return sb.toString();
    }
}

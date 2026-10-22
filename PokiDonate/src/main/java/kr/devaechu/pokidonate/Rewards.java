package kr.devaechu.pokidonate;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/** rewards.yml — 입주민 보상표 / 운영진 보상표 */
final class Rewards {

    record Tier(long min, String name, long per, int maxCount, String message, List<String> commands) {
        /** 이 금액이면 몇 개 */
        int count(long amount) {
            if (per <= 0) return 1;
            return (int) Math.max(1, Math.min(maxCount, amount / per));
        }
    }

    final List<Tier> resident;
    final List<Tier> staff;
    /** true: 메뉴 금액과 딱 맞을 때만 / false: 그 금액 이상 중 가장 높은 칸 */
    final boolean exact;
    /** 즉사(@wipe-and-kill) 때 남길 아이템 */
    final List<String> keepItems;

    private Rewards(List<Tier> resident, List<Tier> staff, boolean exact, List<String> keepItems) {
        this.resident = resident;
        this.staff = staff;
        this.exact = exact;
        this.keepItems = keepItems;
    }

    static Rewards load(File file, Logger log) {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        boolean exact = !"at-least".equalsIgnoreCase(String.valueOf(y.getString("match", "exact")).trim());
        List<String> keep = new ArrayList<>();
        for (String k : y.getStringList("keep-items")) if (k != null && !k.isBlank()) keep.add(k.trim().toLowerCase(java.util.Locale.ROOT));
        return new Rewards(tiers(y.getMapList("resident"), "resident", log), tiers(y.getMapList("staff"), "staff", log), exact, List.copyOf(keep));
    }

    /** exact: min 과 같은 칸 / at-least: min 이상 중 가장 높은 칸. 없으면 null */
    Tier pick(List<Tier> table, long amount) {
        Tier best = null;
        for (Tier t : table) {
            if (exact ? amount == t.min() : amount >= t.min()) {
                if (best == null || t.min() > best.min()) best = t;
            }
        }
        return best;
    }

    /** 아이템 id(예: cobblemon:pokedex_red) 또는 cmd:1001(커스텀 모델) 이 남길 목록에 있는지. * 사용 가능 */
    boolean keeps(String itemId, Integer customModelData) {
        for (String k : keepItems) {
            if (k.startsWith("cmd:")) {
                if (customModelData != null && k.substring(4).equals(String.valueOf(customModelData))) return true;
            } else if (glob(k, itemId.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    static boolean glob(String pattern, String s) {
        StringBuilder re = new StringBuilder();
        for (char c : pattern.toCharArray()) re.append(c == '*' ? ".*" : java.util.regex.Pattern.quote(String.valueOf(c)));
        return s.matches(re.toString());
    }

    private static List<Tier> tiers(List<Map<?, ?>> raw, String section, Logger log) {
        List<Tier> out = new ArrayList<>();
        for (Map<?, ?> m : raw) {
            long min = num(m.get("amount"), num(m.get("min"), -1)); // amount: 메뉴 금액 (min 도 같은 뜻으로 받음)
            if (min < 0) {
                log.warning("[PokiDonate] rewards.yml " + section + ": amount 가 없는 칸은 건너뜀");
                continue;
            }
            List<String> cmds = new ArrayList<>();
            if (m.get("commands") instanceof List<?> l) {
                for (Object o : l) if (o != null && !o.toString().isBlank()) cmds.add(o.toString().strip());
            }
            Object msg = m.get("message");
            Object name = m.get("name");
            out.add(new Tier(min, name == null ? "" : name.toString(), num(m.get("per"), 0), (int) Math.max(1, num(m.get("max-count"), 64)),
                    msg == null ? "" : msg.toString(), List.copyOf(cmds)));
        }
        out.sort(Comparator.comparingLong(Tier::min));
        return List.copyOf(out);
    }

    private static long num(Object o, long def) {
        if (o instanceof Number n) return n.longValue();
        if (o == null) return def;
        try { return Long.parseLong(o.toString().replace(",", "").trim()); } catch (NumberFormatException e) { return def; }
    }
}

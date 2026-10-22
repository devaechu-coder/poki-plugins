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

    record Tier(long min, long per, int maxCount, String message, List<String> commands) {
        /** 이 금액이면 몇 개 */
        int count(long amount) {
            if (per <= 0) return 1;
            return (int) Math.max(1, Math.min(maxCount, amount / per));
        }
    }

    final List<Tier> resident;
    final List<Tier> staff;

    private Rewards(List<Tier> resident, List<Tier> staff) {
        this.resident = resident;
        this.staff = staff;
    }

    static Rewards load(File file, Logger log) {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        return new Rewards(tiers(y.getMapList("resident"), "resident", log), tiers(y.getMapList("staff"), "staff", log));
    }

    /** 금액이 min 이상인 구간 중 가장 높은 것. 없으면 null */
    static Tier pick(List<Tier> table, long amount) {
        Tier best = null;
        for (Tier t : table) if (amount >= t.min() && (best == null || t.min() > best.min())) best = t;
        return best;
    }

    private static List<Tier> tiers(List<Map<?, ?>> raw, String section, Logger log) {
        List<Tier> out = new ArrayList<>();
        for (Map<?, ?> m : raw) {
            long min = num(m.get("min"), -1);
            if (min < 0) {
                log.warning("[PokiDonate] rewards.yml " + section + ": min 이 없는 구간은 건너뜀");
                continue;
            }
            List<String> cmds = new ArrayList<>();
            if (m.get("commands") instanceof List<?> l) {
                for (Object o : l) if (o != null && !o.toString().isBlank()) cmds.add(o.toString().strip());
            }
            Object msg = m.get("message");
            out.add(new Tier(min, num(m.get("per"), 0), (int) Math.max(1, num(m.get("max-count"), 64)),
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

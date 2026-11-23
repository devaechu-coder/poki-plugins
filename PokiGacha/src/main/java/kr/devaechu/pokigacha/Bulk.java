package kr.devaechu.pokigacha;

import com.google.gson.JsonPrimitive;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * /뽑기 추가 <종류> <치장칸> <번호> — 치장을 번호로 한꺼번에 보상에 넣기 (하나씩 꺼내서 끌어 넣지 않아도 됨)
 * 이름은 PokiCosmetics 모드의 목록(OUTFITS, HATS …)을 읽어서 붙임 (못 읽으면 "치장 N번")
 */
final class Bulk {
    /** 한글 칸 이름 → (아이템 id, 모드 목록 필드) */
    static final Map<String, String[]> SLOTS = new LinkedHashMap<>();
    static {
        SLOTS.put("옷", new String[]{"outfit", "OUTFITS"});
        SLOTS.put("모자", new String[]{"hat", "HATS"});
        SLOTS.put("꼬리", new String[]{"tail", "TAILS"});
        SLOTS.put("등", new String[]{"back", "BACKS"});
        SLOTS.put("얼굴", new String[]{"face", "FACES"});
    }
    /** 관리자 전용 옷 (뽑기에 넣지 않음) */
    static final Set<Integer> ADMIN_OUTFITS = Set.of(3, 5);

    @SuppressWarnings("unchecked")
    static Map<Integer, String> names(String field) {
        try {
            Class<?> k = Class.forName("kr.devaechu.pokicosmetics.PokiCosmetics");
            Object m = k.getField(field).get(null);
            if (m instanceof Map<?, ?> map) return (Map<Integer, String>) map;
        } catch (Throwable t) {
            Bukkit.getLogger().info("[PokiGacha] 치장 이름 목록을 못 읽음 (" + field + "): " + t);
        }
        return Collections.emptyMap();
    }

    /** "27-86", "1,3,5", "40-44,2", "전부" → 번호들 (전부는 모드 목록 필요) */
    static List<Integer> parse(String spec, Map<Integer, String> known) {
        Set<Integer> out = new TreeSet<>();
        if (spec.equals("전부") || spec.equalsIgnoreCase("all")) { out.addAll(known.keySet()); return new ArrayList<>(out); }
        for (String part : spec.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            int dash = part.indexOf('-', 1);
            if (dash > 0) {
                int a = Integer.parseInt(part.substring(0, dash).trim()), b = Integer.parseInt(part.substring(dash + 1).trim());
                if (b < a) { int t = a; a = b; b = t; }
                if (b - a > 500) throw new NumberFormatException("범위가 너무 커요");
                for (int i = a; i <= b; i++) out.add(i);
            } else out.add(Integer.parseInt(part));
        }
        return new ArrayList<>(out);
    }

    static ItemStack make(String id, int cmd, String name) {
        StringBuilder sb = new StringBuilder("pokicos:").append(id).append("[minecraft:custom_model_data=").append(cmd);
        if (name != null) {
            String json = new JsonPrimitive(name).toString();               // "세일러 마린룩"
            sb.append(",minecraft:item_name='").append(json.replace("\\", "\\\\").replace("'", "\\'")).append("'");
        }
        sb.append("]");
        try {
            return Bukkit.getItemFactory().createItemStack(sb.toString());
        } catch (Throwable t) {
            if (name != null) return make(id, cmd, null);                    // 이름 붙이기 실패 → 이름 없이
            throw t;
        }
    }

    /** 결과: [넣음, 이미 있음, 건너뜀(목록에 없는 번호·관리자 옷)] */
    static int[] add(Pool pool, String slotKo, String spec, List<String> report) {
        String[] slot = SLOTS.get(slotKo);
        Map<Integer, String> known = names(slot[1]);
        List<Integer> nums = parse(spec, known);
        Set<String> have = new LinkedHashSet<>();
        for (Pool.Reward r : pool.rewards) {
            String k = Util.cosmeticKey(r.item);
            if (k != null) have.add(k);
        }
        int added = 0, dup = 0, skip = 0;
        for (int n : nums) {
            if (slot[0].equals("outfit") && ADMIN_OUTFITS.contains(n)) { skip++; continue; }
            if (!known.isEmpty() && !known.containsKey(n)) { skip++; continue; }
            if (have.contains(slot[0] + ":" + n)) { dup++; continue; }
            ItemStack it;
            try {
                it = make(slot[0], n, known.get(n));
            } catch (Throwable t) {
                report.add("&c" + slotKo + " " + n + "번 아이템을 못 만듦: " + t.getMessage());
                skip++;
                continue;
            }
            Pool.Reward r = new Pool.Reward();
            r.item = it;
            r.weight = 1;
            r.commands = new ArrayList<>();
            pool.rewards.add(r);
            have.add(slot[0] + ":" + n);
            added++;
        }
        return new int[]{added, dup, skip};
    }

    /** /뽑기 빼기 <종류> <치장칸> <번호> */
    static int remove(Pool pool, String slotKo, String spec) {
        String[] slot = SLOTS.get(slotKo);
        List<Integer> nums = parse(spec, names(slot[1]));
        Set<String> keys = new LinkedHashSet<>();
        for (int n : nums) keys.add(slot[0] + ":" + n);
        int before = pool.rewards.size();
        pool.rewards.removeIf(r -> keys.contains(Util.cosmeticKey(r.item)));
        return before - pool.rewards.size();
    }
}

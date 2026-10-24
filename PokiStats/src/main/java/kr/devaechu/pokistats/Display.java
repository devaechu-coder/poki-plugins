package kr.devaechu.pokistats;

import kr.devaechu.pokistats.rank.Category;
import kr.devaechu.pokistats.rank.RankDb;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 화면 표시 도우미 — 한글닉, 아이콘, 줄 형식 */
final class Display {

    private static final Map<Category, Material> ICONS = new EnumMap<>(Category.class);

    private Display() { }

    static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }

    /** PokiName 이 있으면 한글닉 (%pokiname_nick_<마크닉>%), 없으면 마크닉 */
    static String nick(String name) {
        if (name == null) return "?";
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI") && Bukkit.getPluginManager().isPluginEnabled("PokiName")) {
            try {
                String n = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(null, "%pokiname_nick_" + name + "%");
                if (n != null && !n.isBlank() && !n.startsWith("%")) return ChatColor.stripColor(color(n));
            } catch (Throwable ignored) {
            }
        }
        return name;
    }

    /** 아이콘: 모드 아이템(코블몬 볼 등) → 안 되면 바닐라 */
    static Material icon(Category c) {
        return ICONS.computeIfAbsent(c, k -> {
            Material m = null;
            try {
                NamespacedKey key = NamespacedKey.fromString(k.iconId);
                if (key != null) m = Registry.MATERIAL.get(key);
            } catch (Throwable ignored) {
            }
            if (m == null) m = Material.getMaterial(k.iconId.replace(':', '_').toUpperCase(Locale.ROOT));
            if (m == null || !m.isItem()) {
                m = null;
                for (String f : k.iconFallback.split(";")) {
                    Material v = Material.getMaterial(f.trim());
                    if (v != null && v.isItem()) {
                        m = v;
                        break;
                    }
                }
            }
            return m == null ? Material.PAPER : m;
        });
    }

    /** n등(1부터) 줄, 없으면 null */
    static RankDb.Row at(List<RankDb.Row> rows, int n) {
        return n >= 1 && n <= rows.size() ? rows.get(n - 1) : null;
    }

    /** 내 순위 (몇 등, 없으면 -1) */
    static int indexOf(List<RankDb.Row> rows, String uuid) {
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).uuid().equals(uuid)) return i + 1;
        return -1;
    }

    /** PvP 강자 승률 */
    static String winRate(RankDb.Row r) {
        double total = r.value() + r.extra();
        if (total <= 0) return "-";
        return String.format("%.0f%%", r.value() * 100 / total);
    }
}

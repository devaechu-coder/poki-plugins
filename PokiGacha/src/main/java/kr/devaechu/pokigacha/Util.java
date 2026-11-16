package kr.devaechu.pokigacha;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class Util {
    private Util() { }

    static String color(String s) {
        return s == null ? "" : ChatColor.translateAlternateColorCodes('&', s);
    }

    /** 아이템 이름: 붙인 이름 > 아이템 이름 컴포넌트(치장) > 아이템 id */
    static String name(ItemStack it) {
        if (it == null) return "?";
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            try {
                if (m.hasDisplayName()) return ChatColor.stripColor(m.getDisplayName());
            } catch (Throwable ignored) { }
            try {
                if (m.hasItemName()) return ChatColor.stripColor(m.getItemName());
            } catch (Throwable ignored) { }
        }
        String path = it.getType().getKey().getKey().replace('_', ' ');
        return path.isEmpty() ? "?" : path;
    }

    /** PokiName 한글닉 (PlaceholderAPI), 없으면 마크닉 */
    static String nick(Player p) {
        String out = p.getName();
        try {
            if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI") && Bukkit.getPluginManager().isPluginEnabled("PokiName")) {
                Class<?> c = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
                Object r = c.getMethod("setPlaceholders", org.bukkit.OfflinePlayer.class, String.class).invoke(null, null, "%pokiname_nick_" + p.getName() + "%");
                String n = r == null ? "" : ChatColor.stripColor(color(r.toString())).trim();
                if (!n.isEmpty() && !n.startsWith("%")) out = n;
            }
        } catch (Throwable ignored) { }
        return out;
    }

    // ---------- 치장 (PokiCosmetics 옷장) ----------

    /** 치장 아이템의 옷장 키 "outfit:16" (치장이 아니면 null) — PokiCosmetics Wardrobe.key 와 같은 규칙 */
    static String cosmeticKey(ItemStack it) {
        if (it == null || !"pokicos".equals(it.getType().getKey().getNamespace())) return null;
        ItemMeta m = it.getItemMeta();
        try {
            if (m == null || !m.hasCustomModelData()) return null;
            return it.getType().getKey().getKey() + ":" + m.getCustomModelData();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 이미 가진 치장: 옷장(<월드>/pokicos/wardrobe.json) + 지금 가방에 든 것 */
    static Set<String> ownedCosmetics(Player p) {
        Set<String> out = new HashSet<>();
        try {
            List<World> worlds = Bukkit.getWorlds();
            if (!worlds.isEmpty()) {
                File f = new File(worlds.get(0).getWorldFolder(), "pokicos/wardrobe.json");
                if (f.isFile()) {
                    JsonElement root = JsonParser.parseString(Files.readString(f.toPath(), StandardCharsets.UTF_8));
                    if (root.isJsonObject()) {
                        JsonObject o = root.getAsJsonObject();
                        JsonElement mine = o.get(p.getUniqueId().toString());
                        if (mine != null && mine.isJsonArray()) {
                            JsonArray a = mine.getAsJsonArray();
                            for (JsonElement e : a) out.add(e.getAsString());
                        }
                    }
                }
            }
        } catch (Exception e) {
            Bukkit.getLogger().warning("[PokiGacha] 옷장 파일을 못 읽음: " + e);
        }
        for (ItemStack it : p.getInventory().getContents()) {
            String k = cosmeticKey(it);
            if (k != null) out.add(k);
        }
        return out;
    }
}

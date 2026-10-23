package kr.devaechu.pokiwardrobe;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 플레이어별 가진 치장 / 착용 중인 치장 / 파티클 숨기기 — wardrobe-players.yml
 * (PokiName 칭호의 player-titles.yml 과 같은 모양)
 */
final class Store {

    static final class Data {
        String name;
        final Set<String> owned = new LinkedHashSet<>();
        final Map<Cosmetic.Category, String> worn = new EnumMap<>(Cosmetic.Category.class);
        boolean hideParticles;
    }

    private final PokiWardrobe plugin;
    private final Map<UUID, Data> players = new LinkedHashMap<>();
    private File file;

    Store(PokiWardrobe plugin) {
        this.plugin = plugin;
    }

    Data get(UUID id) {
        return players.get(id);
    }

    Data getOrCreate(UUID id, String name) {
        Data d = players.computeIfAbsent(id, k -> new Data());
        if (name != null) d.name = name;
        return d;
    }

    UUID findByName(String name) {
        for (Map.Entry<UUID, Data> e : players.entrySet())
            if (name.equalsIgnoreCase(e.getValue().name)) return e.getKey();
        return null;
    }

    void load() {
        players.clear();
        file = new File(plugin.getDataFolder(), "wardrobe-players.yml");
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = y.getConfigurationSection("players");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            try {
                ConfigurationSection ps = sec.getConfigurationSection(key);
                if (ps == null) continue;
                Data d = new Data();
                d.name = ps.getString("name");
                for (String c : ps.getStringList("owned")) d.owned.add(c.toLowerCase(Locale.ROOT));
                ConfigurationSection w = ps.getConfigurationSection("worn");
                if (w != null) for (String cat : w.getKeys(false)) {
                    Cosmetic.Category c = Cosmetic.Category.of(cat);
                    String v = w.getString(cat);
                    if (c != null && v != null && !v.isEmpty()) d.worn.put(c, v.toLowerCase(Locale.ROOT));
                }
                d.hideParticles = ps.getBoolean("hide-particles", false);
                players.put(UUID.fromString(key), d);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Data> e : players.entrySet()) {
            Data d = e.getValue();
            if (d.owned.isEmpty() && d.worn.isEmpty() && !d.hideParticles) continue;
            String base = "players." + e.getKey();
            y.set(base + ".name", d.name);
            y.set(base + ".owned", new ArrayList<>(d.owned));
            for (Map.Entry<Cosmetic.Category, String> w : d.worn.entrySet())
                y.set(base + ".worn." + w.getKey().name().toLowerCase(Locale.ROOT), w.getValue());
            if (d.hideParticles) y.set(base + ".hide-particles", true);
        }
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("wardrobe-players.yml 저장 실패: " + ex.getMessage());
        }
    }
}

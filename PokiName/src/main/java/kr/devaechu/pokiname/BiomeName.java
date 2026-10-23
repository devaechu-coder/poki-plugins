package kr.devaechu.pokiname;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Biome;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 바이옴 이름을 보기 좋게 — TERRALITH_ORCHID_SWAMP → 난초 늪
 * biomes.yml 에 한글 이름, 없으면 영어를 "Orchid Swamp" 처럼 바꿔서 보여줌.
 * 스코어보드 플러그인이 비동기로 물어봐도 안전하게, 1초마다 메인 스레드에서 미리 계산해둔다.
 */
final class BiomeName {

    private final PokiName plugin;
    private final Map<String, String> names = new HashMap<>();
    private final Map<UUID, String> current = new ConcurrentHashMap<>();
    private BukkitTask task;
    /** 한글 이름이 없어서 콘솔에 이미 알린 바이옴 */
    private final Set<String> reported = new HashSet<>();

    BiomeName(PokiName plugin) {
        this.plugin = plugin;
    }

    void start() {
        stop();
        loadNames();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::update, 20L, 20L);
    }

    void stop() {
        if (task != null) task.cancel();
        task = null;
        current.clear();
    }

    private void loadNames() {
        names.clear();
        File file = new File(plugin.getDataFolder(), "biomes.yml");
        if (!file.exists()) plugin.saveResource("biomes.yml", false);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        // 플러그인 업데이트로 새로 생긴 이름도 쓰게 jar 기본값을 밑에 깔기
        try (InputStream in = plugin.getResource("biomes.yml")) {
            if (in != null) y.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (Exception ignored) {
        }
        ConfigurationSection sec = y.getConfigurationSection("names");
        if (sec != null) {
            for (String k : sec.getKeys(false)) {
                String v = sec.getString(k);
                if (v != null && !v.isBlank()) names.put(k.toLowerCase(Locale.ROOT), v);
            }
        }
    }

    private void update() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                current.put(p.getUniqueId(), resolve(p.getLocation().getBlock().getBiome()));
            } catch (RuntimeException ignored) {
            }
        }
        current.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    /** 바이옴 → 표시 이름 */
    String resolve(Biome b) {
        String ns = null;
        String path = null;
        try {
            NamespacedKey k = b.getKey();
            ns = k.getNamespace().toLowerCase(Locale.ROOT);
            path = k.getKey().toLowerCase(Locale.ROOT);
        } catch (Throwable ignored) {
        }
        String enumName = b.name().toLowerCase(Locale.ROOT);
        return resolve(ns, path, enumName);
    }

    /** 테스트하기 쉽게 문자열만 받는 버전 */
    String resolve(String ns, String path, String enumName) {
        // 후보: 키 경로 → 열거형 이름 → 열거형 이름에서 모드 접두사 뗀 것
        String[] candidates = {
            path,
            enumName,
            ns != null && enumName != null && enumName.startsWith(ns + "_") ? enumName.substring(ns.length() + 1) : null,
            stripModPrefix(enumName),
            stripModPrefix(path),
        };
        for (String c : candidates) {
            if (c != null && names.containsKey(c)) return names.get(c);
        }
        String raw = (ns == null ? "?" : ns) + ":" + (path == null ? "?" : path) + " / " + enumName;
        if (reported.add(raw))
            plugin.getLogger().info("바이옴 한글 이름 없음 → biomes.yml 에 추가하면 됨: " + raw);
        String base = path != null && !"minecraft".equals(ns) ? path : stripModPrefix(enumName);
        return pretty(base != null ? base : String.valueOf(enumName));
    }

    /** /한글닉 바이옴 — 지금 서 있는 바이옴이 어떻게 읽히는지 */
    String[] debug(Player p) {
        Biome b = p.getLocation().getBlock().getBiome();
        String key;
        try { key = String.valueOf(b.getKey()); } catch (Throwable t) { key = "(못 읽음: " + t.getClass().getSimpleName() + ")"; }
        return new String[]{
            "바이옴 이름(enum): " + b.name(),
            "바이옴 키: " + key,
            "보여줄 이름: " + resolve(b),
            "지금 %pokiname_biome% 값: " + get(p.getUniqueId()),
        };
    }

    /** terralith_orchid_swamp → orchid_swamp (모드 이름이 앞에 붙은 경우) */
    private static String stripModPrefix(String s) {
        if (s == null) return null;
        for (String mod : new String[]{"terralith_", "minecraft_", "cobblemon_", "biomesoplenty_", "byg_", "regions_unexplored_"}) {
            if (s.startsWith(mod)) return s.substring(mod.length());
        }
        return s;
    }

    /** orchid_swamp → Orchid Swamp */
    static String pretty(String s) {
        StringBuilder sb = new StringBuilder();
        for (String w : s.replace(':', '_').split("_")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    String get(UUID id) {
        return current.getOrDefault(id, "");
    }

    /** 긴 이름을 두 줄로: line 1 또는 2 (띄어쓰기에서 자름) */
    String part(UUID id, int line) {
        String s = get(id);
        int max = Math.max(4, plugin.getConfig().getInt("biome.max-length", 10));
        if (s.length() <= max) return line == 1 ? s : "";
        int cut = s.lastIndexOf(' ', max);
        if (cut <= 0) cut = max;
        String first = s.substring(0, cut).trim();
        String second = s.substring(cut).trim();
        if (second.length() > max) second = second.substring(0, max - 1) + "…";
        return line == 1 ? first : second;
    }
}

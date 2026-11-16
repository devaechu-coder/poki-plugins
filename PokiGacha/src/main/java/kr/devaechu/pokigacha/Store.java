package kr.devaechu.pokigacha;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * gacha.yml — 뽑기 종류·보상 (인게임 편집이 여기로 저장). 저장할 때마다 backups/ 에 이전 파일을 남김.
 * pending.yml — 접속 안 한 사람에게 지급할 뽑기권 (접속하면 줌)
 *
 * 아이템은 두 가지로 같이 적음: nbt (예: pokicos:outfit[custom_model_data=16]) 를 먼저 쓰고, 안 되면 item (버킷 직렬화)
 */
final class Store {
    private static final int KEEP_BACKUPS = 30;

    private final PokiGacha plugin;
    private final File file, pendingFile, backupDir;
    final Map<String, Pool> pools = new LinkedHashMap<>();
    final Map<UUID, Map<String, Integer>> pending = new LinkedHashMap<>();

    Store(PokiGacha plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "gacha.yml");
        this.pendingFile = new File(plugin.getDataFolder(), "pending.yml");
        this.backupDir = new File(plugin.getDataFolder(), "backups");
    }

    // ---------- 아이템 ----------

    static String itemString(ItemStack it) {
        try {
            String comp = it.hasItemMeta() ? it.getItemMeta().getAsComponentString() : "";
            return it.getType().getKey() + (comp == null || comp.equals("[]") ? "" : comp);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeItem(ConfigurationSection s, ItemStack it) {
        ItemStack one = it.clone();
        s.set("amount", one.getAmount());
        String str = itemString(one);
        if (str != null) s.set("nbt", str);
        one.setAmount(1);
        s.set("item", one);
    }

    private ItemStack readItem(ConfigurationSection s) {
        if (s == null) return null;
        int amount = Math.max(1, s.getInt("amount", 1));
        String str = s.getString("nbt");
        if (str != null && !str.isBlank()) {
            try {
                ItemStack it = Bukkit.getItemFactory().createItemStack(str);
                it.setAmount(amount);
                return it;
            } catch (Throwable t) {
                plugin.getLogger().warning("아이템 문자열을 못 읽음 → 직렬화 값으로: " + str + " (" + t.getMessage() + ")");
            }
        }
        ItemStack it = s.getItemStack("item");
        if (it != null) it.setAmount(amount);
        return it;
    }

    // ---------- 읽기·쓰기 ----------

    void load() {
        pools.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection ps = y.getConfigurationSection("pools");
        if (ps != null) {
            for (String name : ps.getKeys(false)) {
                ConfigurationSection s = ps.getConfigurationSection(name);
                if (s == null) continue;
                Pool p = new Pool(name);
                p.display = s.getString("display", p.display);
                p.ticket = readItem(s.getConfigurationSection("ticket"));
                p.noDuplicate = s.getBoolean("no-duplicate", false);
                p.fallbackPool = s.getString("fallback.pool", null);
                p.fallbackCount = Math.max(1, s.getInt("fallback.count", 1));
                for (Map<?, ?> m : s.getMapList("rewards")) {
                    YamlConfiguration tmp = new YamlConfiguration();
                    for (Map.Entry<?, ?> e : m.entrySet()) tmp.set(String.valueOf(e.getKey()), e.getValue());
                    Pool.Reward r = new Pool.Reward();
                    r.item = readItem(tmp);
                    r.weight = Math.max(0, tmp.getInt("weight", 1));
                    r.commands = new ArrayList<>(tmp.getStringList("commands"));
                    if (r.item == null && r.commands.isEmpty()) continue;
                    p.rewards.add(r);
                }
                pools.put(name, p);
            }
        }
        if (pools.isEmpty()) {
            // 처음: 빈 뽑기 두 개
            Pool cos = new Pool("치장");
            cos.display = "&d치장 뽑기권";
            cos.noDuplicate = true;
            cos.fallbackPool = "일반도구";
            Pool tool = new Pool("일반도구");
            tool.display = "&6일반도구 뽑기권";
            pools.put(cos.name, cos);
            pools.put(tool.name, tool);
            save(false);
        }
        pending.clear();
        YamlConfiguration py = YamlConfiguration.loadConfiguration(pendingFile);
        for (String k : py.getKeys(false)) {
            try {
                UUID u = UUID.fromString(k);
                ConfigurationSection s = py.getConfigurationSection(k);
                if (s == null) continue;
                Map<String, Integer> m = new LinkedHashMap<>();
                for (String pool : s.getKeys(false)) m.put(pool, s.getInt(pool));
                pending.put(u, m);
            } catch (IllegalArgumentException ignored) { }
        }
    }

    /** backup=true 면 바꾸기 전 파일을 backups/ 에 남김 */
    void save(boolean backup) {
        if (backup && file.exists()) {
            try {
                backupDir.mkdirs();
                String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
                Files.copy(file.toPath(), new File(backupDir, "gacha-" + stamp + ".yml").toPath(), StandardCopyOption.REPLACE_EXISTING);
                File[] old = backups();
                for (int i = KEEP_BACKUPS; i < old.length; i++) old[i].delete();
            } catch (IOException e) {
                plugin.getLogger().warning("백업 실패: " + e);
            }
        }
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of(
                "포키 뽑기 설정 — 인게임 /뽑기 편집 <종류> 로 고치는 걸 추천 (여기를 고쳤으면 /뽑기 리로드)",
                "weight = 확률 무게 (기본 1 = 전부 같은 확률, 2 면 두 배 잘 나옴)",
                "no-duplicate = 이미 옷장에 있는 치장은 안 나옴, fallback = 다 가졌으면 대신 줄 뽑기권",
                "commands = 같이 실행할 명령어 (%player% = 마크닉) — 아이템 없이 명령어만 있는 보상도 됨"));
        for (Pool p : pools.values()) {
            ConfigurationSection s = y.createSection("pools." + p.name);
            s.set("display", p.display);
            if (p.ticket != null) writeItem(s.createSection("ticket"), p.ticket);
            s.set("no-duplicate", p.noDuplicate);
            if (p.fallbackPool != null) {
                s.set("fallback.pool", p.fallbackPool);
                s.set("fallback.count", p.fallbackCount);
            }
            List<Map<String, Object>> list = new ArrayList<>();
            for (Pool.Reward r : p.rewards) {
                YamlConfiguration tmp = new YamlConfiguration();
                if (r.item != null) writeItem(tmp, r.item);
                tmp.set("weight", r.weight);
                if (!r.commands.isEmpty()) tmp.set("commands", r.commands);
                list.add(tmp.getValues(false));
            }
            s.set("rewards", list);
        }
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("gacha.yml 저장 실패: " + e);
        }
    }

    File[] backups() {
        File[] f = backupDir.listFiles((d, n) -> n.startsWith("gacha-") && n.endsWith(".yml"));
        if (f == null) return new File[0];
        Arrays.sort(f, Comparator.comparing(File::getName).reversed());
        return f;
    }

    /** 가장 최근 백업으로 되돌림 (그 백업은 지움 → 한 번 더 하면 그 전으로) */
    boolean restoreLatest() {
        File[] b = backups();
        if (b.length == 0) return false;
        try {
            Files.copy(b[0].toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            b[0].delete();
            load();
            return true;
        } catch (IOException e) {
            plugin.getLogger().warning("되돌리기 실패: " + e);
            return false;
        }
    }

    void savePending() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Map<String, Integer>> e : pending.entrySet()) {
            for (Map.Entry<String, Integer> p : e.getValue().entrySet()) {
                if (p.getValue() > 0) y.set(e.getKey() + "." + p.getKey(), p.getValue());
            }
        }
        try {
            y.save(pendingFile);
        } catch (IOException e) {
            plugin.getLogger().warning("pending.yml 저장 실패: " + e);
        }
    }
}

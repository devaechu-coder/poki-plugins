package kr.devaechu.pokistats;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import kr.devaechu.pokistats.rank.RankDb;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.io.File;
import java.io.FileReader;
import java.io.Reader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * 소지금(Vault·Essentials, $) + 코블달러(CobbleDollars, C) 읽기 — PokiName 순위와 같은 방식.
 * 코블달러: 접속 중이면 모드에서 실시간, 아니면 <월드>/cobbledollarsplayerdata/<uuid>.json
 * 메인 스레드에서 부름.
 */
final class Wealth {

    private record Cached(long modified, String name, double value) { }

    private final Logger log;
    private final Map<String, Cached> files = new HashMap<>();
    private Economy econ;
    private boolean econChecked;
    private Method liveGetter;
    private boolean liveFailed;

    Wealth(Logger log) {
        this.log = log;
    }

    /** 한 번이라도 접속한 사람 전부의 지금 잔액 */
    List<RankDb.Wealth> collect(String cobbleFolder) {
        Map<UUID, String> names = new HashMap<>();
        Map<UUID, Double> money = new HashMap<>();
        Map<UUID, Double> coin = new HashMap<>();

        Economy e = economy();
        if (e != null) {
            for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
                if (op.getName() == null) continue;
                try {
                    if (!e.hasAccount(op)) continue;
                    money.put(op.getUniqueId(), e.getBalance(op));
                    names.put(op.getUniqueId(), op.getName());
                } catch (RuntimeException ignored) {
                }
            }
        }

        File folder = cobbleFolder != null && !cobbleFolder.isBlank() ? new File(cobbleFolder)
                : Bukkit.getWorlds().isEmpty() ? null : new File(Bukkit.getWorlds().get(0).getWorldFolder(), "cobbledollarsplayerdata");
        if (folder != null && folder.isDirectory()) {
            File[] list = folder.listFiles((d, n) -> n.endsWith(".json"));
            Set<String> seen = new HashSet<>();
            if (list != null) {
                for (File f : list) {
                    String key = f.getName();
                    seen.add(key);
                    long mod = f.lastModified();
                    Cached c = files.get(key);
                    if (c == null || c.modified() != mod) {
                        c = read(f, mod);
                        if (c == null) continue;
                        files.put(key, c);
                    }
                    UUID id;
                    try {
                        id = UUID.fromString(key.substring(0, key.length() - 5));
                    } catch (IllegalArgumentException ex) {
                        continue;
                    }
                    coin.put(id, c.value());
                    if (!names.containsKey(id)) {
                        String n = Bukkit.getOfflinePlayer(id).getName();
                        names.put(id, n != null ? n : c.name());
                    }
                }
            }
            files.keySet().retainAll(seen);
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            names.put(p.getUniqueId(), p.getName());
            Double live = live(p);
            if (live != null) coin.put(p.getUniqueId(), live);
        }

        List<RankDb.Wealth> out = new ArrayList<>();
        for (Map.Entry<UUID, String> n : names.entrySet()) {
            if (n.getValue() == null) continue;
            UUID id = n.getKey();
            out.add(new RankDb.Wealth(id.toString(), n.getValue(), money.getOrDefault(id, 0d), coin.getOrDefault(id, 0d)));
        }
        return out;
    }

    /** 통계 서버로 보낼 사람: 접속 중 전원 + 소지금 상위 100 + 코블달러 상위 100 (겹치면 한 번, 최대 1000) */
    static List<RankDb.Wealth> pickForUpload(List<RankDb.Wealth> all) {
        Map<String, RankDb.Wealth> pick = new LinkedHashMap<>();
        Map<String, RankDb.Wealth> byId = new HashMap<>();
        for (RankDb.Wealth w : all) byId.put(w.uuid(), w);
        for (Player p : Bukkit.getOnlinePlayers()) {
            RankDb.Wealth w = byId.get(p.getUniqueId().toString());
            if (w != null) pick.put(w.uuid(), w);
        }
        all.stream().sorted(Comparator.comparingDouble(RankDb.Wealth::money).reversed()).limit(100).forEach(w -> pick.putIfAbsent(w.uuid(), w));
        all.stream().sorted(Comparator.comparingDouble(RankDb.Wealth::coin).reversed()).limit(100).forEach(w -> pick.putIfAbsent(w.uuid(), w));
        List<RankDb.Wealth> out = new ArrayList<>(pick.values());
        return out.size() > 1000 ? out.subList(0, 1000) : out;
    }

    private Economy economy() {
        if (econChecked) return econ;
        econChecked = true;
        if (Bukkit.getPluginManager().getPlugin("Vault") != null) {
            RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
            econ = rsp == null ? null : rsp.getProvider();
        }
        if (econ == null) log.warning("Vault 경제를 못 찾아서 소지금은 0 으로 보냄");
        return econ;
    }

    void resetEconomy() {
        econChecked = false;
        econ = null;
    }

    private Cached read(File f, long mod) {
        try (Reader r = new FileReader(f, StandardCharsets.UTF_8)) {
            JsonElement el = JsonParser.parseReader(r);
            if (!el.isJsonObject()) return null;
            JsonObject o = el.getAsJsonObject();
            JsonElement v = o.get("cobbledollars");
            if (v == null) return null;
            String name = o.has("name") ? o.get("name").getAsString() : null;
            return new Cached(mod, name, v.getAsBigInteger().doubleValue());
        } catch (Exception ex) {
            return null;
        }
    }

    private Double live(Player p) {
        if (liveFailed) return null;
        try {
            Object handle = p.getClass().getMethod("getHandle").invoke(p);
            if (liveGetter == null) liveGetter = findGetter(handle);
            Object v = liveGetter.invoke(null, handle);
            if (v instanceof BigInteger b) return b.doubleValue();
            if (v instanceof Number n) return n.doubleValue();
            return null;
        } catch (Throwable ex) {
            liveFailed = true;
            log.warning("코블달러 실시간 값을 못 읽어서 저장 파일 값만 씁니다: " + ex);
            return null;
        }
    }

    private static Method findGetter(Object handle) throws ClassNotFoundException, NoSuchMethodException {
        Class<?> k = Class.forName("fr.harmex.cobbledollars.common.utils.extensions.PlayerExtensionKt", true,
                handle.getClass().getClassLoader());
        for (Method m : k.getMethods()) {
            if (m.getName().equals("getCobbleDollars") && Modifier.isStatic(m.getModifiers())
                    && m.getParameterCount() == 1 && m.getParameterTypes()[0].isInstance(handle)) return m;
        }
        throw new NoSuchMethodException("getCobbleDollars(Player)");
    }
}

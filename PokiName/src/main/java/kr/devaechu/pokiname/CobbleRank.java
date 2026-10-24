package kr.devaechu.pokiname;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileReader;
import java.io.Reader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 코블달러 순위 — CobbleDollars 모드 데이터로 계산.
 *
 * - 모드는 <월드>/cobbledollarsplayerdata/<uuid>.json 에 {"name","cobbledollars"} 를 저장한다
 *   (접속·퇴장·서버 종료·주기 저장 때). → 한 번이라도 접속한 사람 전부.
 *   파일은 바뀐 것만 다시 읽어서 디스크 부담이 거의 없다.
 * - 접속 중인 사람은 모드의 PlayerExtensionKt.getCobbleDollars(플레이어) 로 실시간 값을 읽는다.
 *   (하이브리드 서버라 리플렉션으로 호출. 실패하면 파일 값만 사용)
 */
final class CobbleRank extends Rank {

    private record Cached(long modified, String name, double value) { }

    private final Map<String, Cached> files = new HashMap<>();
    private File folder;
    private Method liveGetter;
    private boolean liveFailed;

    CobbleRank(PokiName plugin) {
        super(plugin, "cobble-rank", "코블달러", "{value}C");
    }

    @Override
    protected boolean prepare() {
        files.clear();
        liveGetter = null;
        liveFailed = false;
        String custom = section().getString("data-folder", "");
        if (custom != null && !custom.isBlank()) folder = new File(custom);
        else if (!Bukkit.getWorlds().isEmpty()) folder = new File(Bukkit.getWorlds().get(0).getWorldFolder(), "cobbledollarsplayerdata");
        if (folder == null || !folder.isDirectory()) {
            plugin.getLogger().warning("코블달러 데이터 폴더가 없어서 코블달러 순위를 끕니다: " + folder
                    + " (config 의 cobble-rank.data-folder 로 지정 가능)");
            return false;
        }
        return true;
    }

    @Override
    protected String source() {
        return folder == null ? "&c없음" : folder.getPath() + (liveFailed ? " &c(실시간 값 못 읽음 — 파일 값만)" : " + 접속 중인 사람은 실시간");
    }

    @Override
    protected List<Entry> collect() {
        Map<UUID, Entry> byId = new HashMap<>();

        // 1) 파일 (바뀐 것만 다시 읽음)
        File[] list = folder.listFiles((d, n) -> n.endsWith(".json"));
        if (list != null) {
            java.util.Set<String> seen = new java.util.HashSet<>();
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
                try { id = UUID.fromString(key.substring(0, key.length() - 5)); } catch (IllegalArgumentException ex) { continue; }
                OfflinePlayer op = Bukkit.getOfflinePlayer(id);
                String name = op.getName() != null ? op.getName() : c.name();
                byId.put(id, new Entry(id, name, c.value()));
            }
            files.keySet().retainAll(seen);
        }

        // 2) 접속 중인 사람은 실시간 값으로 덮어씀
        for (Player p : Bukkit.getOnlinePlayers()) {
            Double live = live(p);
            if (live != null) byId.put(p.getUniqueId(), new Entry(p.getUniqueId(), p.getName(), live));
        }
        return new ArrayList<>(byId.values());
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
            return null; // 저장 중이라 깨져 보이면 다음에 다시
        }
    }

    /** 모드에서 실시간 잔액 읽기. 안 되면 null */
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
            plugin.getLogger().warning("코블달러 실시간 값을 못 읽어서 저장 파일 값만 씁니다: " + ex);
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

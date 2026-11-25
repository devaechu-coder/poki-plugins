package kr.devaechu.pokiname;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 머리 위 마을 표시 — PokiTown 플러그인(kr.devaechu.pokitown.PokiTownApi)에서 마을 이름·색을 받음.
 * PokiTown 이 없으면 마을 표시만 꺼지고 나머지는 그대로. 리플렉션이라 PokiTown 없이도 빌드·실행됨.
 * 마을이 바뀐 사람만 PokiTown 이 알려줌 → 그 사람 이름표만 다시 적용 (주기 확인 없음).
 */
final class TownLink {
    private final PokiName plugin;
    private Method townOf;

    TownLink(PokiName plugin) { this.plugin = plugin; }

    /** PokiTown 찾아서 연결. 성공하면 true */
    boolean connect() {
        townOf = null;
        Plugin pt = Bukkit.getPluginManager().getPlugin("PokiTown");
        if (pt == null || !pt.isEnabled()) return false;
        try {
            Class<?> api = Class.forName("kr.devaechu.pokitown.PokiTownApi", true, pt.getClass().getClassLoader());
            townOf = api.getMethod("townOf", UUID.class);
            Consumer<UUID> onChange = id -> {
                Player p = Bukkit.getPlayer(id);
                if (p != null) plugin.refresh(p);
            };
            api.getMethod("onChange", Consumer.class).invoke(null, onChange);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("PokiTown 연결 실패 → 마을 표시 꺼짐: " + t);
            townOf = null;
            return false;
        }
    }

    boolean connected() { return townOf != null; }

    /** {uuid, name, display, color, role, custom} 또는 null */
    @SuppressWarnings("unchecked")
    Map<String, String> townOf(UUID id) {
        if (townOf == null) return null;
        try {
            return (Map<String, String>) townOf.invoke(null, id);
        } catch (Throwable t) {
            return null;
        }
    }
}

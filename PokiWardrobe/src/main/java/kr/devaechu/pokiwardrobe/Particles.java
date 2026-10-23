package kr.devaechu.pokiwardrobe;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 파티클 치장 — 몇 틱마다 발밑에 파티클.
 * 보는 사람마다 따로 보내서(spawnParticle) "파티클 숨기기" 한 사람한테는 안 보낸다.
 */
final class Particles {

    private final PokiWardrobe plugin;
    private BukkitTask task;
    private final Map<UUID, Location> lastPos = new HashMap<>();

    Particles(PokiWardrobe plugin) {
        this.plugin = plugin;
    }

    void start() {
        stop();
        int every = Math.max(1, plugin.getConfig().getInt("particles.interval-ticks", 5));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, every);
    }

    void stop() {
        if (task != null) task.cancel();
        task = null;
        lastPos.clear();
    }

    private void tick() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("particles");
        boolean movingOnly = c != null && c.getBoolean("moving-only", false);
        double range = c == null ? 24 : c.getDouble("view-distance", 24);
        double range2 = range * range;
        List<? extends Player> online = List.copyOf(Bukkit.getOnlinePlayers());

        for (Player p : online) {
            Store.Data d = plugin.store().get(p.getUniqueId());
            String id = d == null ? null : d.worn.get(Cosmetic.Category.PARTICLE);
            Cosmetic cos = id == null ? null : plugin.cosmetic(id);
            Location now = p.getLocation();
            Location before = lastPos.put(p.getUniqueId(), now);
            if (cos == null || cos.particle() == null) continue;
            if (hidden(p)) continue;
            boolean moving = before != null && before.getWorld() == now.getWorld() && before.distanceSquared(now) > 0.0025;
            if (movingOnly && !moving) continue;

            Location at = now.clone().add(0, moving ? 0.1 : 0.2, 0);
            for (Player viewer : online) {
                if (viewer.getWorld() != p.getWorld() || viewer.getLocation().distanceSquared(now) > range2) continue;
                Store.Data vd = plugin.store().get(viewer.getUniqueId());
                if (vd != null && vd.hideParticles && viewer != p) continue;
                if (!viewer.canSee(p)) continue;
                spawn(viewer, cos, at);
            }
        }
        lastPos.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    /** 투명화·관전자·사망·바닐라 숨기기(vanish 플러그인 표시) 중이면 안 뿌림 */
    private static boolean hidden(Player p) {
        if (p.isDead() || p.getGameMode() == GameMode.SPECTATOR || p.isInvisible()) return true;
        if (p.hasPotionEffect(PotionEffectType.INVISIBILITY)) return true;
        for (MetadataValue v : p.getMetadata("vanished")) if (v.asBoolean()) return true;
        return false;
    }

    private static void spawn(Player viewer, Cosmetic c, Location at) {
        Particle type = c.particle();
        int n = Math.max(1, c.count());
        try {
            if (type == Particle.DUST) {
                viewer.spawnParticle(type, at, n, 0.3, 0.1, 0.3, 0,
                        new Particle.DustOptions(c.dustColor() == null ? org.bukkit.Color.WHITE : c.dustColor(), 1.0f));
            } else if (type == Particle.NOTE) {
                // 음표는 count 0 일 때 offsetX 가 색 — 랜덤 색으로 하나씩
                for (int i = 0; i < n; i++)
                    viewer.spawnParticle(type, at.clone().add((Math.random() - 0.5) * 0.6, 0.3, (Math.random() - 0.5) * 0.6), 0, Math.random(), 0, 0, 1);
            } else if (type.getDataType() == Void.class) {
                viewer.spawnParticle(type, at, n, 0.3, 0.1, 0.3, 0.01);
            }
            // 그 밖에 데이터가 필요한 파티클은 cosmetics.yml 을 읽을 때 걸러짐
        } catch (RuntimeException ignored) {
        }
    }
}

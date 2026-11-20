package kr.devaechu.pokiportal;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * 네더 이동권: 들고 우클릭 → (대기) → 네더 랜덤 안전 위치로 이동. 돌아올 땐 /스폰.
 * 네더 포털은 막음 (불 붙이기·포털 이동·몹 이동). poki.portal.bypass(운영진)는 예외.
 */
public final class PokiPortal extends JavaPlugin implements Listener {
    private NamespacedKey key;
    private final Set<UUID> busy = new HashSet<>();
    private final Map<UUID, Long> lastPortalMsg = new HashMap<>();
    private final Random rnd = new Random();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        key = new NamespacedKey(this, "nether_ticket");
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    private static String c(String s) { return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s); }

    private String msg(String k) { return c(getConfig().getString("messages." + k, k)); }

    // ---------- 이동권 아이템 ----------

    ItemStack ticket(int amount) {
        ItemStack it;
        try {
            it = Bukkit.getItemFactory().createItemStack(getConfig().getString("ticket.item", "minecraft:paper"));
        } catch (Throwable t) {
            it = new ItemStack(Material.PAPER);
        }
        it.setAmount(Math.max(1, Math.min(64, amount)));
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.setDisplayName(c(getConfig().getString("ticket.name", "&6네더 이동권")));
            List<String> lore = new ArrayList<>();
            for (String l : getConfig().getStringList("ticket.lore")) lore.add(c(l));
            m.setLore(lore);
            m.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(m);
        }
        return it;
    }

    boolean isTicket(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    private void give(Player p, int n) {
        while (n > 0) {
            int k = Math.min(64, n);
            for (ItemStack left : p.getInventory().addItem(ticket(k)).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
            n -= k;
        }
    }

    private World nether() {
        for (World w : Bukkit.getWorlds()) if (w.getEnvironment() == World.Environment.NETHER) return w;
        return null;
    }

    // ---------- 사용 ----------

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!isTicket(e.getItem())) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        if (busy.contains(p.getUniqueId())) return;
        World nether = nether();
        if (nether == null) { p.sendMessage(msg("no-nether")); return; }
        if (p.getWorld().equals(nether)) { p.sendMessage(msg("in-nether")); return; }

        // 1장 쓰기 (실패하면 돌려줌)
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getAmount() <= 1) p.getInventory().setItemInMainHand(null);
        else hand.setAmount(hand.getAmount() - 1);
        busy.add(p.getUniqueId());
        p.sendMessage(msg("used"));
        p.playSound(p.getLocation(), Sound.BLOCK_PORTAL_TRIGGER, 0.4f, 1.2f);

        Location start = p.getLocation().clone();
        long warm = Math.max(0, getConfig().getInt("warmup", 3)) * 20L;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline()) { busy.remove(p.getUniqueId()); refund(p); return; }
            if (warm > 0 && (!p.getWorld().equals(start.getWorld()) || p.getLocation().distanceSquared(start) > 1.0)) {
                busy.remove(p.getUniqueId());
                refund(p);
                p.sendMessage(msg("moved"));
                return;
            }
            search(p, nether);
        }, warm);
    }

    private void refund(Player p) {
        if (p.isOnline()) give(p, 1);
        else getLogger().warning("네더 이동권 환불 못 함 (접속 끊김): " + p.getName());
    }

    /** 한 틱에 한 번씩 랜덤 자리를 시험 (렉 방지) */
    private void search(Player p, World w) {
        int tries = Math.max(1, getConfig().getInt("random.tries", 30));
        new BukkitRunnable() {
            int n = 0;
            @Override public void run() {
                if (!p.isOnline()) { busy.remove(p.getUniqueId()); cancel(); return; }
                Location l = tryOnce(w);
                if (l != null) {
                    busy.remove(p.getUniqueId());
                    cancel();
                    p.teleport(l, PlayerTeleportEvent.TeleportCause.PLUGIN);
                    p.sendMessage(msg("arrived"));
                    p.playSound(l, Sound.BLOCK_PORTAL_TRAVEL, 0.25f, 1.0f);
                    getLogger().info(p.getName() + " → 네더 " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ());
                    return;
                }
                if (++n >= tries) {
                    busy.remove(p.getUniqueId());
                    cancel();
                    refund(p);
                    p.sendMessage(msg("failed"));
                }
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    private Location tryOnce(World w) {
        int min = Math.max(0, getConfig().getInt("random.min-distance", 300));
        int max = Math.max(min + 1, getConfig().getInt("random.max-distance", 3000));
        double r = min + rnd.nextDouble() * (max - min), a = rnd.nextDouble() * Math.PI * 2;
        int x = (int) Math.round(Math.cos(a) * r), z = (int) Math.round(Math.sin(a) * r);
        if (!w.getWorldBorder().isInside(new Location(w, x, 64, z))) return null;
        int minY = getConfig().getInt("random.min-y", 32), maxY = Math.min(getConfig().getInt("random.max-y", 118), 125);
        for (int y = maxY; y >= minY; y--) {
            Block feet = w.getBlockAt(x, y, z);
            if (!feet.getType().isAir()) continue;
            if (!w.getBlockAt(x, y + 1, z).getType().isAir()) continue;
            Block ground = w.getBlockAt(x, y - 1, z);
            if (!safeGround(ground.getType())) continue;
            // 옆에 용암이 바로 붙어 있으면 패스
            boolean lava = false;
            for (int dx = -1; dx <= 1 && !lava; dx++)
                for (int dz = -1; dz <= 1 && !lava; dz++)
                    for (int dy = -1; dy <= 1; dy++)
                        if (w.getBlockAt(x + dx, y + dy, z + dz).getType() == Material.LAVA) { lava = true; break; }
            if (lava) continue;
            return new Location(w, x + 0.5, y, z + 0.5, rnd.nextFloat() * 360f, 0f);
        }
        return null;
    }

    private static boolean safeGround(Material m) {
        if (!m.isSolid()) return false;
        return switch (m) {
            case BEDROCK, MAGMA_BLOCK, CAMPFIRE, SOUL_CAMPFIRE, CACTUS, POINTED_DRIPSTONE -> false;
            default -> !m.name().contains("FIRE");
        };
    }

    // ---------- 포털 막기 ----------

    private boolean blockOn() { return getConfig().getBoolean("block-portals", true); }

    private void portalMsg(Player p) {
        long now = System.currentTimeMillis();
        Long last = lastPortalMsg.get(p.getUniqueId());
        if (last != null && now - last < 5000) return;
        lastPortalMsg.put(p.getUniqueId(), now);
        p.sendMessage(msg("portal-blocked"));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreate(PortalCreateEvent e) {
        if (!blockOn() || e.getReason() != PortalCreateEvent.CreateReason.FIRE) return;
        if (e.getEntity() instanceof Player p) {
            if (p.hasPermission("poki.portal.bypass")) return;
            portalMsg(p);
        }
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent e) {
        if (!blockOn() || e.getCause() != PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) return;
        if (e.getPlayer().hasPermission("poki.portal.bypass")) return;
        e.setCancelled(true);
        portalMsg(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent e) {
        if (!blockOn() || e.getTo() == null || e.getTo().getWorld() == null) return;
        World.Environment from = e.getFrom().getWorld() == null ? null : e.getFrom().getWorld().getEnvironment();
        World.Environment to = e.getTo().getWorld().getEnvironment();
        if (from == World.Environment.NETHER || to == World.Environment.NETHER) e.setCancelled(true);
    }

    // ---------- 명령어 ----------

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("poki.portal.admin")) { s.sendMessage(c("&c운영진만 쓸 수 있어요.")); return true; }
        if (a.length >= 1 && a[0].equals("리로드")) {
            reloadConfig();
            s.sendMessage(c("&aPokiPortal 설정을 다시 읽었어요."));
            return true;
        }
        if (a.length >= 2 && a[0].equals("지급")) {
            int n = 1;
            if (a.length >= 3) try { n = Math.max(1, Math.min(2304, Integer.parseInt(a[2]))); } catch (NumberFormatException ignored) { }
            List<Player> targets = new ArrayList<>();
            if (a[1].equalsIgnoreCase("@a")) targets.addAll(Bukkit.getOnlinePlayers());
            else if (a[1].equalsIgnoreCase("@s") && s instanceof Player me) targets.add(me);
            else { Player t = Bukkit.getPlayerExact(a[1]); if (t != null) targets.add(t); }
            if (targets.isEmpty()) { s.sendMessage(c("&c" + a[1] + " — 접속 중인 플레이어가 없어요.")); return true; }
            for (Player t : targets) give(t, n);
            s.sendMessage(c("&a" + targets.size() + "명에게 네더 이동권 " + n + "장 지급"));
            return true;
        }
        s.sendMessage(c("&6/네더이동권 지급 <플레이어|@a|@s> [개수] &7· /네더이동권 리로드"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (!s.hasPermission("poki.portal.admin")) return out;
        if (a.length == 1) out.addAll(List.of("지급", "리로드"));
        else if (a.length == 2 && a[0].equals("지급")) { out.add("@a"); out.add("@s"); for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName()); }
        else if (a.length == 3 && a[0].equals("지급")) out.add("1");
        String last = a.length == 0 ? "" : a[a.length - 1];
        out.removeIf(x -> !x.startsWith(last));
        return out;
    }
}

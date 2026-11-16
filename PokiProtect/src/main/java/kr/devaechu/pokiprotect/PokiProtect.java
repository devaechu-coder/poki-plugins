package kr.devaechu.pokiprotect;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 1) 시설물 보호: protected 목록 블록은 부수기·폭발·피스톤·불로 못 없앰 (우클릭 사용은 그대로).
 *    유저가 직접 설치한 자리는 placed.yml 에 적어두고 보호하지 않음 → 월드에 원래 있던 것만 보호
 * 2) town-only 목록 블록(포켓스낵)은 Towny 마을 땅에서만 설치 가능
 * 야생 상자는 건드리지 않음 → 그대로 파밍 가능
 */
public final class PokiProtect extends JavaPlugin implements Listener {
    private final Set<String> prot = new HashSet<>(), townOnly = new HashSet<>();
    private final Set<String> placed = new HashSet<>();
    private File placedFile;
    private Method getTownAt;
    private Object townyApi;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        readConfig();
        placedFile = new File(getDataFolder(), "placed.yml");
        placed.addAll(YamlConfiguration.loadConfiguration(placedFile).getStringList("placed"));
        findTowny();
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    private void readConfig() {
        reloadConfig();
        prot.clear(); townOnly.clear();
        for (String s : getConfig().getStringList("protected")) prot.add(s.toLowerCase(Locale.ROOT));
        for (String s : getConfig().getStringList("town-only")) townOnly.add(s.toLowerCase(Locale.ROOT));
    }

    private void findTowny() {
        Plugin pl = Bukkit.getPluginManager().getPlugin("Towny");
        if (pl == null || !pl.isEnabled()) { getLogger().warning("Towny 없음 → 마을 전용 블록은 운영진만 설치 가능"); return; }
        try {
            Class<?> c = Class.forName("com.palmergames.bukkit.towny.TownyAPI", true, pl.getClass().getClassLoader());
            townyApi = c.getMethod("getInstance").invoke(null);
            getTownAt = c.getMethod("getTown", Location.class);
        } catch (ReflectiveOperationException | RuntimeException e) {
            getLogger().warning("Towny API 를 못 읽음: " + e);
        }
    }

    private boolean inTown(Location l) {
        if (getTownAt == null) return false;
        try { return getTownAt.invoke(townyApi, l) != null; } catch (ReflectiveOperationException | RuntimeException e) { return false; }
    }

    private String msg(String k) { return ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages." + k, k)); }

    private static String id(Block b) {
        try { return b.getType().getKey().toString().toLowerCase(Locale.ROOT); } catch (RuntimeException e) { return b.getType().name().toLowerCase(Locale.ROOT); }
    }

    private static boolean in(Set<String> set, Block b) {
        if (set.contains(id(b))) return true;
        String n = b.getType().name().toLowerCase(Locale.ROOT); // Arclight 모드 블록 이름 (cobblemon_pc) 도 맞춰 봄
        for (String s : set) if (n.equals(s.replace(':', '_'))) return true;
        return false;
    }

    private static String key(Block b) { return b.getWorld().getName() + "," + b.getX() + "," + b.getY() + "," + b.getZ(); }

    /** 유저가 설치한 자리인지 (PC 처럼 2칸짜리는 위·아래 칸도 봄) */
    private boolean placedByPlayer(Block b) {
        if (placed.contains(key(b))) return true;
        for (BlockFace f : new BlockFace[]{BlockFace.DOWN, BlockFace.UP}) {
            Block o = b.getRelative(f);
            if (o.getType() == b.getType() && placed.contains(key(o))) return true;
        }
        return false;
    }

    /** 보호 대상: 목록에 있고 유저가 설치한 게 아님 */
    private boolean guarded(Block b) { return in(prot, b) && !placedByPlayer(b); }

    private void savePlaced() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("placed", List.copyOf(placed));
        try { y.save(placedFile); } catch (IOException e) { getLogger().warning("placed.yml 저장 실패: " + e); }
    }

    // ---------- 설치 ----------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        Player p = e.getPlayer();
        if (in(townOnly, b) && !p.hasPermission("poki.protect.bypass") && !inTown(b.getLocation())) {
            e.setCancelled(true);
            p.sendMessage(msg(getTownAt == null ? "no-towny" : "town-only"));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlaced(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        if (in(prot, b) && placed.add(key(b))) savePlaced();
    }

    // ---------- 부수기 ----------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (!in(prot, b)) return;
        if (guarded(b) && !e.getPlayer().hasPermission("poki.protect.bypass")) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(msg("protected"));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBroken(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (!in(prot, b)) return;
        boolean ch = placed.remove(key(b));
        for (BlockFace f : new BlockFace[]{BlockFace.DOWN, BlockFace.UP}) ch |= placed.remove(key(b.getRelative(f)));
        if (ch) savePlaced();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) { e.blockList().removeIf(this::guarded); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { e.blockList().removeIf(this::guarded); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonOut(BlockPistonExtendEvent e) { if (e.getBlocks().stream().anyMatch(this::guarded)) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonIn(BlockPistonRetractEvent e) { if (e.getBlocks().stream().anyMatch(this::guarded)) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) { if (guarded(e.getBlock())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent e) { if (guarded(e.getBlock())) e.setCancelled(true); } // 엔더맨·위더 등

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (!s.hasPermission("poki.protect.bypass")) return true;
        if (a.length > 0 && a[0].equals("리로드")) {
            readConfig();
            s.sendMessage(ChatColor.GREEN + "PokiProtect 설정을 다시 읽었어요");
            return true;
        }
        s.sendMessage(ChatColor.GOLD + "보호 시설물: " + ChatColor.WHITE + String.join(", ", prot));
        s.sendMessage(ChatColor.GOLD + "마을 전용: " + ChatColor.WHITE + String.join(", ", townOnly));
        s.sendMessage(ChatColor.GRAY + "유저가 설치한 시설물 " + placed.size() + "개 (보호 안 함) · /시설보호 리로드");
        return true;
    }
}

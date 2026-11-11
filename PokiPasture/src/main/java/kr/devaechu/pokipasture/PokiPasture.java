package kr.devaechu.pokipasture;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * 목장 블록(코블몬 cobblemon:pasture) 설치 제한.
 *  - 본인이 주민인 마을(Towny) 땅에만 설치 가능
 *  - 마을 하나에 limit 개까지 (기본 2)
 * 마을별로 설치한 자리를 data.yml 에 적어두고, 셀 때마다 그 자리에 아직 목장 블록이 있는지 확인해서
 * 부서진 건 빼고 셈 (부수기·폭발·피스톤을 따로 안 잡아도 개수가 맞음).
 * 이 플러그인을 넣기 전에 설치된 목장 블록은 기록이 없어서 세지 않음.
 */
public final class PokiPasture extends JavaPlugin implements Listener {
    private File dataFile;
    private YamlConfiguration data;
    private Towny towny;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        towny = Towny.find();
        if (towny == null) getLogger().warning("Towny 를 못 찾음 → 목장 블록 설치가 전부 막힘 (운영진 제외)");
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    private String msg(String key) {
        return ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages." + key, key));
    }

    private boolean isPasture(Block b) {
        String id = getConfig().getString("block", "cobblemon:pasture").toLowerCase(Locale.ROOT);
        try {
            if (b.getType().getKey().toString().equalsIgnoreCase(id)) return true;
        } catch (RuntimeException ignored) { }
        return b.getType().name().equalsIgnoreCase(id.replace(':', '_'));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        if (!isPasture(b)) return;
        Player p = e.getPlayer();
        boolean bypass = p.hasPermission("poki.pasture.bypass");
        if (towny == null) {
            if (!bypass) { e.setCancelled(true); p.sendMessage(msg("no-towny")); }
            return;
        }
        String town = towny.townAt(b.getLocation());
        String mine = towny.townOf(p);
        if (!bypass && (town == null || !town.equals(mine))) {
            e.setCancelled(true);
            p.sendMessage(msg("not-town"));
            return;
        }
        if (town == null) return; // 운영진이 야생에 설치: 세지 않음
        int limit = getConfig().getInt("limit", 2);
        List<String> list = alive(town);
        if (!bypass && list.size() >= limit) {
            e.setCancelled(true);
            p.sendMessage(msg("full").replace("{count}", String.valueOf(list.size())).replace("{limit}", String.valueOf(limit)));
            return;
        }
        list.add(key(b.getLocation()));
        data.set("towns." + town, list);
        save();
        p.sendMessage(msg("placed").replace("{count}", String.valueOf(list.size())).replace("{limit}", String.valueOf(limit)));
    }

    /** 기록된 자리 중 아직 목장 블록이 있는 것만 (없어진 건 지움) */
    private List<String> alive(String town) {
        List<String> list = new ArrayList<>(data.getStringList("towns." + town));
        boolean changed = false;
        for (Iterator<String> it = list.iterator(); it.hasNext(); ) {
            Location l = parse(it.next());
            if (l == null || !isPasture(l.getBlock())) { it.remove(); changed = true; }
        }
        if (changed) { data.set("towns." + town, list); save(); }
        return list;
    }

    private static String key(Location l) {
        return l.getWorld().getName() + "," + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
    }

    private static Location parse(String s) {
        String[] a = s.split(",");
        if (a.length != 4) return null;
        World w = Bukkit.getWorld(a[0]);
        if (w == null) return null;
        try {
            return new Location(w, Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void save() {
        try { data.save(dataFile); } catch (IOException e) { getLogger().warning("data.yml 저장 실패: " + e); }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equals("reload") && sender.hasPermission("poki.pasture.bypass")) {
            reloadConfig();
            sender.sendMessage(ChatColor.GREEN + "PokiPasture 설정을 다시 읽었어요");
            return true;
        }
        if (!(sender instanceof Player p)) { sender.sendMessage("게임 안에서 써 주세요"); return true; }
        String town = towny == null ? null : towny.townOf(p);
        if (town == null) { p.sendMessage(ChatColor.GRAY + "마을에 들어가 있지 않아요"); return true; }
        int limit = getConfig().getInt("limit", 2);
        List<String> list = alive(town);
        p.sendMessage(ChatColor.GOLD + "우리 마을 목장 블록 " + list.size() + "/" + limit);
        for (String s : list) p.sendMessage(ChatColor.GRAY + " · " + s.replace(",", " "));
        return true;
    }

    /** Towny 를 이름으로 불러 씀 (빌드에 Towny 가 필요 없게). 마을은 UUID 문자열로 구분 */
    private static final class Towny {
        private final Object api;
        private final Method getTownAt, getResident;

        private Towny(Object api, Method getTownAt, Method getResident) {
            this.api = api; this.getTownAt = getTownAt; this.getResident = getResident;
        }

        static Towny find() {
            Plugin pl = Bukkit.getPluginManager().getPlugin("Towny");
            if (pl == null || !pl.isEnabled()) return null;
            try {
                Class<?> c = Class.forName("com.palmergames.bukkit.towny.TownyAPI", true, pl.getClass().getClassLoader());
                Object api = c.getMethod("getInstance").invoke(null);
                return new Towny(api, c.getMethod("getTown", Location.class), c.getMethod("getResident", Player.class));
            } catch (ReflectiveOperationException | RuntimeException e) {
                Bukkit.getLogger().warning("[PokiPasture] Towny API 를 못 읽음: " + e);
                return null;
            }
        }

        private static String id(Object town) throws ReflectiveOperationException {
            return town == null ? null : String.valueOf(town.getClass().getMethod("getUUID").invoke(town));
        }

        String townAt(Location l) {
            try { return id(getTownAt.invoke(api, l)); } catch (ReflectiveOperationException | RuntimeException e) { return null; }
        }

        String townOf(Player p) {
            try {
                Object r = getResident.invoke(api, p);
                if (r == null) return null;
                return id(r.getClass().getMethod("getTownOrNull").invoke(r));
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
    }
}

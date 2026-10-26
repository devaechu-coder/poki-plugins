package kr.devaechu.pokiui;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;

/**
 * 다른 플러그인이 코드에 박아 둔 상자 제목을, 열린 직후 리소스팩 그림 글자 제목으로 바꿔준다.
 * (예: SimpleShopJS 의 "상점: 코블버스" → 코블버스 헤더 + 보라 배경)
 * config.yml 의 titles: 원래 제목(색 코드 빼고) → 새 제목
 */
public final class PokiUI extends JavaPlugin implements Listener {

    private final Map<String, String> titles = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        load();
        getServer().getPluginManager().registerEvents(this, this);
    }

    private void load() {
        reloadConfig();
        titles.clear();
        ConfigurationSection sec = getConfig().getConfigurationSection("titles");
        if (sec != null) {
            for (String k : sec.getKeys(false)) {
                String v = sec.getString(k);
                if (v != null && !v.isEmpty()) titles.put(k, ChatColor.translateAlternateColorCodes('&', v));
            }
        }
        getLogger().info("제목 바꾸기 " + titles.size() + "개: " + String.join(", ", titles.keySet()));
    }

    private static String strip(String s) {
        String t = ChatColor.stripColor(s);
        return t == null ? "" : t.trim();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        InventoryView view = e.getView();
        String now = view.getTitle();
        String want = titles.get(strip(now));
        if (want == null || want.equals(now)) return;
        Inventory top = view.getTopInventory();
        // 열리는 중에는 못 바꿔서 다음 틱에 (같은 창이 아직 열려 있을 때만)
        Bukkit.getScheduler().runTask(this, () -> {
            if (!p.isOnline()) return;
            InventoryView cur = p.getOpenInventory();
            if (cur.getTopInventory() != top) return;
            try {
                cur.setTitle(want);
            } catch (Throwable t) {
                getLogger().warning("제목을 못 바꿈 (" + strip(now) + "): " + t);
            }
        });
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length > 0 && (args[0].equals("리로드") || args[0].equalsIgnoreCase("reload"))) {
            load();
            sender.sendMessage(ChatColor.LIGHT_PURPLE + "[PokiUI] " + ChatColor.WHITE + "다시 불러왔어요. 제목 " + titles.size() + "개");
            return true;
        }
        if (args.length > 0 && (args[0].equals("제목") || args[0].equalsIgnoreCase("title")) && sender instanceof Player p) {
            sender.sendMessage(ChatColor.LIGHT_PURPLE + "[PokiUI] " + ChatColor.WHITE + "지금 연 창 제목: " + ChatColor.YELLOW + strip(p.getOpenInventory().getTitle()));
            return true;
        }
        sender.sendMessage("/" + label + " 리로드  — config.yml 다시 읽기");
        return true;
    }
}

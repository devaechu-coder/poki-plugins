package kr.devaechu.pokiui;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * 포키 롤리팝(빵 + custom_model_data 1001)은 배가 꽉 찼으면 못 먹게.
 * 아이템 자체는 안 건드리고 먹는 순간만 막아서, 이미 나눠준 롤리팝(상자·배낭 안 포함)에도 다 적용됨.
 */
final class LollipopGuard implements Listener {

    static final int LOLLIPOP_CMD = 1001;

    static boolean isLollipop(ItemStack it) {
        if (it == null || it.getType() != Material.BREAD || !it.hasItemMeta()) return false;
        ItemMeta m = it.getItemMeta();
        return m.hasCustomModelData() && m.getCustomModelData() == LOLLIPOP_CMD;
    }

    private static boolean full(Player p) {
        return p.getFoodLevel() >= 20;
    }

    private static void tell(Player p) {
        p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(ChatColor.LIGHT_PURPLE + "배불러서 롤리팝을 못 먹어요"));
    }

    /** 먹기 시작부터 막기 */
    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (!isLollipop(e.getItem()) || !full(p)) return;
        e.setUseItemInHand(Event.Result.DENY);
        tell(p);
    }

    /** 혹시 다 먹었을 때도 한 번 더 막기 (아이템은 그대로 남음) */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        if (!isLollipop(e.getItem()) || !full(e.getPlayer())) return;
        e.setCancelled(true);
        tell(e.getPlayer());
        e.getPlayer().updateInventory();
    }
}

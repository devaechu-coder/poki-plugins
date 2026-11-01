package kr.devaechu.pokidonate;

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
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.FoodComponent;

/**
 * 포키 롤리팝(빵 + custom_model_data 1001)은 배가 꽉 찼으면 못 먹게.
 *  1) 롤리팝에 붙은 "항상 먹기(can_always_eat)" 설정을 끔 → 배부르면 먹는 동작 자체가 안 나옴 (바닐라 빵처럼)
 *     접속할 때 인벤토리·엔더상자, 상자를 열 때 그 안, 손에 들고 쓸 때 고침 (이미 나눠준 롤리팝도)
 *  2) 아직 안 고쳐진 롤리팝을 먹으려 하면 서버에서 막음 (먹는 동작은 잠깐 보일 수 있음)
 */
final class LollipopGuard implements Listener {

    static final int LOLLIPOP_CMD = 1001;

    static boolean isLollipop(ItemStack it) {
        if (it == null || it.getType() != Material.BREAD || !it.hasItemMeta()) return false;
        ItemMeta m = it.getItemMeta();
        return m.hasCustomModelData() && m.getCustomModelData() == LOLLIPOP_CMD;
    }

    /** 항상 먹기 설정이 켜져 있으면 끔. 바꿨으면 true */
    static boolean fix(ItemStack it) {
        if (!isLollipop(it)) return false;
        try {
            ItemMeta m = it.getItemMeta();
            if (!m.hasFood()) return false;
            FoodComponent f = m.getFood();
            if (!f.canAlwaysEat()) return false;
            f.setCanAlwaysEat(false);
            m.setFood(f);
            it.setItemMeta(m);
            return true;
        } catch (Throwable t) {
            return false;   // 서버가 음식 설정 API 를 지원 안 하면 2)만 동작
        }
    }

    static int fixAll(Inventory inv) {
        if (inv == null) return 0;
        int n = 0;
        for (ItemStack it : inv.getContents()) if (fix(it)) n++;
        return n;
    }

    private static boolean full(Player p) {
        return p.getFoodLevel() >= 20;
    }

    private static void tell(Player p) {
        p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(ChatColor.LIGHT_PURPLE + "배불러서 롤리팝을 못 먹어요"));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (fixAll(p.getInventory()) + fixAll(p.getEnderChest()) > 0) p.updateInventory();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        fixAll(e.getInventory());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        ItemStack it = e.getItem();
        if (!isLollipop(it)) return;
        if (fix(it)) p.updateInventory();
        if (!full(p)) return;
        e.setUseItemInHand(Event.Result.DENY);
        tell(p);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        if (!isLollipop(e.getItem()) || !full(e.getPlayer())) return;
        e.setCancelled(true);
        tell(e.getPlayer());
        e.getPlayer().updateInventory();
    }
}

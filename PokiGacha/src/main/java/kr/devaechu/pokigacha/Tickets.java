package kr.devaechu.pokigacha;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 뽑기권 아이템 — 만들기·알아보기 + 거래 막기.
 * 뽑기권은 자기 인벤토리 밖으로 못 나감: 버리기·상자·거래창·액자 X, 죽어도 안 떨어지고 부활하면 돌려받음.
 */
final class Tickets implements Listener {
    private final PokiGacha plugin;
    final NamespacedKey key;
    private final Map<UUID, List<ItemStack>> keepOnDeath = new HashMap<>();

    Tickets(PokiGacha plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "pool");
    }

    ItemStack make(Pool p, int amount) {
        ItemStack it = p.ticket != null ? p.ticket.clone() : new ItemStack(Material.PAPER);
        it.setAmount(Math.max(1, amount));
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.setDisplayName(Util.color(p.display));
            List<String> lore = new ArrayList<>();
            lore.add(Util.color("&7들고 &f우클릭&7해서 뽑기"));
            lore.add(Util.color("&7확률 보기: &f/뽑기 확률 " + p.name));
            if (p.noDuplicate) lore.add(Util.color("&a이미 가진 치장은 안 나와요"));
            lore.add(Util.color("&8거래·버리기 불가"));
            m.setLore(lore);
            m.getPersistentDataContainer().set(key, PersistentDataType.STRING, p.name);
            it.setItemMeta(m);
        }
        return it;
    }

    /** 뽑기권이면 뽑기 종류 이름, 아니면 null */
    String poolOf(ItemStack it) {
        if (it == null || it.getType().isAir() || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    boolean is(ItemStack it) { return poolOf(it) != null; }

    private void deny(Player p) {
        p.sendMessage(Util.color(PokiGacha.PREFIX + "&c뽑기권은 거래하거나 옮길 수 없어요."));
    }

    // ---------- 사용 ----------

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        String pool = poolOf(it);
        if (pool == null) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) {
            e.getPlayer().sendMessage(Util.color(PokiGacha.PREFIX + "&7뽑기권은 &f주 손&7에 들고 우클릭해 주세요."));
            return;
        }
        plugin.roll(e.getPlayer(), pool);
    }

    // ---------- 거래 막기 ----------

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (is(e.getItemDrop().getItemStack())) {
            e.setCancelled(true);
            deny(e.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getView().getTopInventory().getHolder() instanceof Gui.Holder) return; // 우리 화면은 따로 처리
        InventoryType top = e.getView().getTopInventory().getType();
        if (top == InventoryType.CRAFTING || top == InventoryType.CREATIVE || top == InventoryType.PLAYER) return; // 내 인벤토리만 열린 상태
        boolean ticket = is(e.getCursor()) || is(e.getCurrentItem());
        if (!ticket && e.getClick() == ClickType.NUMBER_KEY && e.getHotbarButton() >= 0) ticket = is(p.getInventory().getItem(e.getHotbarButton()));
        if (!ticket && e.getClick() == ClickType.SWAP_OFFHAND) ticket = is(p.getInventory().getItemInOffHand());
        if (!ticket) return;
        int topSize = e.getView().getTopInventory().getSize();
        boolean intoTop = e.getRawSlot() < topSize
                || e.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY
                || e.getAction() == InventoryAction.COLLECT_TO_CURSOR;
        if (intoTop) {
            e.setCancelled(true);
            deny(p);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (!is(e.getOldCursor())) return;
        if (e.getView().getTopInventory().getHolder() instanceof Gui.Holder) return;
        InventoryType top = e.getView().getTopInventory().getType();
        if (top == InventoryType.CRAFTING || top == InventoryType.CREATIVE || top == InventoryType.PLAYER) return;
        int topSize = e.getView().getTopInventory().getSize();
        for (int s : e.getRawSlots()) {
            if (s < topSize) {
                e.setCancelled(true);
                if (e.getWhoClicked() instanceof Player p) deny(p);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntity(PlayerInteractEntityEvent e) {
        ItemStack it = e.getHand() == EquipmentSlot.HAND ? e.getPlayer().getInventory().getItemInMainHand() : e.getPlayer().getInventory().getItemInOffHand();
        if (is(it)) {
            e.setCancelled(true); // 액자·갑옷 거치대·주민 거래 등
            plugin.roll(e.getPlayer(), poolOf(it));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        List<ItemStack> kept = new ArrayList<>();
        for (Iterator<ItemStack> it = e.getDrops().iterator(); it.hasNext(); ) {
            ItemStack s = it.next();
            if (is(s)) { kept.add(s.clone()); it.remove(); }
        }
        if (!kept.isEmpty()) keepOnDeath.merge(e.getEntity().getUniqueId(), kept, (a, b) -> { a.addAll(b); return a; });
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        List<ItemStack> kept = keepOnDeath.remove(e.getPlayer().getUniqueId());
        if (kept == null) return;
        Player p = e.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (ItemStack left : p.getInventory().addItem(kept.toArray(new ItemStack[0])).values()) {
                String pool = poolOf(left);
                if (pool != null) plugin.addPending(p.getUniqueId(), pool, left.getAmount());
            }
            p.sendMessage(Util.color(PokiGacha.PREFIX + "&7뽑기권을 돌려받았어요."));
        });
    }
}

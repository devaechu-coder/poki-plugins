package kr.devaechu.pokiwardrobe;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;

/**
 * 모자 치장 — 모자 아이템을 머리 칸에 씌운다.
 *
 * 아이템 유실을 막는 규칙:
 * - 쓰고 있던 투구는 인벤토리로 옮긴다. 자리가 없으면 착용을 거부한다 (아무것도 안 버림).
 * - 모자 아이템은 표시가 붙어 있어서(PDC) 집기·옮기기·버리기·드롭이 막힌다.
 * - 머리 칸이 아닌 곳에서 발견된 모자 아이템은 지운다 (복사 방지). 모자 자체는 데이터로 남아서 다시 씌워진다.
 * - 나갈 때·플러그인 꺼질 때 머리에서 벗기고, 들어올 때 다시 씌운다 → 플러그인을 빼도 모자 아이템이 남지 않음.
 */
final class Hats implements Listener {

    private final PokiWardrobe plugin;
    final NamespacedKey key;

    Hats(PokiWardrobe plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "hat");
    }

    // ---------- 아이템 ----------

    boolean isHat(ItemStack it) {
        if (it == null || it.getType() == Material.AIR || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.STRING);
    }

    ItemStack makeItem(Cosmetic c, boolean forHead) {
        ItemStack it = new ItemStack(c.material());
        ItemMeta m = it.getItemMeta();
        if (m == null) return it;
        m.setDisplayName(Text.color(c.name()));
        if (c.model() > 0) m.setCustomModelData(c.model());
        if (forHead) {
            m.getPersistentDataContainer().set(key, PersistentDataType.STRING, c.id());
            m.setLore(Text.color(java.util.List.of("&7치장 모자 · /치장 에서 벗기")));
            // 투구 아이템을 임시로 쓰더라도 방어력은 0
            if (c.material().name().endsWith("_HELMET")) {
                m.addAttributeModifier(Attribute.GENERIC_ARMOR, new AttributeModifier(
                        new NamespacedKey(plugin, "hat_armor"), 0, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.HEAD));
                m.addAttributeModifier(Attribute.GENERIC_ARMOR_TOUGHNESS, new AttributeModifier(
                        new NamespacedKey(plugin, "hat_toughness"), 0, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.HEAD));
            }
        }
        it.setItemMeta(m);
        return it;
    }

    // ---------- 씌우기 / 벗기기 ----------

    /** 모자를 씌운다. 성공하면 null, 실패하면 이유 */
    String wear(Player p, Cosmetic c) {
        PlayerInventory inv = p.getInventory();
        ItemStack head = inv.getHelmet();
        if (head != null && head.getType() != Material.AIR && !isHat(head)) {
            // 쓰고 있던 투구 → 인벤토리로. 자리 없으면 아무것도 안 하고 거부
            if (inv.firstEmpty() == -1) return "인벤토리가 꽉 차서 쓰고 있는 투구를 옮길 수 없어요. 한 칸 비우고 다시 해주세요.";
            Map<Integer, ItemStack> left = inv.addItem(head.clone());
            if (!left.isEmpty()) {
                for (ItemStack l : left.values()) inv.removeItem(l); // 혹시 일부만 들어갔으면 되돌림
                return "인벤토리가 꽉 차서 쓰고 있는 투구를 옮길 수 없어요.";
            }
            p.sendMessage(Text.color("&7쓰고 있던 투구는 인벤토리로 옮겼어요."));
        }
        inv.setHelmet(makeItem(c, true));
        return null;
    }

    void takeOff(Player p) {
        ItemStack head = p.getInventory().getHelmet();
        if (isHat(head)) p.getInventory().setHelmet(null);
    }

    /** 데이터대로 머리 칸을 맞춘다 (접속·부활·점검 때) */
    void sync(Player p) {
        Store.Data d = plugin.store().get(p.getUniqueId());
        String id = d == null ? null : d.worn.get(Cosmetic.Category.HAT);
        Cosmetic c = id == null ? null : plugin.cosmetic(id);
        ItemStack head = p.getInventory().getHelmet();
        if (c == null) {
            if (isHat(head)) p.getInventory().setHelmet(null);
            return;
        }
        if (isHat(head) && c.id().equals(head.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING))) {
            // 모델 번호나 이름이 바뀌었을 수 있으니 같은 걸로 다시 씌움
            ItemStack fresh = makeItem(c, true);
            if (!fresh.isSimilar(head)) p.getInventory().setHelmet(fresh);
            return;
        }
        if (head == null || head.getType() == Material.AIR || isHat(head)) {
            p.getInventory().setHelmet(makeItem(c, true));
        }
        // 머리에 진짜 투구가 있으면 건드리지 않음 (다음에 옷장에서 다시 쓰면 옮겨짐)
    }

    /** 머리 칸이 아닌 곳의 모자 아이템 지우기 */
    void sweep(Player p) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] storage = inv.getStorageContents();
        boolean changed = false;
        for (int i = 0; i < storage.length; i++) {
            if (isHat(storage[i])) { storage[i] = null; changed = true; }
        }
        if (changed) inv.setStorageContents(storage);
        if (isHat(inv.getItemInOffHand())) inv.setItemInOffHand(null);
        if (isHat(p.getItemOnCursor())) p.setItemOnCursor(null);
        ItemStack[] armor = inv.getArmorContents(); // 0 신발 1 바지 2 갑옷 3 머리
        boolean ac = false;
        for (int i = 0; i < 3; i++) if (isHat(armor[i])) { armor[i] = null; ac = true; }
        if (ac) inv.setArmorContents(armor);
    }

    // ---------- 막기 ----------

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        boolean hat = isHat(e.getCurrentItem()) || isHat(e.getCursor());
        if (!hat && e.getHotbarButton() >= 0 && e.getWhoClicked() instanceof Player p)
            hat = isHat(p.getInventory().getItem(e.getHotbarButton()));
        if (!hat && e.getClick() == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND && e.getWhoClicked() instanceof Player p)
            hat = isHat(p.getInventory().getItemInOffHand());
        if (hat) {
            e.setCancelled(true);
            if (e.getWhoClicked() instanceof Player p) p.sendMessage(Text.color("&7치장 모자는 &f/치장 &7에서 벗을 수 있어요."));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (isHat(e.getOldCursor()) || isHat(e.getCursor())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (isHat(e.getItemDrop().getItemStack())) {
            e.getItemDrop().remove();
            Bukkit.getScheduler().runTask(plugin, () -> sync(e.getPlayer()));
        }
    }

    /** 모자를 쓴 채로 투구를 우클릭하면 마크가 서로 바꿔 끼워서 모자가 손으로 옴 → 막음 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onEquipClick(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack hand = e.getItem();
        if (hand == null || !hand.getType().name().endsWith("_HELMET")) return;
        if (!isHat(e.getPlayer().getInventory().getHelmet())) return;
        e.setCancelled(true);
        e.getPlayer().sendMessage(Text.color("&7치장 모자를 쓰고 있어요. 투구를 쓰려면 &f/치장 &7에서 모자를 먼저 벗어주세요."));
    }

    /** 죽어도 모자는 안 떨어짐 — 부활하면 다시 씌움 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        e.getDrops().removeIf(this::isHat);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) sync(p); }, 2L);
    }

    /** 상자 등에 어떻게든 들어간 모자 아이템 정리 */
    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (top.getHolder() instanceof Menu) return;
        ItemStack[] c = top.getContents();
        boolean changed = false;
        for (int i = 0; i < c.length; i++) if (isHat(c[i])) { c[i] = null; changed = true; }
        if (changed) top.setContents(c);
        HumanEntity h = e.getPlayer();
        if (h instanceof Player p) sweep(p);
    }

    /** 점검용: 지금 머리에 쓴 모자 id */
    String hatOnHead(Player p) {
        ItemStack head = p.getInventory().getHelmet();
        return isHat(head) ? head.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING) : null;
    }
}

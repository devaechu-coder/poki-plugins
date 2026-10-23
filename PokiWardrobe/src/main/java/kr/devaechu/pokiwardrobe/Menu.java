package kr.devaechu.pokiwardrobe;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 치장 옷장 (54칸 상자 화면).
 *  0줄: 카테고리 탭 (모자 / 파티클 / 의상-준비 중) + 파티클 숨기기
 *  1~4줄: 치장 36칸 (가진 건 착용/해제, 없는 건 회색 잠금 + 얻는 방법)
 *  5줄: 이전 쪽 · 벗기 · 다음 쪽
 */
final class Menu implements InventoryHolder {

    static final int PER_PAGE = 36;
    private static final int TAB_HAT = 1, TAB_PARTICLE = 3, TAB_OUTFIT = 5, TAB_HIDE = 7;
    private static final int PREV = 45, INFO = 47, OFF = 49, NEXT = 53;

    final Cosmetic.Category category;
    final int page;
    private final Inventory inv;
    private final Map<Integer, Cosmetic> slots = new HashMap<>();

    private Menu(PokiWardrobe plugin, Player p, Cosmetic.Category category, int page) {
        this.category = category;
        List<Cosmetic> list = plugin.cosmetics(category);
        int pages = Math.max(1, (list.size() + PER_PAGE - 1) / PER_PAGE);
        this.page = Math.max(0, Math.min(page, pages - 1));
        this.inv = Bukkit.createInventory(this, 54, Text.color("&8치장 옷장 · " + category.label + (pages > 1 ? " (" + (this.page + 1) + "/" + pages + ")" : "")));

        Store.Data d = plugin.store().get(p.getUniqueId());
        String worn = d == null ? null : d.worn.get(category);

        ItemStack line = icon(Material.GRAY_STAINED_GLASS_PANE, " ", List.of(), false);
        for (int i = 0; i < 9; i++) inv.setItem(i, line);
        for (int i = 45; i < 54; i++) inv.setItem(i, line);
        inv.setItem(TAB_HAT, tab(Material.LEATHER_HELMET, "모자", category == Cosmetic.Category.HAT));
        inv.setItem(TAB_PARTICLE, tab(Material.BLAZE_POWDER, "파티클", category == Cosmetic.Category.PARTICLE));
        inv.setItem(TAB_OUTFIT, icon(Material.BARRIER, "&7의상", List.of("&8준비 중이에요"), false));
        boolean hide = d != null && d.hideParticles;
        inv.setItem(TAB_HIDE, icon(hide ? Material.ENDER_EYE : Material.ENDER_PEARL,
                hide ? "&e다른 사람 파티클: &c숨김" : "&e다른 사람 파티클: &a보임",
                List.of("&7눌러서 " + (hide ? "다시 보기" : "숨기기"), "&8내 파티클은 계속 보여요"), false));

        int from = this.page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && from + i < list.size(); i++) {
            Cosmetic c = list.get(from + i);
            boolean owned = d != null && d.owned.contains(c.id());
            boolean on = c.id().equals(worn);
            int slot = 9 + i;
            slots.put(slot, c);
            List<String> lore = new ArrayList<>(c.description());
            if (!lore.isEmpty()) lore.add("");
            ItemStack it;
            if (owned) {
                it = c.category() == Cosmetic.Category.HAT ? plugin.hats().makeItem(c, false) : new ItemStack(c.material());
                lore.add(on ? "&a● 착용 중 &7(눌러서 벗기)" : "&e눌러서 착용");
                it = decorate(it, c.name(), lore, on);
            } else {
                lore.add("&8잠김");
                if (c.unlock() != null && !c.unlock().isBlank()) lore.add("&7얻는 방법: &f" + c.unlock());
                it = icon(Material.GRAY_DYE, "&7" + stripColor(c.name()), lore, false);
            }
            inv.setItem(slot, it);
        }
        if (list.isEmpty()) inv.setItem(22, icon(Material.PAPER, "&7아직 " + category.label + " 치장이 없어요", List.of(), false));

        if (this.page > 0) inv.setItem(PREV, icon(Material.ARROW, "&f이전 쪽", List.of(), false));
        if (this.page < pages - 1) inv.setItem(NEXT, icon(Material.ARROW, "&f다음 쪽", List.of(), false));
        inv.setItem(INFO, icon(Material.BOOK, "&f" + category.label + " 치장",
                List.of("&7가진 것 " + countOwned(list, d) + " / " + list.size(), "&7카테고리마다 하나씩 착용할 수 있어요"), false));
        if (worn != null) inv.setItem(OFF, icon(Material.RED_STAINED_GLASS_PANE, "&c" + category.label + " 벗기", List.of(), false));
    }

    static void open(PokiWardrobe plugin, Player p, Cosmetic.Category category, int page) {
        p.openInventory(new Menu(plugin, p, category, page).inv);
    }

    @Override
    public Inventory getInventory() {
        return inv;
    }

    private static int countOwned(List<Cosmetic> list, Store.Data d) {
        if (d == null) return 0;
        int n = 0;
        for (Cosmetic c : list) if (d.owned.contains(c.id())) n++;
        return n;
    }

    private static String stripColor(String s) {
        return org.bukkit.ChatColor.stripColor(Text.color(s));
    }

    private static ItemStack tab(Material m, String name, boolean active) {
        return icon(m, (active ? "&a&l" : "&f") + name, List.of(active ? "&7보는 중" : "&7눌러서 보기"), active);
    }

    static ItemStack icon(Material m, String name, List<String> lore, boolean glow) {
        return decorate(new ItemStack(m), name, lore, glow);
    }

    private static ItemStack decorate(ItemStack it, String name, List<String> lore, boolean glow) {
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return it;
        meta.setDisplayName(Text.color(name));
        meta.setLore(Text.color(lore));
        meta.addItemFlags(ItemFlag.values());
        if (glow) meta.setEnchantmentGlintOverride(true);
        it.setItemMeta(meta);
        return it;
    }

    // ---------- 클릭 ----------

    static final class Clicks implements Listener {
        private final PokiWardrobe plugin;

        Clicks(PokiWardrobe plugin) {
            this.plugin = plugin;
        }

        @EventHandler
        public void onClick(InventoryClickEvent e) {
            if (!(e.getView().getTopInventory().getHolder() instanceof Menu m)) return;
            e.setCancelled(true);
            if (!(e.getWhoClicked() instanceof Player p)) return;
            if (e.getClickedInventory() != e.getView().getTopInventory()) return;
            int slot = e.getRawSlot();
            switch (slot) {
                case TAB_HAT -> reopen(p, Cosmetic.Category.HAT, 0);
                case TAB_PARTICLE -> reopen(p, Cosmetic.Category.PARTICLE, 0);
                case TAB_HIDE -> {
                    plugin.toggleHideParticles(p);
                    reopen(p, m.category, m.page);
                }
                case PREV -> reopen(p, m.category, m.page - 1);
                case NEXT -> reopen(p, m.category, m.page + 1);
                case OFF -> {
                    plugin.takeOff(p, m.category, true);
                    reopen(p, m.category, m.page);
                }
                default -> {
                    Cosmetic c = m.slots.get(slot);
                    if (c == null) return;
                    Store.Data d = plugin.store().get(p.getUniqueId());
                    if (d == null || !d.owned.contains(c.id())) {
                        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
                        return;
                    }
                    if (c.id().equals(d.worn.get(c.category()))) plugin.takeOff(p, c.category(), true);
                    else plugin.wear(p, c);
                    reopen(p, m.category, m.page);
                }
            }
        }

        @EventHandler
        public void onDrag(InventoryDragEvent e) {
            if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
        }

        private void reopen(Player p, Cosmetic.Category c, int page) {
            Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) open(plugin, p, c, page); });
        }
    }
}

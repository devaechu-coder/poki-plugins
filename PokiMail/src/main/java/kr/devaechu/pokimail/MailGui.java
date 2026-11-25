package kr.devaechu.pokimail;

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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** 우편함 창: 45칸 우편 + 아래 줄 (이전 · 모두 받기 · 다음). 누르면 받기 */
final class MailGui implements Listener {
    private static final int PER = 45;

    static final class Holder implements InventoryHolder {
        Inventory inv;
        int page;
        final List<PokiMail.Mail> shown = new ArrayList<>();
        @Override public Inventory getInventory() { return inv; }
    }

    private final PokiMail plugin;

    MailGui(PokiMail plugin) { this.plugin = plugin; }

    void open(Player p, int page) {
        List<PokiMail.Mail> all = plugin.box(p.getUniqueId());
        int pages = Math.max(1, (all.size() + PER - 1) / PER);
        page = Math.max(0, Math.min(page, pages - 1));
        Holder h = new Holder();
        h.page = page;
        h.inv = Bukkit.createInventory(h, 54, PokiMail.color("&6우편함 &8(" + all.size() + "개" + (pages > 1 ? " · " + (page + 1) + "/" + pages : "") + ")"));
        SimpleDateFormat f = new SimpleDateFormat("MM/dd HH:mm");
        for (int i = 0; i < PER && page * PER + i < all.size(); i++) {
            PokiMail.Mail m = all.get(page * PER + i);
            h.shown.add(m);
            h.inv.setItem(i, icon(m, f));
        }
        if (all.isEmpty()) h.inv.setItem(22, button(Material.PAPER, "&7우편함이 비어 있어요", "&8운영진 보상이 오면 여기로 와요"));
        if (page > 0) h.inv.setItem(45, button(Material.ARROW, "&f이전 쪽"));
        if (!all.isEmpty()) h.inv.setItem(49, button(Material.CHEST, "&a모두 받기", "&7인벤토리에 들어가는 만큼 받아요"));
        if (page < pages - 1) h.inv.setItem(53, button(Material.ARROW, "&f다음 쪽"));
        p.openInventory(h.inv);
    }

    private static ItemStack icon(PokiMail.Mail m, SimpleDateFormat f) {
        ItemStack it;
        if (m.spec != null) {
            try { it = PokiMail.parse(m.spec); it.setAmount(Math.max(1, Math.min(m.count, it.getMaxStackSize()))); }
            catch (Exception e) { it = new ItemStack(Material.BARRIER); }
        } else it = new ItemStack(Material.PAPER);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            if (m.cmd != null) im.setDisplayName(PokiMail.color("&e" + PokiMail.label(m)));
            List<String> lore = im.hasLore() && im.getLore() != null ? new ArrayList<>(im.getLore()) : new ArrayList<>();
            lore.add("");
            if (m.spec != null && m.count > it.getMaxStackSize()) lore.add(PokiMail.color("&f총 " + m.count + "개"));
            if (m.memo != null) lore.add(PokiMail.color("&7" + m.memo));
            lore.add(PokiMail.color("&8" + (m.from == null ? "" : m.from + " · ") + f.format(new Date(m.at))));
            lore.add(PokiMail.color("&a클릭해서 받기"));
            im.setLore(lore);
            it.setItemMeta(im);
        }
        return it;
    }

    private static ItemStack button(Material mat, String name, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.setDisplayName(PokiMail.color(name));
            List<String> l = new ArrayList<>();
            for (String s : lore) l.add(PokiMail.color(s));
            im.setLore(l);
            im.addItemFlags(ItemFlag.values());
            it.setItemMeta(im);
        }
        return it;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != h.inv) return;
        int slot = e.getRawSlot();
        if (slot == 45 && h.page > 0) { reopen(p, h.page - 1); return; }
        if (slot == 53) { reopen(p, h.page + 1); return; }
        if (slot == 49) {
            int[] r = plugin.claimAll(p);
            if (r[0] > 0) p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.2f);
            PokiMail.msg(p, r[0] == 0 && r[1] == 0 ? "&7우편함이 비어 있어요"
                    : "&a" + r[0] + "개 받았어요" + (r[1] > 0 ? " &7(" + r[1] + "개는 인벤토리가 꽉 차서 남겨둠)" : ""));
            reopen(p, h.page);
            return;
        }
        if (slot < 0 || slot >= h.shown.size()) return;
        String res = plugin.claim(p, h.shown.get(slot));
        if (res == null) p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.2f);
        else PokiMail.msg(p, "&e" + res);
        reopen(p, h.page);
    }

    /** 클릭 이벤트 안에서 바로 창을 바꾸면 꼬일 수 있어서 다음 틱에 */
    private void reopen(Player p, int page) {
        Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) open(p, page); });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    void closeAll() {
        for (Player p : Bukkit.getOnlinePlayers())
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof Holder) p.closeInventory();
    }
}

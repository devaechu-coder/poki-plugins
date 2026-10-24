package kr.devaechu.pokistats;

import kr.devaechu.pokistats.rank.Category;
import kr.devaechu.pokistats.rank.RankDb;
import kr.devaechu.pokistats.rank.Ranks;
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
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * /명예의전당 GUI
 * - 메인: 순위 15종 아이콘 (전체 1위·이번 주 1위·내 순위 미리보기)
 * - 순위: TOP 10 (1위 맨 위 가운데, 2·3위 그 아래, 4~10위 한 줄) + 내 순위 + 전체/이번 주 전환 + 뒤로
 */
final class HofGui implements Listener {

    /** 메인 화면 순위 아이콘 자리 (45칸) */
    private static final int[] MAIN_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 31};
    /** 순위 화면 1~10위 자리 (54칸) */
    private static final int[] TOP_SLOTS = {4, 12, 14, 28, 29, 30, 31, 32, 33, 34};
    private static final int BACK = 45, ME = 49, TOGGLE = 53;

    private final Ranks ranks;

    HofGui(Ranks ranks) {
        this.ranks = ranks;
    }

    /** 이 GUI 인지 구분하는 표시 */
    private static final class Menu implements InventoryHolder {
        final Category cat; // null = 메인
        final boolean weekly;
        Inventory inv;

        Menu(Category cat, boolean weekly) {
            this.cat = cat;
            this.weekly = weekly;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    void openMain(Player p) {
        Menu m = new Menu(null, false);
        Inventory inv = Bukkit.createInventory(m, 45, Display.color("&8명예의 전당"));
        m.inv = inv;
        fill(inv, Material.BLACK_STAINED_GLASS_PANE);
        Ranks.Snapshot s = ranks.snapshot();
        String me = p.getUniqueId().toString();
        Category[] cats = Category.values();
        for (int i = 0; i < cats.length && i < MAIN_SLOTS.length; i++) {
            Category c = cats[i];
            List<String> lore = new ArrayList<>();
            List<RankDb.Row> all = s.get(c, false);
            RankDb.Row first = Display.at(all, 1);
            lore.add(Display.color("&7" + (c.hasWeekly() ? "전체 " : "지금 ") + "1위 &f" + (first == null ? "-" : Display.nick(first.name()) + " &7· &a" + c.format(first.value()))));
            if (c.hasWeekly()) {
                RankDb.Row wf = Display.at(s.get(c, true), 1);
                lore.add(Display.color("&7이번 주 1위 &f" + (wf == null ? "-" : Display.nick(wf.name()) + " &7· &a" + c.format(wf.value()))));
            }
            int my = Display.indexOf(all, me);
            lore.add(Display.color("&7내 순위 &e" + (my < 0 ? "기록 없음" : my + "위 &7· &a" + c.format(all.get(my - 1).value()))));
            lore.add("");
            lore.add(Display.color("&a클릭해서 TOP 10 보기"));
            inv.setItem(MAIN_SLOTS[i], item(Display.icon(c), "&e&l" + c.title, lore));
        }
        inv.setItem(40, item(Material.BOOK, "&f명예의 전당", List.of(
                Display.color("&7이번 주는 월요일 0시(한국시간)부터"),
                Display.color("&7같은 값이면 먼저 도달한 사람이 위"),
                Display.color("&7" + updated(s)))));
        p.openInventory(inv);
    }

    void openRank(Player p, Category c, boolean weekly) {
        boolean wk = weekly && c.hasWeekly();
        Menu m = new Menu(c, wk);
        Inventory inv = Bukkit.createInventory(m, 54, Display.color("&8" + c.title + (c.hasWeekly() ? (wk ? " · 이번 주" : " · 전체") : "")));
        m.inv = inv;
        fill(inv, Material.GRAY_STAINED_GLASS_PANE);
        Ranks.Snapshot s = ranks.snapshot();
        List<RankDb.Row> rows = s.get(c, wk);
        for (int i = 0; i < TOP_SLOTS.length; i++) {
            RankDb.Row r = Display.at(rows, i + 1);
            if (r == null) {
                inv.setItem(TOP_SLOTS[i], item(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&7" + (i + 1) + "위 &8-", List.of()));
                continue;
            }
            inv.setItem(TOP_SLOTS[i], head(r, i + 1, c));
        }
        String me = p.getUniqueId().toString();
        int my = Display.indexOf(rows, me);
        ItemStack mine = my < 0
                ? head(p.getUniqueId(), "&f내 순위 &7기록 없음", List.of())
                : head(rows.get(my - 1), my, c);
        if (my > 0) {
            ItemMeta im = mine.getItemMeta();
            if (im != null) {
                im.setDisplayName(Display.color("&f내 순위 &e" + my + "위"));
                mine.setItemMeta(im);
            }
        }
        inv.setItem(ME, mine);
        inv.setItem(BACK, item(Material.ARROW, "&f뒤로", List.of()));
        if (c.hasWeekly()) {
            inv.setItem(TOGGLE, item(Material.CLOCK, wk ? "&f전체 보기" : "&f이번 주 보기",
                    List.of(Display.color("&7지금: " + (wk ? "이번 주" : "전체")))));
        }
        inv.setItem(49 - 9, item(Display.icon(c), "&e&l" + c.title, List.of(Display.color("&7" + updated(s)))));
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Menu m)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;
        int slot = e.getRawSlot();
        if (m.cat == null) {
            for (int i = 0; i < MAIN_SLOTS.length && i < Category.values().length; i++) {
                if (MAIN_SLOTS[i] == slot) {
                    click(p);
                    openRank(p, Category.values()[i], false);
                    return;
                }
            }
            return;
        }
        if (slot == BACK) {
            click(p);
            openMain(p);
        } else if (slot == TOGGLE && m.cat.hasWeekly()) {
            click(p);
            openRank(p, m.cat, !m.weekly);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    // ── 아이템 ──

    private static void click(Player p) {
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
    }

    private static String updated(Ranks.Snapshot s) {
        if (s.at() == 0) return "아직 계산 전이에요";
        long sec = Math.max(0, (System.currentTimeMillis() - s.at()) / 1000);
        return sec < 60 ? "방금 갱신" : (sec / 60) + "분 전 갱신";
    }

    private static ItemStack head(RankDb.Row r, int rank, Category c) {
        String medal = switch (rank) {
            case 1 -> "&6&l1위";
            case 2 -> "&f&l2위";
            case 3 -> "&c&l3위";
            default -> "&e" + rank + "위";
        };
        List<String> lore = new ArrayList<>();
        lore.add(Display.color("&a" + c.format(r.value())));
        if (c == Category.PVP) lore.add(Display.color("&7승률 &f" + Display.winRate(r) + " &8(" + (long) r.value() + "승 " + (long) r.extra() + "패)"));
        UUID id;
        try {
            id = UUID.fromString(r.uuid());
        } catch (IllegalArgumentException ex) {
            return item(Material.PLAYER_HEAD, medal + " &f" + Display.nick(r.name()), lore);
        }
        return head(id, medal + " &f" + Display.nick(r.name()), lore);
    }

    private static ItemStack head(UUID id, String name, List<String> lore) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        if (it.getItemMeta() instanceof SkullMeta sm) {
            try {
                sm.setOwningPlayer(Bukkit.getOfflinePlayer(id));
            } catch (Throwable ignored) {
            }
            sm.setDisplayName(Display.color(name));
            sm.setLore(lore);
            it.setItemMeta(sm);
        }
        return it;
    }

    private static ItemStack item(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.setDisplayName(Display.color(name));
            im.setLore(lore);
            im.addItemFlags(ItemFlag.values());
            it.setItemMeta(im);
        }
        return it;
    }

    private static void fill(Inventory inv, Material m) {
        ItemStack pane = item(m, " ", List.of());
        ItemStack[] all = new ItemStack[inv.getSize()];
        Arrays.fill(all, pane);
        inv.setContents(all);
    }
}

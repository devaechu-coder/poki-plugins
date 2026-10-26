package kr.devaechu.pokihelp;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * /문의함 — 54칸 상자. 리소스팩 모드면 상자 제목에 PokiTitles-RP 의 그림 글자를 넣어 배경을 그린다.
 * 0~44 문의 / 45 이전 / 47 필터 / 48 미처리 / 49 페이지 / 50 처리 완료 / 51 닫기 / 53 다음
 */
final class Gui implements Listener {

    private static final int PER_PAGE = 45;

    // 리소스팩 글자 (PokiTitles-RP README 참고): 몸통 3×4 조각 + 헤더 3조각, 조각마다 -1 칸 보정
    private static final String TITLE_INBOX = title(0xE810, 0xE830);
    private static final String TITLE_DONE = title(0xE820, 0xE840);

    private static String title(int body, int header) {
        StringBuilder sb = new StringBuilder("§f");
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 3; c++) sb.append((char) (body + r * 3 + c)).append('');
            sb.append('');
        }
        for (int c = 0; c < 3; c++) sb.append((char) (header + c)).append('');
        return sb.append('').append('').toString();
    }

    private final PokiHelp plugin;

    Gui(PokiHelp plugin) {
        this.plugin = plugin;
    }

    private static final class View implements InventoryHolder {
        Inventory inventory;
        final boolean done;
        final Ticket.Kind filter;
        final int page;
        final Map<Integer, Integer> slots = new HashMap<>();

        View(boolean done, Ticket.Kind filter, int page) {
            this.done = done;
            this.filter = filter;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    void open(Player p) {
        open(p, false, null, 0);
    }

    private void open(Player p, boolean done, Ticket.Kind filter, int wantPage) {
        if (!plugin.isStaff(p)) {
            p.sendMessage(PokiHelp.color("&c운영진만 열 수 있어요."));
            return;
        }
        boolean pack = plugin.getConfig().getBoolean("gui.resource-pack", true);
        List<Ticket> list = plugin.store().list(done, filter);
        int pages = Math.max(1, (list.size() + PER_PAGE - 1) / PER_PAGE);
        int page = Math.max(0, Math.min(wantPage, pages - 1));
        View v = new View(done, filter, page);
        String title = pack ? (done ? TITLE_DONE : TITLE_INBOX)
                : PokiHelp.color(done ? "&8문의함 · 처리 완료 " + list.size() + "건" : "&8문의함 · 미처리 " + list.size() + "건");
        v.inventory = Bukkit.createInventory(v, 54, title);

        for (int slot = 0; slot < PER_PAGE && page * PER_PAGE + slot < list.size(); slot++) {
            Ticket t = list.get(page * PER_PAGE + slot);
            v.slots.put(slot, t.id);
            List<String> lore = new ArrayList<>();
            lore.add("&7보낸 사람: &f" + t.who());
            lore.add("&7시간: &f" + PokiHelp.time(t.created));
            lore.add("&7위치: &f" + t.where());
            lore.add("");
            for (String line : wrap(t.message, 24)) lore.add("&e" + line);
            lore.add("");
            if (done) {
                lore.add("&a" + (t.doneBy.isBlank() ? "운영진" : t.doneBy) + " 님이 처리 &7(" + PokiHelp.time(t.doneAt) + ")");
                lore.add("&a좌클릭: &f이동");
                lore.add("&b우클릭: &f미처리로 되돌리기");
            } else {
                lore.add("&a좌클릭: &f이동 &7(접속 중이면 그 사람한테, 아니면 보낸 곳)");
                lore.add("&b우클릭: &f처리 완료");
            }
            lore.add("&cShift+좌클릭: &f삭제");
            v.inventory.setItem(slot, item(pack, t.kind.model, t.kind.plain,
                    t.kind.tag() + " &f문의 #" + t.id, lore));
        }

        String filterName = filter == null ? "전체" : filter.label;
        v.inventory.setItem(45, item(pack, 8305, Material.ARROW, "&f이전 페이지", List.of()));
        v.inventory.setItem(47, item(pack, 8307, Material.HOPPER, "&e종류: &f" + filterName,
                List.of("&7누를 때마다 전체 → 버그 → 신고 → 질문 → 기타")));
        v.inventory.setItem(48, item(pack, 8308, Material.CLOCK, (done ? "&7" : "&d") + "미처리 문의 보기",
                List.of("&7" + plugin.store().openCount() + "건")));
        v.inventory.setItem(49, item(pack, 8311, Material.PAPER, "&f" + (page + 1) + " / " + pages + " 페이지",
                List.of("&7" + (done ? "처리 완료" : "미처리") + " · " + filterName + " " + list.size() + "건")));
        v.inventory.setItem(50, item(pack, 8309, Material.LIME_DYE, (done ? "&d" : "&7") + "처리 완료 문의 보기", List.of()));
        v.inventory.setItem(51, item(pack, 8310, Material.BARRIER, "&c닫기", List.of()));
        v.inventory.setItem(53, item(pack, 8306, Material.ARROW, "&f다음 페이지", List.of()));
        p.openInventory(v.inventory);
    }

    private static ItemStack item(boolean pack, int model, Material plain, String name, List<String> lore) {
        ItemStack it = new ItemStack(pack ? Material.PAPER : plain);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            if (pack) m.setCustomModelData(model);
            m.setDisplayName(PokiHelp.color("&r" + name));
            List<String> l = new ArrayList<>();
            for (String s : lore) l.add(PokiHelp.color("&r" + s));
            m.setLore(l);
            it.setItemMeta(m);
        }
        return it;
    }

    /** 긴 내용을 아이템 설명 여러 줄로 (띄어쓰기에서 자름) */
    static List<String> wrap(String s, int max) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String w : s.split(" ")) {
            while (w.length() > max) {
                if (line.length() > 0) {
                    out.add(line.toString());
                    line.setLength(0);
                }
                out.add(w.substring(0, max));
                w = w.substring(max);
            }
            if (line.length() > 0 && line.length() + 1 + w.length() > max) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(w);
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof View) e.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof View v)) return;
        // 아래 내 인벤토리·숫자키·Shift 이동까지 전부 막기
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!plugin.isStaff(p)) {
            p.closeInventory();
            return;
        }
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= 54) return;
        ClickType click = e.getClick();
        Integer id = v.slots.get(slot);
        if (id != null) {
            Ticket t = plugin.store().get(id);
            if (t == null) {
                later(p, v.done, v.filter, v.page);
                return;
            }
            if (click == ClickType.SHIFT_LEFT) {
                plugin.delete(p, t);
                later(p, v.done, v.filter, v.page);
            } else if (click == ClickType.LEFT) {
                p.closeInventory();
                plugin.teleport(p, t);
            } else if (click == ClickType.RIGHT) {
                if (v.done) plugin.reopen(p, t);
                else plugin.resolve(p, t);
                later(p, v.done, v.filter, v.page);
            }
            return;
        }
        if (click != ClickType.LEFT && click != ClickType.RIGHT) return;
        switch (slot) {
            case 45 -> later(p, v.done, v.filter, v.page - 1);
            case 47 -> {
                Ticket.Kind[] ks = Ticket.Kind.values();
                Ticket.Kind next = v.filter == null ? ks[0] : (v.filter.ordinal() == ks.length - 1 ? null : ks[v.filter.ordinal() + 1]);
                later(p, v.done, next, 0);
            }
            case 48 -> later(p, false, v.filter, 0);
            case 50 -> later(p, true, v.filter, 0);
            case 51 -> p.closeInventory();
            case 53 -> later(p, v.done, v.filter, v.page + 1);
            default -> {
            }
        }
    }

    /** 클릭 이벤트 안에서 바로 다시 열면 꼬일 수 있어서 다음 틱에 */
    private void later(Player p, boolean done, Ticket.Kind filter, int page) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) open(p, done, filter, page);
        });
    }

    /** 리로드·종료 때 열려 있는 문의함 닫기 */
    void closeAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof View) p.closeInventory();
        }
    }
}

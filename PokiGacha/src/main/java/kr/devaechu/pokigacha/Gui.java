package kr.devaechu.pokigacha;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** 화면들: 편집(운영진) · 확률 보기 · 뽑기 연출 */
final class Gui implements Listener {
    static abstract class Holder implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private final PokiGacha plugin;
    private final Random rnd = new Random();
    /** 편집 중인 사본 (뽑기 이름 → 사본). 닫아도 남아서 다시 열면 이어서 */
    final Map<String, Pool> drafts = new HashMap<>();
    /** 이름 바꾸기 채팅 기다리는 운영진 → 뽑기 이름 */
    private final Map<UUID, String> renaming = new HashMap<>();
    /** 지금 뽑는 중인 사람 */
    final Map<UUID, RollHolder> rolling = new HashMap<>();

    Gui(PokiGacha plugin) { this.plugin = plugin; }

    static ItemStack button(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.setDisplayName(Util.color(name));
            List<String> l = new ArrayList<>();
            for (String s : lore) l.add(Util.color(s));
            im.setLore(l);
            im.addItemFlags(ItemFlag.values());
            it.setItemMeta(im);
        }
        return it;
    }

    /** 보상 아이템 + 설명 줄 덧붙인 표시용 사본 */
    static ItemStack shown(Pool.Reward r, int total, List<String> extra) {
        ItemStack it = r.item != null ? r.item.clone() : new ItemStack(Material.COMMAND_BLOCK);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            List<String> lore = m.hasLore() ? new ArrayList<>(m.getLore()) : new ArrayList<>();
            if (!lore.isEmpty()) lore.add("");
            double pct = total > 0 ? r.weight * 100.0 / total : 0;
            lore.add(Util.color("&7확률 &f" + String.format("%.1f", pct) + "%"));
            if (r.item == null) lore.add(Util.color("&7명령어 보상"));
            for (String s : extra) lore.add(Util.color(s));
            m.setLore(lore);
            it.setItemMeta(m);
        }
        return it;
    }

    // =====================================================================
    // 편집
    // =====================================================================

    static final class EditorHolder extends Holder {
        final Pool draft;
        boolean switching; // 이름 바꾸기 등으로 잠깐 닫는 중
        EditorHolder(Pool draft) { this.draft = draft; }
    }

    private static final int SAVE = 45, CANCEL = 46, TICKET = 47, RENAME = 48, INFO = 49, NODUP = 50, FALLBACK = 51, TEST = 52;

    void openEditor(Player p, Pool pool) {
        if (pool.editor != null && !pool.editor.equals(p.getName())) {
            p.sendMessage(Util.color(PokiGacha.PREFIX + "&c" + pool.editor + " 님이 편집 중이에요."));
            return;
        }
        Pool draft = drafts.computeIfAbsent(pool.name, k -> pool.copy());
        pool.editor = p.getName();
        EditorHolder h = new EditorHolder(draft);
        h.inv = Bukkit.createInventory(h, 54, Util.color("&8뽑기 편집 · " + pool.name));
        fillEditor(h);
        p.openInventory(h.inv);
    }

    private void fillEditor(EditorHolder h) {
        Pool d = h.draft;
        h.inv.clear();
        int total = d.totalWeight();
        for (int i = 0; i < d.rewards.size() && i < 45; i++) {
            Pool.Reward r = d.rewards.get(i);
            h.inv.setItem(i, shown(r, total, List.of("", "&eQ&7: 빼기", "&e아이템 들고 클릭&7: 그 아이템으로 바꾸기")));
        }
        for (int i = 45; i < 54; i++) h.inv.setItem(i, button(Material.GRAY_STAINED_GLASS_PANE, " "));
        h.inv.setItem(SAVE, button(Material.LIME_CONCRETE, "&a&l저장", "&7보상 " + d.rewards.size() + "개 · 전부 같은 확률"));
        h.inv.setItem(CANCEL, button(Material.RED_CONCRETE, "&c&l취소", "&7바꾼 것 버리기"));
        ItemStack t = plugin.tickets.make(d, 1);
        ItemMeta tm = t.getItemMeta();
        if (tm != null) {
            tm.setLore(List.of(Util.color("&7뽑기권 생김새"), "", Util.color("&e아이템을 들고 클릭&7: 그 모양으로"), Util.color("&e빈손으로 클릭&7: 종이로")));
            t.setItemMeta(tm);
        }
        h.inv.setItem(TICKET, t);
        h.inv.setItem(RENAME, button(Material.NAME_TAG, "&e뽑기권 이름 바꾸기", "&7지금: " + d.display, "", "&e클릭&7 → 채팅에 새 이름 (&색 코드 가능)"));
        h.inv.setItem(INFO, button(Material.BOOK, "&f사용법",
                "&e인벤토리 아이템 Shift+클릭 &7또는", "&e들고 빈칸 클릭&7 → 보상으로 추가 (복사본, 내 아이템은 그대로)",
                "&eQ&7 → 빼기  &e아이템 들고 보상 클릭&7 → 교체", "", "&7확률은 전부 똑같아요 (보상 개수로 나눔)",
                "&7저장하면 이전 설정은 백업 → &f/뽑기 되돌리기"));
        h.inv.setItem(NODUP, button(d.noDuplicate ? Material.LIME_DYE : Material.GRAY_DYE,
                "&f치장 중복 금지: " + (d.noDuplicate ? "&a켜짐" : "&7꺼짐"), "&7켜면 옷장에 이미 있는 치장은 안 나와요", "", "&e클릭&7: 켜기/끄기"));
        String fb = d.fallbackPool == null ? "&7없음" : "&f" + d.fallbackPool + " 뽑기권 " + d.fallbackCount + "장";
        h.inv.setItem(FALLBACK, button(Material.CHEST, "&f다 가졌을 때 대신: " + fb,
                "&7중복 금지일 때 뽑을 게 없으면 대신 줄 것", "", "&e클릭&7: 다른 뽑기권으로 바꾸기", "&eShift+좌/우클릭&7: 장 수 +1/-1"));
        h.inv.setItem(TEST, button(Material.COMPARATOR, "&f테스트 1000번", "&7실제 지급 없이 결과만 채팅으로"));
    }

    private void onEditorClick(InventoryClickEvent e, EditorHolder h, Player p) {
        e.setCancelled(true);
        Pool d = h.draft;
        int raw = e.getRawSlot();
        if (raw >= 54) {
            // 내 인벤토리: Shift+클릭이면 보상으로 추가, 나머지는 그냥 둠 (옮기기는 허용)
            if (e.getClick().isShiftClick()) {
                addReward(p, h, e.getCurrentItem());
            } else if (e.getAction() != InventoryAction.COLLECT_TO_CURSOR) {
                e.setCancelled(false);
            }
            return;
        }
        ItemStack cursor = e.getCursor();
        if (raw < 45) {
            if (raw >= d.rewards.size()) {
                if (cursor != null && !cursor.getType().isAir()) addReward(p, h, cursor);
                return;
            }
            Pool.Reward r = d.rewards.get(raw);
            if (e.getClick() == ClickType.DROP || e.getClick() == ClickType.CONTROL_DROP) {
                d.rewards.remove(raw);
                click(p);
            } else if (cursor != null && !cursor.getType().isAir()) {
                r.item = cursor.clone(); // 들고 클릭: 아이템 바꿔치기
                click(p);
            }
            fillEditor(h);
            return;
        }
        switch (raw) {
            case SAVE -> {
                Pool real = plugin.store.pools.get(d.name);
                d.editor = null;
                plugin.store.pools.put(d.name, d);
                plugin.store.save(true);
                drafts.remove(d.name);
                if (real != null) real.editor = null;
                h.switching = true;
                p.closeInventory();
                p.sendMessage(Util.color(PokiGacha.PREFIX + "&a저장했어요. &7(" + d.name + " · 보상 " + d.rewards.size() + "개)"));
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);
            }
            case CANCEL -> {
                drafts.remove(d.name);
                unlock(d.name);
                h.switching = true;
                p.closeInventory();
                p.sendMessage(Util.color(PokiGacha.PREFIX + "&7바꾼 걸 버렸어요."));
            }
            case TICKET -> {
                if (cursor != null && !cursor.getType().isAir()) {
                    ItemStack t = cursor.clone();
                    t.setAmount(1);
                    d.ticket = t;
                } else {
                    d.ticket = null;
                }
                click(p);
                fillEditor(h);
            }
            case RENAME -> {
                renaming.put(p.getUniqueId(), d.name);
                unlock(d.name); // 채팅 안 치고 가도 뽑기가 잠긴 채로 남지 않게
                h.switching = true;
                p.closeInventory();
                p.sendMessage(Util.color(PokiGacha.PREFIX + "&e채팅에 새 뽑기권 이름을 쳐 주세요. &7(예: &d치장 뽑기권&7 · 그만두려면 &f취소&7)"));
            }
            case NODUP -> { d.noDuplicate = !d.noDuplicate; click(p); fillEditor(h); }
            case FALLBACK -> {
                if (e.getClick() == ClickType.SHIFT_LEFT) d.fallbackCount = Math.min(64, d.fallbackCount + 1);
                else if (e.getClick() == ClickType.SHIFT_RIGHT) d.fallbackCount = Math.max(1, d.fallbackCount - 1);
                else {
                    List<String> names = new ArrayList<>();
                    names.add(null);
                    for (String n : plugin.store.pools.keySet()) if (!n.equals(d.name)) names.add(n);
                    int i = names.indexOf(d.fallbackPool);
                    d.fallbackPool = names.get((i + 1) % names.size());
                }
                click(p);
                fillEditor(h);
            }
            case TEST -> plugin.test(p, d, 1000);
            default -> { }
        }
    }

    private void addReward(Player p, EditorHolder h, ItemStack it) {
        if (it == null || it.getType().isAir()) return;
        if (plugin.tickets.is(it)) {
            p.sendMessage(Util.color(PokiGacha.PREFIX + "&c뽑기권은 보상으로 넣을 수 없어요."));
            return;
        }
        if (h.draft.rewards.size() >= 45) {
            p.sendMessage(Util.color(PokiGacha.PREFIX + "&c보상은 45개까지예요."));
            return;
        }
        Pool.Reward r = new Pool.Reward();
        r.item = it.clone();
        h.draft.rewards.add(r);
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.2f);
        fillEditor(h);
    }

    private static void click(Player p) { p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.2f); }

    private void unlock(String name) {
        Pool real = plugin.store.pools.get(name);
        if (real != null) real.editor = null;
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        String pool = renaming.remove(e.getPlayer().getUniqueId());
        if (pool == null) return;
        e.setCancelled(true);
        String msg = e.getMessage().trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Pool d = drafts.get(pool);
            Pool real = plugin.store.pools.get(pool);
            if (d == null || real == null) return;
            if (!msg.equals("취소") && !msg.isEmpty()) d.display = msg.length() > 60 ? msg.substring(0, 60) : msg;
            real.editor = null;
            openEditor(e.getPlayer(), real);
        });
    }

    // =====================================================================
    // 확률 보기 (모두)
    // =====================================================================

    static final class ViewHolder extends Holder { }

    void openView(Player p, Pool pool) {
        ViewHolder h = new ViewHolder();
        int rows = Math.max(1, Math.min(6, (pool.rewards.size() + 8) / 9));
        h.inv = Bukkit.createInventory(h, rows * 9, Util.color("&8" + ChatStrip.strip(pool.display) + " · 확률"));
        Set<String> owned = pool.noDuplicate ? Util.ownedCosmetics(p) : Set.of();
        int total = pool.totalWeight();
        for (int i = 0; i < pool.rewards.size() && i < rows * 9; i++) {
            Pool.Reward r = pool.rewards.get(i);
            String k = Util.cosmeticKey(r.item);
            List<String> extra = k != null && owned.contains(k) ? List.of("&a✔ 이미 가짐 &7(안 나와요)") : List.of();
            h.inv.setItem(i, shown(r, total, extra));
        }
        p.openInventory(h.inv);
    }

    /** 색 코드 지우기 */
    static final class ChatStrip {
        static String strip(String s) { return org.bukkit.ChatColor.stripColor(Util.color(s)); }
    }

    // =====================================================================
    // 뽑기 연출
    // =====================================================================

    static final class RollHolder extends Holder {
        final Player player;
        final Pool pool;
        final Pool.Reward result;
        final List<Pool.Reward> reel;
        int step;
        boolean done;
        BukkitTask task;

        RollHolder(Player player, Pool pool, Pool.Reward result, List<Pool.Reward> reel) {
            this.player = player; this.pool = pool; this.result = result; this.reel = reel;
        }
    }

    private static final int STEPS = 28;
    /** 흰색(글꼴 그림 색 그대로) + 왼쪽으로 8칸 + 배경 그림 — PokiCosmetics 의 assets/minecraft/font/default.json */
    private static final String BG_TITLE = "§f\ue9cf\ue9c0";

    void openRoll(Player p, Pool pool, Pool.Reward result, List<Pool.Reward> candidates) {
        List<Pool.Reward> reel = new ArrayList<>();
        for (int i = 0; i < STEPS + 9; i++) reel.add(candidates.get(rnd.nextInt(candidates.size())));
        reel.set(STEPS + 4, result); // 마지막에 가운데(13번 칸)에 결과가 오게
        RollHolder h = new RollHolder(p, pool, result, reel);
        h.inv = Bukkit.createInventory(h, 27, BG_TITLE); // 포키 모드 글꼴: 뽑기 기계 배경 그림
        rolling.put(p.getUniqueId(), h);
        p.openInventory(h.inv);
        frame(h);
        tick(h);
    }

    private void frame(RollHolder h) {
        for (int i = 0; i < 9; i++) {
            Pool.Reward r = h.reel.get(h.step + i);
            ItemStack it = r.item != null ? r.item.clone() : new ItemStack(Material.COMMAND_BLOCK);
            h.inv.setItem(9 + i, it);
        }
    }

    private void tick(RollHolder h) {
        if (h.done) return;
        if (h.step >= STEPS) { finish(h); return; }
        long delay = h.step < 14 ? 2 : h.step < 20 ? 3 : h.step < 24 ? 5 : 8;
        h.task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (h.done) return;
            h.step++;
            frame(h);
            h.player.playSound(h.player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 1.0f + h.step / 40f);
            tick(h);
        }, delay);
    }

    void finish(RollHolder h) {
        if (h.done) return;
        h.done = true;
        if (h.task != null) h.task.cancel();
        rolling.remove(h.player.getUniqueId());
        // 결과만 남기고 양옆은 비움 (배경 그림의 가운데 칸이 강조)
        for (int i = 9; i < 18; i++) if (i != 13) h.inv.setItem(i, null);
        plugin.grant(h.player, h.pool, h.result);
    }

    // =====================================================================
    // 공통 이벤트
    // =====================================================================

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (holder instanceof EditorHolder eh) { onEditorClick(e, eh, p); return; }
        e.setCancelled(true);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder)) return;
        int top = e.getView().getTopInventory().getSize();
        for (int s : e.getRawSlots()) if (s < top) { e.setCancelled(true); return; }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        Object h = e.getInventory().getHolder();
        if (h instanceof RollHolder r) {
            finish(r); // 닫아도 결과는 바로 받음
        } else if (h instanceof EditorHolder eh) {
            if (eh.switching) return;
            unlock(eh.draft.name);
            if (e.getPlayer() instanceof Player p) {
                p.sendMessage(Util.color(PokiGacha.PREFIX + "&7아직 저장 안 했어요. &f/뽑기 편집 " + eh.draft.name + "&7 로 이어서 하고 &a저장&7을 눌러 주세요."));
            }
        }
    }

    /** 서버 끌 때: 뽑는 중이던 사람 결과 지급 */
    void shutdown() {
        for (RollHolder r : new ArrayList<>(rolling.values())) finish(r);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof Holder) p.closeInventory();
        }
    }
}

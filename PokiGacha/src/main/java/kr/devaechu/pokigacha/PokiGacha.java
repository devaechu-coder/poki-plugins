package kr.devaechu.pokigacha;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * 포키 뽑기권.
 *  /뽑기권 지급 <플레이어|@a> <종류> [개수]  ← 후원 연동(콘솔)에서 씀. 접속 안 했으면 다음 접속 때 지급
 *  뽑기권 들고 우클릭 → 연출 → 보상. 보상은 전부 같은 확률
 *  /뽑기 편집 <종류>  ← 운영진이 인게임 상자 화면에서 보상 넣기·빼기
 */
public final class PokiGacha extends JavaPlugin implements Listener {
    static final String PREFIX = "&d[뽑기] &f";

    Store store;
    Tickets tickets;
    Gui gui;
    private final Random rnd = new Random();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        store = new Store(this);
        store.load();
        tickets = new Tickets(this);
        gui = new Gui(this);
        getServer().getPluginManager().registerEvents(tickets, this);
        getServer().getPluginManager().registerEvents(gui, this);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("뽑기 " + store.pools.size() + "종류: " + String.join(", ", store.pools.keySet()));
    }

    @Override
    public void onDisable() {
        if (gui != null) gui.shutdown();
    }

    private static String c(String s) { return Util.color(s); }

    // =====================================================================
    // 뽑기
    // =====================================================================

    void roll(Player p, String poolName) {
        if (gui.rolling.containsKey(p.getUniqueId())) return;
        Pool pool = store.pools.get(poolName);
        if (pool == null) {
            p.sendMessage(c(PREFIX + "&c없어진 뽑기예요. 운영진에게 문의해 주세요."));
            return;
        }
        if (pool.editor != null) {
            p.sendMessage(c(PREFIX + "&e지금 운영진이 이 뽑기를 고치는 중이에요. 잠시 후에 다시 해 주세요."));
            return;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!poolName.equals(tickets.poolOf(hand))) return;

        List<Pool.Reward> cand = candidates(p, pool);
        if (cand.isEmpty()) {
            Pool fb = pool.fallbackPool == null ? null : store.pools.get(pool.fallbackPool);
            if (pool.noDuplicate && !pool.rewards.isEmpty() && fb != null) {
                take(p);
                give(p, fb, pool.fallbackCount);
                p.sendMessage(c(PREFIX + "&a" + Gui.ChatStrip.strip(pool.display) + "의 보상을 전부 가지고 있어서 &f" + Gui.ChatStrip.strip(fb.display) + " " + pool.fallbackCount + "장&a으로 바꿔 드렸어요!"));
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f);
                log(p, pool, "(전부 보유 → " + fb.name + " 뽑기권 " + pool.fallbackCount + ")");
            } else if (pool.noDuplicate && !pool.rewards.isEmpty()) {
                p.sendMessage(c(PREFIX + "&e이 뽑기의 보상을 전부 가지고 있어요! 운영진에게 문의해 주세요."));
            } else {
                p.sendMessage(c(PREFIX + "&e아직 준비 중인 뽑기예요."));
            }
            return;
        }
        if (p.getInventory().firstEmpty() == -1) {
            p.sendMessage(c(PREFIX + "&c인벤토리를 한 칸 이상 비우고 다시 해 주세요."));
            return;
        }
        take(p);
        Pool.Reward result = pick(cand);
        gui.openRoll(p, pool, result, cand);
    }

    /** 뽑을 수 있는 보상 (중복 금지면 이미 가진 치장 빼고, 무게 0 빼고) */
    List<Pool.Reward> candidates(Player p, Pool pool) {
        Set<String> owned = pool.noDuplicate ? Util.ownedCosmetics(p) : Set.of();
        List<Pool.Reward> out = new ArrayList<>();
        for (Pool.Reward r : pool.rewards) {
            if (r.weight <= 0) continue;
            String k = Util.cosmeticKey(r.item);
            if (k != null && owned.contains(k)) continue;
            out.add(r);
        }
        return out;
    }

    Pool.Reward pick(List<Pool.Reward> cand) {
        int total = 0;
        for (Pool.Reward r : cand) total += r.weight;
        int x = rnd.nextInt(Math.max(1, total));
        for (Pool.Reward r : cand) {
            x -= r.weight;
            if (x < 0) return r;
        }
        return cand.get(cand.size() - 1);
    }

    private void take(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getAmount() <= 1) p.getInventory().setItemInMainHand(null);
        else hand.setAmount(hand.getAmount() - 1);
    }

    /** 결과 지급 (연출 끝나거나 창을 닫았을 때) */
    void grant(Player p, Pool pool, Pool.Reward r) {
        String name = r.item != null ? Util.name(r.item) : "보상";
        int amount = r.item != null ? r.item.getAmount() : 0;
        if (r.item != null) {
            for (ItemStack left : p.getInventory().addItem(r.item.clone()).values()) {
                // 인벤이 꽉 차면 우편함 (없으면 예전처럼 바닥)
                if (!Mailbox.mail(p.getUniqueId(), left, left.getAmount(), "뽑기 당첨")) p.getWorld().dropItemNaturally(p.getLocation(), left);
                else p.sendMessage(c(PREFIX + "&e인벤토리가 꽉 차서 당첨 아이템은 우편함으로 보냈어요 &f/우편함"));
            }
        }
        for (String cmd : r.commands) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", p.getName()));
        }
        p.sendMessage(c(PREFIX + "&e" + name + (amount > 1 ? " &fx" + amount : "") + " &f당첨!"));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.5f);
        log(p, pool, name + (amount > 1 ? " x" + amount : ""));
    }

    private void log(Player p, Pool pool, String what) {
        File f = new File(getDataFolder(), "rolls.csv");
        boolean head = !f.exists();
        try (PrintWriter w = new PrintWriter(new FileWriter(f, StandardCharsets.UTF_8, true))) {
            if (head) w.println("시각,uuid,마크닉,뽑기,결과");
            w.println(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + "," + p.getUniqueId() + "," + p.getName() + ","
                    + pool.name + "," + what.replace(",", " "));
        } catch (IOException e) {
            getLogger().warning("rolls.csv 기록 실패: " + e);
        }
    }

    /** 운영진 테스트: 실제 지급 없이 n 번 */
    void test(Player p, Pool pool, int n) {
        List<Pool.Reward> cand = new ArrayList<>();
        for (Pool.Reward r : pool.rewards) if (r.weight > 0) cand.add(r);
        if (cand.isEmpty()) {
            p.sendMessage(c(PREFIX + "&e보상이 없어요."));
            return;
        }
        Map<Pool.Reward, Integer> count = new LinkedHashMap<>();
        for (Pool.Reward r : cand) count.put(r, 0);
        for (int i = 0; i < n; i++) count.merge(pick(cand), 1, Integer::sum);
        p.sendMessage(c("&7── 테스트 " + n + "번 &f" + pool.name + " &7(중복 금지는 빼고 계산) ──"));
        for (Map.Entry<Pool.Reward, Integer> e : count.entrySet()) {
            Pool.Reward r = e.getKey();
            p.sendMessage(c(" &f" + (r.item != null ? Util.name(r.item) : "명령어") + " &7" + e.getValue() + "번 &8(" + String.format("%.1f", e.getValue() * 100.0 / n) + "%)"));
        }
    }

    // =====================================================================
    // 뽑기권 지급
    // =====================================================================

    /** 인벤토리에 넣고, 못 넣은 건 나중에 (pending) — 땅에 안 떨굼 */
    void give(Player p, Pool pool, int count) {
        int left = count;
        while (left > 0) {
            int n = Math.min(left, 64);
            Map<Integer, ItemStack> rest = p.getInventory().addItem(tickets.make(pool, n));
            int notIn = 0;
            for (ItemStack s : rest.values()) notIn += s.getAmount();
            left -= n;
            if (notIn > 0) {
                // 포키 우편함이 있으면 우편함으로, 없으면 예전처럼 보관 (/뽑기 받기)
                if (Mailbox.mail(p.getUniqueId(), tickets.make(pool, 1), notIn + left, "뽑기권")) {
                    p.sendMessage(c(PREFIX + "&e인벤토리가 꽉 차서 " + Gui.ChatStrip.strip(pool.display) + " " + (notIn + left) + "장은 우편함으로 보냈어요 &f/우편함"));
                    return;
                }
                addPending(p.getUniqueId(), pool.name, notIn + left);
                p.sendMessage(c(PREFIX + "&e인벤토리가 꽉 차서 " + Gui.ChatStrip.strip(pool.display) + " " + (notIn + left) + "장은 보관 중이에요. 칸을 비우고 &f/뽑기 받기"));
                return;
            }
        }
    }

    void addPending(UUID u, String pool, int n) {
        store.pending.computeIfAbsent(u, k -> new LinkedHashMap<>()).merge(pool, n, Integer::sum);
        store.savePending();
    }

    /** 보관 중인 뽑기권 받기 */
    void deliverPending(Player p) {
        Map<String, Integer> m = store.pending.remove(p.getUniqueId());
        if (m == null || m.isEmpty()) return;
        store.savePending();
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            Pool pool = store.pools.get(e.getKey());
            if (pool == null || e.getValue() <= 0) continue;
            p.sendMessage(c(PREFIX + "&a" + Gui.ChatStrip.strip(pool.display) + " " + e.getValue() + "장을 받았어요!"));
            give(p, pool, e.getValue());
        }
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.7f, 1.0f);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (store.pending.containsKey(p.getUniqueId())) {
            Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline()) deliverPending(p); }, 60L);
        }
    }

    // =====================================================================
    // 명령어
    // =====================================================================

    /** @a·@s·@r·@p 는 직접 처리 (Arclight 에서 Bukkit.selectEntities 가 내부 오류를 냄), 나머지만 selectEntities */
    private List<Player> targets(CommandSender s, String sel) {
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        switch (sel.toLowerCase(java.util.Locale.ROOT)) {
            case "@a": return online;
            case "@s": return s instanceof Player p ? List.of(p) : List.of();
            case "@r": return online.isEmpty() ? List.of() : List.of(online.get(rnd.nextInt(online.size())));
            case "@p": {
                if (s instanceof Player p) return List.of(p);
                return online.isEmpty() ? List.of() : List.of(online.get(0));
            }
            default:
                try {
                    List<Player> out = new ArrayList<>();
                    for (org.bukkit.entity.Entity en : Bukkit.selectEntities(s, sel)) if (en instanceof Player pl) out.add(pl);
                    return out;
                } catch (Throwable t) {
                    return null;
                }
        }
    }

    private Pool poolArg(CommandSender s, String name) {
        Pool p = store.pools.get(name);
        if (p == null) {
            for (Pool x : store.pools.values()) if (x.name.equalsIgnoreCase(name)) return x;
            s.sendMessage(c(PREFIX + "&c'" + name + "' 뽑기가 없어요. &7(" + String.join(", ", store.pools.keySet()) + ")"));
        }
        return p;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        try {
            return run(s, cmd, a);
        } catch (Throwable t) {
            getLogger().log(java.util.logging.Level.WARNING, "명령어 오류: /" + label + " " + String.join(" ", a), t);
            s.sendMessage(c(PREFIX + "&c오류: " + t.getClass().getSimpleName() + (t.getMessage() == null ? "" : " — " + t.getMessage()) + " &7(서버 로그에 자세히)"));
            return true;
        }
    }

    private boolean run(CommandSender s, Command cmd, String[] a) {
        boolean admin = s.hasPermission("poki.gacha.admin");
        if (cmd.getName().equals("뽑기권")) {
            if (!admin) { s.sendMessage(c(PREFIX + "&c운영진만 쓸 수 있어요.")); return true; }
            // /뽑기권 우편 <플레이어|@a> <종류> [개수] — 인벤 말고 바로 포키 우편함으로 (접속 안 해도 됨)
            if (a.length >= 3 && a[0].equals("우편")) {
                Pool pool = poolArg(s, a[2]);
                if (pool == null) return true;
                int n = 1;
                if (a.length >= 4) try { n = Math.max(1, Math.min(10000, Integer.parseInt(a[3]))); } catch (NumberFormatException ignored) { }
                List<UUID> to = new ArrayList<>();
                if (a[1].startsWith("@")) {
                    List<Player> targets = targets(s, a[1]);
                    if (targets == null) { s.sendMessage(c(PREFIX + "&c선택자를 못 읽었어요: " + a[1] + " &7(@a 를 써 주세요)")); return true; }
                    for (Player t : targets) to.add(t.getUniqueId());
                } else {
                    Player on = Bukkit.getPlayerExact(a[1]);
                    if (on != null) to.add(on.getUniqueId());
                    else {
                        @SuppressWarnings("deprecation") OfflinePlayer op = Bukkit.getOfflinePlayer(a[1]);
                        if (!op.hasPlayedBefore()) { s.sendMessage(c(PREFIX + "&c" + a[1] + " — 서버에 들어온 적 없는 이름이에요.")); return true; }
                        to.add(op.getUniqueId());
                    }
                }
                int sent = 0;
                for (UUID u : to) if (Mailbox.mail(u, tickets.make(pool, 1), n, "뽑기권")) sent++;
                if (sent == 0 && !to.isEmpty()) { s.sendMessage(c(PREFIX + "&c포키 우편함(PokiMail)이 없어서 못 보냈어요 &7(/뽑기권 지급 을 써 주세요)")); return true; }
                getLogger().info("뽑기권 우편: " + a[1] + " (" + sent + "명) " + pool.name + " x" + n);
                if (!(s instanceof Player) && sent > 1) return true;
                s.sendMessage(c(PREFIX + sent + "명 우편함에 " + pool.name + " 뽑기권 " + n + "장씩 보냈어요"));
                return true;
            }
            // /뽑기권 지급 <플레이어> <종류> [개수]
            if (a.length >= 3 && a[0].equals("지급")) {
                Pool pool = poolArg(s, a[2]);
                if (pool == null) return true;
                int n = 1;
                if (a.length >= 4) try { n = Math.max(1, Math.min(10000, Integer.parseInt(a[3]))); } catch (NumberFormatException ignored) { }
                if (a[1].startsWith("@")) {
                    // @a 등 선택자: 접속한 사람 각자에게 (운영진 후원 '전원 지급'용)
                    int given = 0;
                    List<Player> targets = targets(s, a[1]);
                    if (targets == null) { s.sendMessage(c(PREFIX + "&c선택자를 못 읽었어요: " + a[1] + " &7(@a 를 써 주세요)")); return true; }
                    for (Player t : targets) {
                        give(t, pool, n);
                        t.sendMessage(c(PREFIX + "&a" + Gui.ChatStrip.strip(pool.display) + " " + n + "장을 받았어요! &7들고 우클릭"));
                        given++;
                    }
                    s.sendMessage(c(PREFIX + given + "명에게 " + pool.name + " 뽑기권 " + n + "장씩 지급"));
                    return true;
                }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t != null) {
                    give(t, pool, n);
                    t.sendMessage(c(PREFIX + "&a" + Gui.ChatStrip.strip(pool.display) + " " + n + "장을 받았어요! &7들고 우클릭"));
                    s.sendMessage(c(PREFIX + t.getName() + " 에게 " + pool.name + " 뽑기권 " + n + "장 지급"));
                } else {
                    @SuppressWarnings("deprecation") OfflinePlayer op = Bukkit.getOfflinePlayer(a[1]);
                    if (!op.hasPlayedBefore()) { s.sendMessage(c(PREFIX + "&c" + a[1] + " — 서버에 들어온 적 없는 이름이에요.")); return true; }
                    if (!getConfig().getBoolean("offline-give", false)) {
                        // 접속 안 한 사람에게는 안 줌 (config offline-give: true 면 보관했다가 다음 접속 때)
                        s.sendMessage(c(PREFIX + "&e" + a[1] + " 접속 안 함 → 지급 안 했어요."));
                        getLogger().info("뽑기권 지급 안 함 (접속 안 함): " + a[1] + " " + pool.name + " x" + n);
                        return true;
                    }
                    addPending(op.getUniqueId(), pool.name, n);
                    s.sendMessage(c(PREFIX + a[1] + " 접속 안 함 → 다음 접속 때 " + pool.name + " 뽑기권 " + n + "장 지급"));
                }
                return true;
            }
            s.sendMessage(c(PREFIX + "/뽑기권 지급 <플레이어> <종류> [개수]"));
            return true;
        }

        // /뽑기
        String sub = a.length > 0 ? a[0] : "";
        Player p = s instanceof Player pl ? pl : null;
        switch (sub) {
            case "확률" -> {
                if (p == null) return true;
                if (a.length < 2) { s.sendMessage(c(PREFIX + "/뽑기 확률 <종류> &7(" + String.join(", ", store.pools.keySet()) + ")")); return true; }
                Pool pool = poolArg(s, a[1]);
                if (pool != null) gui.openView(p, pool);
            }
            case "받기" -> {
                if (p == null) return true;
                if (!store.pending.containsKey(p.getUniqueId())) s.sendMessage(c(PREFIX + "&7보관 중인 뽑기권이 없어요."));
                else deliverPending(p);
            }
            case "편집" -> {
                if (!admin || p == null) { s.sendMessage(c(PREFIX + "&c운영진만 (게임 안에서)")); return true; }
                if (a.length < 2) { s.sendMessage(c(PREFIX + "/뽑기 편집 <종류>")); return true; }
                Pool pool = poolArg(s, a[1]);
                if (pool != null) gui.openEditor(p, pool);
            }
            case "추가", "빼기" -> {
                if (!admin) { s.sendMessage(c(PREFIX + "&c운영진만")); return true; }
                if (a.length < 4 || !Bulk.SLOTS.containsKey(a[2])) {
                    s.sendMessage(c(PREFIX + "/뽑기 " + sub + " <종류> <" + String.join("|", Bulk.SLOTS.keySet()) + "> <번호>"));
                    s.sendMessage(c("&7  번호: &f27-86 &7(범위) · &f1,3,5 &7(여러 개) · &f40-44,2 &7· &f전부 &7(관리자 옷 3·5번은 빠짐)"));
                    s.sendMessage(c("&7  예) &f/뽑기 추가 치장 옷 27-86 &7· &f/뽑기 추가 치장 모자 40-44"));
                    return true;
                }
                Pool pool = poolArg(s, a[1]);
                if (pool == null) return true;
                if (pool.editor != null) { s.sendMessage(c(PREFIX + "&c" + pool.editor + " 님이 편집 중이에요. 편집 창을 닫은 뒤에 다시")); return true; }
                try {
                    if (sub.equals("추가")) {
                        List<String> report = new ArrayList<>();
                        int[] r = Bulk.add(pool, a[2], a[3], report);
                        for (String line : report) s.sendMessage(c(PREFIX + line));
                        if (r[0] > 0) store.save(true);
                        s.sendMessage(c(PREFIX + "&a" + pool.name + " 에 " + a[2] + " " + r[0] + "개 넣었어요."
                                + (r[1] > 0 ? " &7(이미 있음 " + r[1] + ")" : "") + (r[2] > 0 ? " &7(건너뜀 " + r[2] + ")" : "")
                                + " &7지금 보상 " + pool.rewards.size() + "개 · 확인: /뽑기 확률 " + pool.name));
                    } else {
                        int n = Bulk.remove(pool, a[2], a[3]);
                        if (n > 0) store.save(true);
                        s.sendMessage(c(PREFIX + "&e" + pool.name + " 에서 " + a[2] + " " + n + "개 뺐어요. &7잘못 뺐으면 /뽑기 되돌리기"));
                    }
                } catch (NumberFormatException e) {
                    s.sendMessage(c(PREFIX + "&c번호를 못 읽었어요: " + a[3] + " &7(예: 27-86, 1,3,5, 전부)"));
                }
            }
            case "만들기" -> {
                if (!admin) return true;
                if (a.length < 2) { s.sendMessage(c(PREFIX + "/뽑기 만들기 <이름>")); return true; }
                String n = a[1];
                if (store.pools.containsKey(n)) { s.sendMessage(c(PREFIX + "&c이미 있어요.")); return true; }
                store.pools.put(n, new Pool(n));
                store.save(true);
                s.sendMessage(c(PREFIX + "&a'" + n + "' 뽑기를 만들었어요. &f/뽑기 편집 " + n));
            }
            case "삭제" -> {
                if (!admin) return true;
                if (a.length < 3 || !a[2].equals("확인")) { s.sendMessage(c(PREFIX + "&c지우려면 &f/뽑기 삭제 <이름> 확인 &7(이미 나간 뽑기권은 못 쓰게 돼요)")); return true; }
                if (store.pools.remove(a[1]) == null) { s.sendMessage(c(PREFIX + "&c없어요.")); return true; }
                store.save(true);
                s.sendMessage(c(PREFIX + "&7'" + a[1] + "' 뽑기를 지웠어요. 잘못 지웠으면 &f/뽑기 되돌리기"));
            }
            case "되돌리기" -> {
                if (!admin) return true;
                gui.drafts.clear();
                s.sendMessage(c(PREFIX + (store.restoreLatest() ? "&a직전 저장 전으로 되돌렸어요. (한 번 더 하면 그 전으로)" : "&c백업이 없어요.")));
            }
            case "테스트" -> {
                if (!admin || p == null) return true;
                if (a.length < 2) { s.sendMessage(c(PREFIX + "/뽑기 테스트 <종류> [횟수]")); return true; }
                Pool pool = poolArg(s, a[1]);
                int n = 1000;
                if (a.length >= 3) try { n = Math.max(1, Math.min(100000, Integer.parseInt(a[2]))); } catch (NumberFormatException ignored) { }
                if (pool != null) test(p, pool, n);
            }
            case "리로드" -> {
                if (!admin) return true;
                gui.drafts.clear();
                store.load();
                reloadConfig();
                s.sendMessage(c(PREFIX + "&agacha.yml 을 다시 읽었어요. (" + store.pools.size() + "종류)"));
            }
            default -> {
                s.sendMessage(c("&d── 포키 뽑기 ──"));
                for (Pool pool : store.pools.values()) {
                    s.sendMessage(c(" &f" + pool.name + " &7보상 " + pool.rewards.size() + "개" + (pool.noDuplicate ? " · 치장 중복 없음" : "") + (pool.editor != null ? " &e(점검 중)" : "")));
                }
                s.sendMessage(c("&7뽑기권 들고 우클릭 · &f/뽑기 확률 <종류> &7· &f/뽑기 받기 &7(보관 중인 뽑기권)"));
                if (admin) s.sendMessage(c("&8운영진: /뽑기 편집|추가|빼기|만들기|삭제|되돌리기|테스트|리로드 · /뽑기권 지급 <플레이어> <종류> [개수]"));
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        boolean admin = s.hasPermission("poki.gacha.admin");
        List<String> out = new ArrayList<>();
        List<String> pools = new ArrayList<>(store.pools.keySet());
        if (cmd.getName().equals("뽑기권")) {
            if (!admin) return out;
            if (a.length == 1) { out.add("지급"); out.add("우편"); }
            else if (a.length == 2) { out.add("@a"); for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName()); }
            else if (a.length == 3) out.addAll(pools);
            else if (a.length == 4) out.add("1");
        } else {
            if (a.length == 1) {
                out.addAll(List.of("확률", "받기"));
                if (admin) out.addAll(List.of("편집", "추가", "빼기", "만들기", "삭제", "되돌리기", "테스트", "리로드"));
            } else if (a.length == 2 && List.of("확률", "편집", "삭제", "테스트", "추가", "빼기").contains(a[0])) out.addAll(pools);
            else if (admin && a.length == 3 && List.of("추가", "빼기").contains(a[0])) out.addAll(Bulk.SLOTS.keySet());
            else if (admin && a.length == 4 && List.of("추가", "빼기").contains(a[0])) out.addAll(List.of("전부", "1-10"));
        }
        String last = a.length == 0 ? "" : a[a.length - 1];
        out.removeIf(x -> !x.startsWith(last));
        return out;
    }
}

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
 *  뽑기권 들고 우클릭 → 연출 → 보상. 보상은 전부 같은 확률, 레어·전설은 전체 알림
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
                log(p, pool, "(전부 보유 → " + fb.name + " 뽑기권 " + pool.fallbackCount + ")", null);
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
                p.getWorld().dropItemNaturally(p.getLocation(), left);
            }
        }
        for (String cmd : r.commands) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", p.getName()));
        }
        String label = r.grade.color + name + (amount > 1 ? " &fx" + amount : "");
        p.sendMessage(c(PREFIX + label + " &f당첨!"));
        if (r.grade.announce) {
            Bukkit.broadcastMessage(c("&d✨ [뽑기] &f" + Util.nick(p) + "님이 " + Gui.ChatStrip.strip(pool.display).replace(" 뽑기권", "") + " 뽑기에서 "
                    + r.grade.color + "[" + r.grade.ko + "] " + name + "&f 획득!"));
            for (Player o : Bukkit.getOnlinePlayers()) o.playSound(o.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.5f, 1.2f);
        } else {
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.5f);
        }
        log(p, pool, name + (amount > 1 ? " x" + amount : ""), r.grade);
    }

    private void log(Player p, Pool pool, String what, Pool.Grade g) {
        File f = new File(getDataFolder(), "rolls.csv");
        boolean head = !f.exists();
        try (PrintWriter w = new PrintWriter(new FileWriter(f, StandardCharsets.UTF_8, true))) {
            if (head) w.println("시각,uuid,마크닉,뽑기,결과,등급");
            w.println(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + "," + p.getUniqueId() + "," + p.getName() + ","
                    + pool.name + "," + what.replace(",", " ") + "," + (g == null ? "" : g.ko));
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
            p.sendMessage(c(" " + r.grade.color + (r.item != null ? Util.name(r.item) : "명령어") + " &7" + e.getValue() + "번 &8(" + String.format("%.1f", e.getValue() * 100.0 / n) + "%)"));
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
        boolean admin = s.hasPermission("poki.gacha.admin");
        if (cmd.getName().equals("뽑기권")) {
            if (!admin) { s.sendMessage(c(PREFIX + "&c운영진만 쓸 수 있어요.")); return true; }
            // /뽑기권 지급 <플레이어> <종류> [개수]
            if (a.length >= 3 && a[0].equals("지급")) {
                Pool pool = poolArg(s, a[2]);
                if (pool == null) return true;
                int n = 1;
                if (a.length >= 4) try { n = Math.max(1, Math.min(10000, Integer.parseInt(a[3]))); } catch (NumberFormatException ignored) { }
                if (a[1].startsWith("@")) {
                    // @a 등 선택자: 접속한 사람 각자에게 (운영진 후원 '전원 지급'용)
                    int given = 0;
                    try {
                        for (org.bukkit.entity.Entity en : Bukkit.selectEntities(s, a[1])) {
                            if (!(en instanceof Player t)) continue;
                            give(t, pool, n);
                            t.sendMessage(c(PREFIX + "&a" + Gui.ChatStrip.strip(pool.display) + " " + n + "장을 받았어요! &7들고 우클릭"));
                            given++;
                        }
                    } catch (IllegalArgumentException ex) {
                        s.sendMessage(c(PREFIX + "&c선택자 오류: " + ex.getMessage()));
                        return true;
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
                s.sendMessage(c(PREFIX + "&agacha.yml 을 다시 읽었어요. (" + store.pools.size() + "종류)"));
            }
            default -> {
                s.sendMessage(c("&d── 포키 뽑기 ──"));
                for (Pool pool : store.pools.values()) {
                    s.sendMessage(c(" &f" + pool.name + " &7보상 " + pool.rewards.size() + "개" + (pool.noDuplicate ? " · 치장 중복 없음" : "") + (pool.editor != null ? " &e(점검 중)" : "")));
                }
                s.sendMessage(c("&7뽑기권 들고 우클릭 · &f/뽑기 확률 <종류> &7· &f/뽑기 받기 &7(보관 중인 뽑기권)"));
                if (admin) s.sendMessage(c("&8운영진: /뽑기 편집|만들기|삭제|되돌리기|테스트|리로드 · /뽑기권 지급 <플레이어> <종류> [개수]"));
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
            if (a.length == 1) out.add("지급");
            else if (a.length == 2) { out.add("@a"); for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName()); }
            else if (a.length == 3) out.addAll(pools);
            else if (a.length == 4) out.add("1");
        } else {
            if (a.length == 1) {
                out.addAll(List.of("확률", "받기"));
                if (admin) out.addAll(List.of("편집", "만들기", "삭제", "되돌리기", "테스트", "리로드"));
            } else if (a.length == 2 && List.of("확률", "편집", "삭제", "테스트").contains(a[0])) out.addAll(pools);
        }
        String last = a.length == 0 ? "" : a[a.length - 1];
        out.removeIf(x -> !x.startsWith(last));
        return out;
    }
}

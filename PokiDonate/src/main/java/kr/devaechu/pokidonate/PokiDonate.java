package kr.devaechu.pokidonate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * 포키 치지직 후원 연동.
 * 1단계: 채널 연동 + 후원 수신 + 장부 기록 (보상 지급은 다음 단계).
 */
public final class PokiDonate extends JavaPlugin implements SessionManager.Sink, LinkFlow.Events, Listener {

    static final String PREFIX = "&6[후원] &f";

    private final Links links = new Links();
    /** 지금 마크에 접속 중인 사람 (후원 이벤트는 다른 스레드에서 오니까 따로 들고 있음) */
    private final Set<UUID> online = ConcurrentHashMap.newKeySet();
    private Db db;
    private ChzzkApi api;
    private TokenKeeper tokens;
    private SessionManager sessions;
    private LinkFlow linkFlow;
    private ExecutorService net;
    private ScheduledExecutorService timer;
    private boolean configured;
    private volatile Rewards rewards;
    /** 지금 지급 중인 장부 번호 (접속 지급과 즉시 지급이 겹쳐 두 번 주지 않게) */
    private final Set<Long> paying = ConcurrentHashMap.newKeySet();
    /** 이번 실행 중 처리 끝난 장부 번호 (DB 반영 전에 같은 줄을 또 집어도 다시 안 주게) */
    private final Set<Long> settled = ConcurrentHashMap.newKeySet();
    /** 즉사 후 부활하면 돌려줄 필수 아이템 */
    private final Map<UUID, List<ItemStack>> restore = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        db = new Db();
        try {
            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            db.open(new File(getDataFolder(), "donate.db"));
        } catch (ClassNotFoundException e) {
            getLogger().severe("SQLite 드라이버가 서버에 없어요 (org.sqlite.JDBC). 플러그인을 끕니다.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "장부(donate.db)를 열지 못했어요. 플러그인을 끕니다.", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        links.load(db.loadLinks().join());
        loadRewards();

        for (Player p : Bukkit.getOnlinePlayers()) online.add(p.getUniqueId());
        Bukkit.getPluginManager().registerEvents(this, this);

        DonateCommand cmd = new DonateCommand(this);
        var command = getCommand("donate");
        if (command != null) {
            command.setExecutor(cmd);
            command.setTabCompleter(cmd);
        }
        startServices();
    }

    @Override
    public void onDisable() {
        stopServices();
        if (db != null) db.close();
    }

    /** /후원 리로드 */
    void reload() {
        reloadConfig();
        loadRewards();
        stopServices();
        startServices();
    }

    private void startServices() {
        String clientId = getConfig().getString("chzzk.client-id", "").trim();
        String clientSecret = getConfig().getString("chzzk.client-secret", "").trim();
        String bridgeUrl = getConfig().getString("bridge.url", "").trim();
        String bridgeSecret = getConfig().getString("bridge.secret", "").trim();
        configured = !clientId.isEmpty() && !clientSecret.isEmpty() && !bridgeUrl.isEmpty() && !bridgeSecret.isEmpty();
        if (!configured) {
            getLogger().warning("config.yml 의 chzzk.client-secret / bridge.secret 이 비어 있어요. 채워넣고 /후원 리로드 하면 시작해요.");
            return;
        }

        net = Executors.newCachedThreadPool(daemon("PokiDonate-net"));
        timer = Executors.newSingleThreadScheduledExecutor(daemon("PokiDonate-timer"));
        api = new ChzzkApi(getConfig().getString("chzzk.api-base", ChzzkApi.DEFAULT_BASE), clientId, clientSecret);
        tokens = new TokenKeeper(api, links, db, getLogger(), this::needsRelink);
        sessions = new SessionManager(api, links, tokens, db, getLogger(), this, net, timer, getConfig().getBoolean("debug"));
        linkFlow = new LinkFlow(api, links, db, getLogger(), this, bridgeUrl, bridgeSecret,
                Math.max(1, getConfig().getInt("link-expire-minutes", 10)));

        timer.scheduleWithFixedDelay(() -> net.execute(tokens::sweep), 1, 30, TimeUnit.MINUTES);
        LinkFlow flow = linkFlow;
        ExecutorService pool = net;
        java.util.concurrent.atomic.AtomicBoolean polling = new java.util.concurrent.atomic.AtomicBoolean();
        timer.scheduleWithFixedDelay(() -> {
            if (!flow.hasPending() || !polling.compareAndSet(false, true)) return;
            pool.execute(() -> {
                try { flow.poll(); } finally { polling.set(false); }
            });
        }, 3, 3, TimeUnit.SECONDS);
        sessions.start();
    }

    private void stopServices() {
        if (sessions != null) sessions.stop();
        if (timer != null) timer.shutdownNow();
        if (net != null) net.shutdownNow();
        sessions = null;
        linkFlow = null;
        timer = null;
        net = null;
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        online.add(u);
        if (restore.containsKey(u) && !e.getPlayer().isDead()) giveBack(e.getPlayer());
        // 지급 직전에 나갔던 후원이 있으면 지금 지급
        if (links.get(u) != null) {
            db.pendingFor(u).thenAccept(list -> {
                if (!list.isEmpty()) Bukkit.getScheduler().runTaskLater(this, () -> list.forEach(this::pay), 40L);
            });
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { online.remove(e.getPlayer().getUniqueId()); }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (restore.containsKey(p.getUniqueId())) Bukkit.getScheduler().runTaskLater(this, () -> giveBack(p), 2L);
    }

    // ── 후원 수신 (세션 스레드) ──────────────────────────

    @Override
    public void onDonation(JsonObject d) {
        String channelId = ChzzkApi.str(d, "channelId");
        Link l = links.byChannel(channelId);
        if (l != null && !online.contains(l.uuid())) {
            // 방송 중이어도 마크에 없으면 보상 대상이 아니라 장부에 남기지 않는다
            if (getConfig().getBoolean("debug")) getLogger().info("후원 무시 (마크 미접속): " + l.playerName() + " " + won(amount(d.get("payAmount"))));
            return;
        }
        record(donation(channelId, l, ChzzkApi.str(d, "donatorChannelId"), ChzzkApi.str(d, "donatorNickname"),
                amount(d.get("payAmount")), ChzzkApi.str(d, "donationType"), ChzzkApi.str(d, "donationText"), false));
    }

    boolean isOnline(UUID uuid) {
        return online.contains(uuid);
    }

    /** 장부 한 줄 만들기. 마크 미접속 후원은 여기까지 오지 않는다 */
    Donation donation(String channelId, Link l, String donatorId, String donatorNick, long amount, String type, String text, boolean test) {
        Donation.Status st = Donation.Status.PENDING;
        String note = null;
        if (l == null) { st = Donation.Status.SKIPPED; note = "미연동 채널"; }
        return new Donation(0, System.currentTimeMillis(), channelId, l == null ? null : l.uuid(), l == null ? null : l.playerName(),
                donatorId, donatorNick, amount, type, text, st, test, note);
    }

    /** 장부에 쓰고 알림. /후원 테스트 도 여기로 온다 */
    void record(Donation don) {
        db.insertDonation(don).whenComplete((id, err) -> {
            if (err != null) {
                getLogger().log(Level.SEVERE, "후원 기록 실패! " + don, err);
                return;
            }
            getLogger().info("후원 #" + id + (don.test() ? " (테스트)" : "") + " " + (don.streamerName() == null ? don.channelId() : don.streamerName())
                    + " ← " + don.donatorNickname() + " " + won(don.amount()) + " [" + don.type() + "] " + don.message());
            if (don.status() == Donation.Status.PENDING && getConfig().getBoolean("notify-streamer", true)) {
                tell(don.streamerUuid(), "&e" + nick(don.donatorNickname()) + "&f님 &a" + won(don.amount())
                        + (don.test() ? " &7(테스트)" : "") + " &8· 장부 #" + id);
            }
            if (don.status() == Donation.Status.PENDING) {
                Donation saved = new Donation(id, don.receivedAt(), don.channelId(), don.streamerUuid(), don.streamerName(),
                        don.donatorChannelId(), don.donatorNickname(), don.amount(), don.type(), don.message(),
                        don.status(), don.test(), don.note());
                Bukkit.getScheduler().runTask(this, () -> pay(saved));
            }
        });
    }

    // ── 보상 지급 (메인 스레드) ─────────────────────────

    private void loadRewards() {
        File f = new File(getDataFolder(), "rewards.yml");
        if (!f.exists()) saveResource("rewards.yml", false);
        rewards = Rewards.load(f, getLogger());
        getLogger().info("보상표: 입주민 " + rewards.resident.size() + "구간 · 운영진 " + rewards.staff.size() + "구간");
    }

    Rewards rewards() { return rewards; }

    /** 관리자 재지급: 실패·대상 아님 처리된 줄도 다시 시도할 수 있게 */
    String repay(Donation d) {
        settled.remove(d.id());
        return pay(d);
    }

    /**
     * 장부 한 줄을 보상표대로 지급. 스트리머가 지금 없으면 '대기' 로 두고 다음 접속 때 지급.
     * 운영진(poki.donate.staff) 이면 운영진 보상표, 아니면 입주민 보상표.
     * @return 지급 결과 한 줄 (관리자 재지급 안내용)
     */
    String pay(Donation d) {
        if (d.streamerUuid() == null) return "연동된 스트리머가 없어요";
        if (settled.contains(d.id())) return "이미 처리된 후원이에요";
        if (!paying.add(d.id())) return "이미 지급 중이에요";
        try {
            Player p = Bukkit.getPlayer(d.streamerUuid());
            if (p == null) return "스트리머가 접속 중이 아니에요 — 접속하면 자동 지급";
            boolean staff = p.hasPermission("poki.donate.staff");
            Rewards r = rewards;
            Rewards.Tier tier = r.pick(staff ? r.staff : r.resident, d.amount());
            String profile = staff ? "운영진" : "입주민";
            if (tier == null) {
                String why = (staff ? r.staff : r.resident).isEmpty() ? profile + " 보상표 비어 있음"
                        : r.exact ? "메뉴에 없는 금액" : "최소 금액 미만";
                db.setStatus(d.id(), Donation.Status.SKIPPED, why);
                settled.add(d.id());
                return "대상 아님 (" + why + ")";
            }
            if (tier.commands().isEmpty()) {
                db.setStatus(d.id(), Donation.Status.FAILED, won(tier.min()) + " 메뉴 보상 명령어 미설정 — 직접 지급 필요");
                settled.add(d.id());
                tellAdmins("&e후원 #" + d.id() + " &f" + plain(p.getName()) + " " + won(d.amount()) + " &7— 보상 명령어가 아직 없어요. 직접 지급해 주세요.");
                return "실패 — " + won(tier.min()) + " 메뉴 보상 명령어가 rewards.yml 에 없어요";
            }
            int count = tier.count(d.amount());
            for (String raw : tier.commands()) {
                String cmd = fill(raw, p.getName(), count, d);
                boolean ok;
                try {
                    if (cmd.equalsIgnoreCase("@wipe-and-kill")) {
                        wipeAndKill(p, r);
                        ok = true;
                    } else {
                        ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.startsWith("/") ? cmd.substring(1) : cmd);
                    }
                } catch (RuntimeException ex) {
                    getLogger().log(Level.WARNING, "후원 #" + d.id() + " 보상 명령어 오류: " + cmd, ex);
                    ok = false;
                }
                if (!ok) {
                    String head = cmd.length() > 40 ? cmd.substring(0, 40) + "…" : cmd;
                    db.setStatus(d.id(), Donation.Status.FAILED, "명령어 실패: " + head);
                    settled.add(d.id());
                    tellAdmins("&c후원 #" + d.id() + " 보상 지급 실패 &7(" + plain(p.getName()) + ") &f/후원 재지급 " + d.id());
                    return "실패 — 명령어를 실행하지 못했어요";
                }
            }
            db.setStatus(d.id(), Donation.Status.PAID, profile + " " + won(tier.min()) + " 메뉴" + (tier.per() > 0 ? " · " + count + "개" : ""));
            settled.add(d.id());
            getLogger().info("후원 #" + d.id() + " 보상 지급: " + p.getName() + " " + profile + " " + count + "개");
            if (!tier.message().isBlank()) p.sendMessage(color(PREFIX + fill(tier.message(), plain(p.getName()), count, d)));
            return "지급 완료 (" + profile + " · " + count + "개)";
        } finally {
            paying.remove(d.id());
        }
    }

    /**
     * 즉사: rewards.yml 의 keep-items 에 있는 필수 아이템만 빼두고 인벤토리(갑옷·왼손 포함) 전부 삭제 → 사망.
     * 빼둔 아이템은 부활하면 돌려준다 (keepInventory 설정과 상관없이 땅에 떨어지지 않음).
     */
    private void wipeAndKill(Player p, Rewards r) {
        PlayerInventory inv = p.getInventory();
        List<ItemStack> kept = new ArrayList<>();
        for (ItemStack it : inv.getContents()) {
            if (it == null || it.getType().isAir()) continue;
            ItemMeta meta = it.getItemMeta();
            Integer cmd = meta != null && meta.hasCustomModelData() ? meta.getCustomModelData() : null;
            if (r.keeps(it.getType().getKey().toString(), cmd)) kept.add(it.clone());
        }
        inv.clear();
        inv.setArmorContents(new ItemStack[4]);
        inv.setItemInOffHand(null);
        p.setItemOnCursor(null);
        if (!kept.isEmpty()) restore.merge(p.getUniqueId(), kept, (a, b) -> { List<ItemStack> m = new ArrayList<>(a); m.addAll(b); return m; });
        getLogger().info("즉사: " + p.getName() + " 인벤토리 삭제 (필수 아이템 " + kept.size() + "칸 보관)");
        p.setHealth(0);
    }

    private void giveBack(Player p) {
        List<ItemStack> items = restore.remove(p.getUniqueId());
        if (items == null || items.isEmpty()) return;
        for (ItemStack left : p.getInventory().addItem(items.toArray(new ItemStack[0])).values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), left);
        }
        p.sendMessage(color(PREFIX + "&7필수 아이템 " + items.size() + "칸을 돌려받았어요."));
    }

    private static String fill(String s, String player, int count, Donation d) {
        return s.replace("%player%", player)
                .replace("%count%", String.valueOf(count))
                .replace("%amount%", String.valueOf(d.amount()))
                .replace("%donator%", nick(d.donatorNickname()));
    }

    static long amount(JsonElement e) {
        if (e == null || e.isJsonNull()) return 0;
        try {
            if (e.getAsJsonPrimitive().isNumber()) return e.getAsLong();
            String digits = e.getAsString().replaceAll("[^0-9]", ""); // 문서엔 문자열, 실제론 숫자로 옴
            return digits.isEmpty() ? 0 : Long.parseLong(digits);
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    @Override
    public void onRevoked(String channelId) {
        Link l = links.byChannel(channelId);
        if (l != null) tokens.markRelink(l, "스트리머가 치지직에서 권한을 철회함");
    }

    private void needsRelink(Link l) {
        if (sessions != null) sessions.remove(l.channelId(), null);
        tell(l.uuid(), "&c치지직 연동이 풀렸어요. &e/후원 연동 &c으로 다시 연결해 주세요.");
        tellAdmins("&e" + l.playerName() + " &7(" + l.channelName() + ") &f재연동이 필요해요.");
    }

    // ── LinkFlow.Events ─────────────────────────────────

    @Override
    public void linked(Link link, Link old) {
        if (sessions != null) {
            if (old != null) sessions.remove(old.channelId(), null);
            sessions.assign(link.channelId());
        }
        tell(link.uuid(), "&a치지직 채널 &f" + plain(link.channelName()) + " &a연동 완료! &7이제 방송 후원이 장부에 기록돼요.");
    }

    // ── 명령어에서 쓰는 것들 ────────────────────────────

    boolean configured() { return configured; }
    Links links() { return links; }
    Db db() { return db; }
    SessionManager sessions() { return sessions; }
    LinkFlow linkFlow() { return linkFlow; }

    /** 연동 해제 (본인 또는 관리자). 장부는 그대로 남는다 */
    void unlink(Link l) {
        links.remove(l.uuid());
        db.deleteLink(l.uuid());
        db.log("unlinked", l.channelName() + " ↔ " + l.playerName());
        if (sessions != null) sessions.remove(l.channelId(), l.accessToken());
        if (net != null && api != null) {
            ChzzkApi a = api;
            net.execute(() -> {
                try { a.revoke(l.accessToken()); } catch (Exception ignored) { }
            });
        }
    }

    // ── 메시지 ──────────────────────────────────────────

    @Override
    public void tell(UUID uuid, String msg) {
        Bukkit.getScheduler().runTask(this, () -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) p.sendMessage(color(PREFIX + msg));
        });
    }

    void tellAdmins(String msg) {
        Bukkit.getScheduler().runTask(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("poki.donate.admin")) p.sendMessage(color(PREFIX + msg));
            }
        });
    }

    static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    static String won(long amount) {
        return String.format("%,d원", amount);
    }

    /** 닉네임에 섞인 & 색코드가 먹지 않게 (& 뒤에 폭 0 공백) */
    static String nick(String s) {
        return s == null || s.isEmpty() ? "익명" : plain(s);
    }

    static String plain(String s) {
        return s == null ? "" : s.replace("&", "&​").replace("§", "");
    }
}

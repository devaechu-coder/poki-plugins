package kr.devaechu.pokilive;

import net.md_5.bungee.api.ChatColor;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 콘서트장 공연 연출.
 * 무대 기준점(무대 중심 + 객석 방향)을 한 번 정해두면 불꽃·조명 위치를 거기서 계산한다.
 */
public final class PokiLive extends JavaPlugin implements Listener, TabCompleter {

    static final Color PINK = Color.fromRGB(0xF28DB8);
    static final Color BLUE = Color.fromRGB(0x4FC3E8);
    static final Color PURPLE = Color.fromRGB(0x9A5FD0);
    static final Color WHITE = Color.fromRGB(0xFFFFFF);
    static final Color GOLD = Color.fromRGB(0xF5D53A);
    static final java.awt.Color[] CYCLE = {
            new java.awt.Color(0xF28DB8), new java.awt.Color(0x7FD6F2), new java.awt.Color(0xB98AF0)};

    private NamespacedKey fwKey;
    private Location stage;          // 무대 중심 (발 위치)
    private Vector front;            // 객석 쪽 방향 (수평 단위벡터)
    private Location screenLoc;      // 스크린 문구 자리
    private float screenYaw;
    private TextDisplay screen;
    private String screenText;
    private BukkitTask screenTask, lightTask;
    private String lightMode;        // 반짝이, 하트, 음표, 벚꽃, 별빛
    private final Map<UUID, BukkitTask> spots = new HashMap<>();
    private final List<BukkitTask> shows = new ArrayList<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        fwKey = new NamespacedKey(this, "show");
        loadPlaces();
        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("pokilive");
        if (cmd != null) { cmd.setExecutor(this); cmd.setTabCompleter(this); }
    }

    @Override
    public void onDisable() {
        stopAll();
        removeScreen();
    }

    // ───────── 위치 저장 ─────────
    private void loadPlaces() {
        var c = getConfig();
        stage = c.getLocation("stage.location");
        front = c.getVector("stage.front");
        screenLoc = c.getLocation("screen.location");
        screenYaw = (float) c.getDouble("screen.yaw", 0);
    }

    private void savePlaces() {
        var c = getConfig();
        c.set("stage.location", stage);
        c.set("stage.front", front);
        c.set("screen.location", screenLoc);
        c.set("screen.yaw", screenYaw);
        saveConfig();
    }

    /** 무대 기준 상대좌표 → 월드 좌표. side: 객석을 볼 때 오른쪽 +, fwd: 객석 쪽 +. */
    private Location at(double side, double fwd, double up) {
        Vector right = new Vector(-front.getZ(), 0, front.getX());
        return stage.clone().add(right.multiply(side)).add(front.clone().multiply(fwd)).add(0, up, 0);
    }

    private List<Player> audience() {
        double r = getConfig().getDouble("radius", 64);
        List<Player> out = new ArrayList<>();
        if (stage == null || stage.getWorld() == null) return out;
        for (Player p : stage.getWorld().getPlayers())
            if (p.getLocation().distanceSquared(stage) <= r * r) out.add(p);
        return out;
    }

    // ───────── 명령어 ─────────
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 0) { help(s); return true; }
        String sub = a[0];
        if (sub.equals("리로드")) {
            reloadConfig(); loadPlaces(); msg(s, "설정 다시 읽음"); return true;
        }
        if (sub.equals("무대")) {
            if (!(s instanceof Player p)) return true;
            Location l = p.getLocation();
            Vector d = l.getDirection().setY(0);
            // 45도가 아니라 동서남북 4방향으로 맞춤
            if (Math.abs(d.getX()) > Math.abs(d.getZ())) d = new Vector(Math.signum(d.getX()), 0, 0);
            else d = new Vector(0, 0, Math.signum(d.getZ()));
            stage = new Location(l.getWorld(), l.getBlockX() + 0.5, l.getY(), l.getBlockZ() + 0.5);
            front = d;
            savePlaces();
            msg(s, "무대 기준점 저장 — 지금 보는 쪽을 객석으로 잡았어. 확인은 &e/공연 불꽃");
            return true;
        }
        if (sub.equals("스크린")) {
            if (!(s instanceof Player p)) return true;
            if (a.length > 1 && a[1].equals("돌리기")) {
                screenYaw = (screenYaw + 180) % 360; savePlaces(); refreshScreen();
                msg(s, "스크린 글자 방향을 뒤집었어"); return true;
            }
            RayTraceResult hit = p.rayTraceBlocks(80);
            if (hit == null || hit.getHitBlock() == null || hit.getHitBlockFace() == null) {
                msg(s, "&c스크린 가운데 블록을 바라보고 다시 쳐줘"); return true;
            }
            Vector n = hit.getHitBlockFace().getDirection();
            Location c = hit.getHitBlock().getLocation().add(0.5, 0.5, 0.5).add(n.clone().multiply(0.52));
            Location look = c.clone(); look.setDirection(n);
            screenYaw = look.getYaw();
            // 글자는 기준점에서 위로 그려지니까 가운데쯤 오도록 조금 내림
            screenLoc = c.subtract(0, getConfig().getDouble("screen-scale", 5) * 0.15, 0);
            savePlaces();
            showText(screenText != null ? screenText : "&lPOKI LIVE");
            msg(s, "스크린 자리 저장. 글자가 거꾸로 보이면 &e/공연 스크린 돌리기");
            return true;
        }
        if (sub.equals("리모컨")) {
            if (s instanceof Player p) p.openInventory(new Remote().inv);
            return true;
        }
        if (stage == null) { msg(s, "&c먼저 무대 가운데에 서서 객석을 보고 &e/공연 무대 &c를 쳐줘"); return true; }
        switch (sub) {
            case "시작" -> opening(a.length > 1 ? String.join(" ", Arrays.copyOfRange(a, 1, a.length)) : null);
            case "끝" -> finale();
            case "불꽃" -> fireworks(a.length > 1 ? a[1] : "기본");
            case "문구" -> {
                if (a.length < 2) { msg(s, "/공연 문구 <내용|끄기>  (줄바꿈은 \\n, 색은 &d 같은 코드)"); return true; }
                if (a[1].equals("끄기")) { removeScreen(); screenText = null; msg(s, "스크린 문구 끔"); }
                else {
                    if (screenLoc == null) { msg(s, "&c먼저 스크린을 보고 &e/공연 스크린"); return true; }
                    showText(String.join(" ", Arrays.copyOfRange(a, 1, a.length)));
                }
            }
            case "조명" -> {
                String m = a.length > 1 ? a[1] : "반짝이";
                if (m.equals("끄기")) { stopLight(); msg(s, "조명 끔"); }
                else if (LIGHTS.contains(m)) { startLight(m); msg(s, "조명: " + m); }
                else msg(s, "조명 종류: " + String.join(", ", LIGHTS) + ", 끄기");
            }
            case "스포트" -> {
                Player t = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : (s instanceof Player p ? p : null);
                if (a.length > 1 && a[1].equals("끄기")) { stopSpots(); msg(s, "스포트라이트 모두 끔"); return true; }
                if (t == null) { msg(s, "&c그 플레이어를 못 찾았어"); return true; }
                msg(s, toggleSpot(t) ? t.getName() + " 스포트라이트 켬" : t.getName() + " 스포트라이트 끔");
            }
            case "멈춤" -> { stopAll(); msg(s, "연출 모두 멈춤 (스크린 문구는 그대로)"); }
            default -> help(s);
        }
        return true;
    }

    static final List<String> LIGHTS = List.of("반짝이", "하트", "음표", "벚꽃", "별빛");
    static final List<String> FW = List.of("기본", "별", "하트", "연속", "피날레");

    private void help(CommandSender s) {
        msg(s, "&e/공연 리모컨 &f— 버튼으로 연출 (제일 편함)");
        msg(s, "&e/공연 무대 &f— 무대 가운데서 객석 보고 치기 (처음 한 번)");
        msg(s, "&e/공연 스크린 &f— 스크린 가운데 보고 치기 (처음 한 번)");
        msg(s, "&e/공연 시작 [제목] &f/ &e/공연 끝");
        msg(s, "&e/공연 불꽃 [" + String.join("|", FW) + "]");
        msg(s, "&e/공연 문구 <내용|끄기> &f/ &e/공연 조명 <" + String.join("|", LIGHTS) + "|끄기>");
        msg(s, "&e/공연 스포트 [닉|끄기] &f/ &e/공연 멈춤");
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        List<String> opts;
        if (a.length == 1) opts = List.of("리모컨", "무대", "스크린", "시작", "끝", "불꽃", "문구", "조명", "스포트", "멈춤", "리로드");
        else if (a.length == 2) opts = switch (a[0]) {
            case "불꽃" -> FW;
            case "조명" -> concat(LIGHTS, "끄기");
            case "문구" -> List.of("끄기");
            case "스크린" -> List.of("돌리기");
            case "스포트" -> { List<String> n = new ArrayList<>(); n.add("끄기"); Bukkit.getOnlinePlayers().forEach(p -> n.add(p.getName())); yield n; }
            default -> List.of();
        };
        else opts = List.of();
        String cur = a[a.length - 1];
        return opts.stream().filter(o -> o.startsWith(cur)).toList();
    }

    private static List<String> concat(List<String> l, String x) { List<String> o = new ArrayList<>(l); o.add(x); return o; }

    // ───────── 오프닝 / 피날레 ─────────
    private void opening(String title) {
        String t = title == null ? "&d&lPOKI LIVE" : title;
        String sub = getConfig().getString("messages.opening-subtitle", "&bPOKI LIVE");
        stopLight();
        String[] counts = {"&d3", "&b2", "&51"};
        for (int i = 0; i < 3; i++) {
            final String n = counts[i];
            final float pitch = 0.8f + i * 0.2f;
            later(i * 20L, () -> {
                for (Player p : audience()) {
                    p.sendTitle(color(n), "", 0, 18, 4);
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, pitch);
                }
                if (screenLoc != null) showText("&l" + n);
            });
        }
        later(60L, () -> {
            for (Player p : audience()) {
                p.sendTitle(color(t), color(title == null ? "" : sub), 5, 60, 20);
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            }
            if (screenLoc != null) showText(t);
            fireworks("연속");
            startLight("반짝이");
        });
    }

    private void finale() {
        stopLight();
        String t = getConfig().getString("messages.finale-title", "&d감사합니다!");
        String sub = getConfig().getString("messages.finale-subtitle", "&bPOKI LIVE");
        fireworks("피날레");
        later(30L, () -> {
            for (Player p : audience()) {
                p.sendTitle(color(t), color(sub), 10, 80, 30);
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
            }
            if (screenLoc != null) showText(t);
            startLight("하트");
        });
        later(20L * 20, this::stopLight);
    }

    // ───────── 불꽃 ─────────
    private List<double[]> points() {
        List<double[]> out = new ArrayList<>();
        for (Object o : getConfig().getList("firework-points", List.of())) {
            if (o instanceof List<?> l && l.size() >= 3)
                out.add(new double[]{num(l.get(0)), num(l.get(1)), num(l.get(2))});
        }
        if (out.isEmpty()) out.add(new double[]{0, 4, 0.5});
        return out;
    }

    private static double num(Object o) { return o instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(o)); }

    private void fireworks(String kind) {
        List<double[]> pts = points();
        var rnd = ThreadLocalRandom.current();
        switch (kind) {
            case "별" -> { for (double[] p : pts) launch(at(p[0], p[1], p[2]), FireworkEffect.Type.STAR, rnd.nextInt(14, 22), false); }
            case "하트" -> {
                for (int i = 0; i < pts.size(); i++) {
                    double[] p = pts.get(i);
                    later(i * 4L, () -> heartBurst(at(p[0], p[1], p[2] + 6)));
                }
            }
            case "연속" -> {
                // 바깥 → 가운데로 차례차례
                List<double[]> order = new ArrayList<>(pts);
                order.sort(Comparator.comparingDouble(p -> -Math.abs(p[0])));
                for (int r = 0; r < 3; r++)
                    for (int i = 0; i < order.size(); i++) {
                        double[] p = order.get(i);
                        FireworkEffect.Type ty = r == 1 ? FireworkEffect.Type.STAR : FireworkEffect.Type.BALL_LARGE;
                        later(r * 30L + i * 5L, () -> launch(at(p[0], p[1], p[2]), ty, rnd.nextInt(14, 22), false));
                    }
            }
            case "피날레" -> {
                for (int r = 0; r < 6; r++)
                    for (double[] p : pts) {
                        FireworkEffect.Type ty = FireworkEffect.Type.values()[rnd.nextInt(FireworkEffect.Type.values().length)];
                        if (ty == FireworkEffect.Type.CREEPER) ty = FireworkEffect.Type.STAR;
                        FireworkEffect.Type fty = ty;
                        later(r * 12L + rnd.nextInt(8), () -> launch(at(p[0], p[1], p[2]), fty, rnd.nextInt(16, 26), true));
                    }
                for (int i = 0; i < 4; i++) later(80L + i * 6L, () -> heartBurst(at(0, -1, 16)));
            }
            default -> { for (double[] p : pts) launch(at(p[0], p[1], p[2]), FireworkEffect.Type.BALL_LARGE, rnd.nextInt(14, 22), false); }
        }
    }

    private void launch(Location l, FireworkEffect.Type type, int fuse, boolean big) {
        World w = l.getWorld();
        if (w == null) return;
        var rnd = ThreadLocalRandom.current();
        Color[] pal = {PINK, BLUE, PURPLE};
        Color c1 = pal[rnd.nextInt(3)], c2 = pal[rnd.nextInt(3)];
        Firework fw = w.spawn(l, Firework.class, f -> {
            FireworkMeta m = f.getFireworkMeta();
            m.clearEffects();
            m.addEffect(FireworkEffect.builder().with(type).withColor(c1, c2).withFade(WHITE)
                    .trail(true).flicker(big || rnd.nextBoolean()).build());
            if (big) m.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BURST).withColor(GOLD).build());
            m.setPower(1);
            f.setFireworkMeta(m);
            f.getPersistentDataContainer().set(fwKey, PersistentDataType.BYTE, (byte) 1);
        });
        later(fuse, () -> { if (fw.isValid()) fw.detonate(); });
    }

    /** 객석 쪽을 향한 하트 모양 파티클. */
    private void heartBurst(Location c) {
        World w = c.getWorld();
        if (w == null) return;
        Vector right = new Vector(-front.getZ(), 0, front.getX());
        Particle.DustOptions pink = new Particle.DustOptions(PINK, 2.2f);
        Particle.DustOptions light = new Particle.DustOptions(Color.fromRGB(0xFFC4DC), 1.6f);
        double s = 0.22;
        for (double t = 0; t < Math.PI * 2; t += Math.PI / 40) {
            double x = 16 * Math.pow(Math.sin(t), 3);
            double y = 13 * Math.cos(t) - 5 * Math.cos(2 * t) - 2 * Math.cos(3 * t) - Math.cos(4 * t);
            Location p = c.clone().add(right.clone().multiply(x * s)).add(0, y * s, 0);
            w.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, pink, true);
            w.spawnParticle(Particle.DUST, c.clone().add(right.clone().multiply(x * s * 0.6)).add(0, y * s * 0.6, 0), 1, 0, 0, 0, 0, light, true);
        }
        w.playSound(c, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 3f, 1.2f);
    }

    @EventHandler
    public void onFireworkDamage(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Firework f && f.getPersistentDataContainer().has(fwKey, PersistentDataType.BYTE))
            e.setCancelled(true);   // 공연 불꽃은 아프지 않게
    }

    // ───────── 스크린 문구 ─────────
    private void showText(String raw) {
        screenText = raw;
        boolean cycle = getConfig().getBoolean("screen-color-cycle", true) && !raw.contains("&");
        if (screen == null || !screen.isValid()) spawnScreen();
        if (screen == null) return;
        if (screenTask != null) { screenTask.cancel(); screenTask = null; }
        String text = raw.replace("\\n", "\n");
        if (!cycle) { screen.setText(color(text)); return; }
        screenTask = new BukkitRunnable() {
            int i = 0;
            @Override public void run() {
                if (screen == null || !screen.isValid()) { cancel(); return; }
                screen.setText(ChatColor.of(CYCLE[i++ % CYCLE.length]) + "" + ChatColor.BOLD + text.replace("\n", "\n" + ChatColor.of(CYCLE[(i - 1) % CYCLE.length]) + ChatColor.BOLD));
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private void spawnScreen() {
        if (screenLoc == null || screenLoc.getWorld() == null) return;
        Location l = screenLoc.clone();
        l.setYaw(screenYaw);
        l.setPitch(0);
        float sc = (float) getConfig().getDouble("screen-scale", 5);
        screen = l.getWorld().spawn(l, TextDisplay.class, d -> {
            d.setPersistent(false);              // 재시작하면 사라짐 (다시 /공연 문구 로 띄움)
            d.setBillboard(Display.Billboard.FIXED);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            d.setShadowed(true);
            d.setLineWidth(220);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                    new Vector3f(sc, sc, sc), new AxisAngle4f()));
        });
    }

    private void refreshScreen() {
        removeScreen();
        if (screenText != null) showText(screenText);
    }

    private void removeScreen() {
        if (screenTask != null) { screenTask.cancel(); screenTask = null; }
        if (screen != null) { screen.remove(); screen = null; }
    }

    // ───────── 조명 파티클 ─────────
    private void startLight(String mode) {
        stopLight();
        lightMode = mode;
        var c = getConfig();
        double side = c.getDouble("audience.side", 11), f0 = c.getDouble("audience.front-min", 6),
                f1 = c.getDouble("audience.front-max", 30), h0 = c.getDouble("audience.height-min", 1),
                h1 = c.getDouble("audience.height-max", 7);
        lightTask = new BukkitRunnable() {
            int tick = 0;
            @Override public void run() {
                World w = stage.getWorld();
                if (w == null) { cancel(); return; }
                var r = ThreadLocalRandom.current();
                tick++;
                for (int i = 0; i < 14; i++) {
                    Location p = at(r.nextDouble(-side, side), r.nextDouble(f0, f1), r.nextDouble(h0, h1));
                    switch (mode) {
                        case "하트" -> { if (i < 4) w.spawnParticle(Particle.HEART, p, 1, 0, 0, 0, 0, null, true); }
                        case "음표" -> { if (i < 5) w.spawnParticle(Particle.NOTE, p, 0, r.nextDouble(), 0, 0, 1, null, true); }
                        case "벚꽃" -> w.spawnParticle(Particle.CHERRY_LEAVES, p.add(0, 4, 0), 1, 0.3, 0, 0.3, 0, null, true);
                        case "별빛" -> {
                            Color col = new Color[]{PINK, BLUE, PURPLE}[r.nextInt(3)];
                            w.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, new Particle.DustOptions(col, 1.5f), true);
                        }
                        default -> w.spawnParticle(Particle.END_ROD, p, 1, 0.1, 0.1, 0.1, 0.01, null, true);
                    }
                }
                // 무대 양옆 기둥에서 위로 올라가는 빛 (1초마다)
                if (tick % 4 == 0) {
                    for (int sgn : new int[]{-1, 1})
                        w.spawnParticle(Particle.END_ROD, at(sgn * 12.5, 1, 1), 6, 0.2, 0.5, 0.2, 0.08, null, true);
                }
            }
        }.runTaskTimer(this, 0L, 5L);
    }

    private void stopLight() {
        if (lightTask != null) { lightTask.cancel(); lightTask = null; }
        lightMode = null;
    }

    // ───────── 스포트라이트 ─────────
    private boolean toggleSpot(Player t) {
        BukkitTask old = spots.remove(t.getUniqueId());
        if (old != null) { old.cancel(); t.setGlowing(false); return false; }
        t.setGlowing(true);
        spots.put(t.getUniqueId(), new BukkitRunnable() {
            double a = 0;
            @Override public void run() {
                if (!t.isOnline()) { cancel(); spots.remove(t.getUniqueId()); return; }
                Location base = t.getLocation();
                World w = base.getWorld();
                a += 0.35;
                Particle.DustOptions ring = new Particle.DustOptions(Color.fromRGB(0xFFF2B0), 1.2f);
                for (int i = 0; i < 16; i++) {
                    double ang = a + i * Math.PI / 8;
                    w.spawnParticle(Particle.DUST, base.clone().add(Math.cos(ang) * 1.1, 0.1, Math.sin(ang) * 1.1), 1, 0, 0, 0, 0, ring, true);
                }
                // 위에서 내려오는 빛기둥
                for (double y = 1; y < 9; y += 0.8)
                    w.spawnParticle(Particle.END_ROD, base.clone().add(Math.cos(a * 2) * 0.5, 2 + y, Math.sin(a * 2) * 0.5), 1, 0, 0, 0, 0, null, true);
            }
        }.runTaskTimer(this, 0L, 3L));
        return true;
    }

    private void stopSpots() {
        for (var e : spots.entrySet()) {
            e.getValue().cancel();
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) p.setGlowing(false);
        }
        spots.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        BukkitTask t = spots.remove(e.getPlayer().getUniqueId());
        if (t != null) { t.cancel(); e.getPlayer().setGlowing(false); }
    }

    private void stopAll() {
        stopLight();
        stopSpots();
        shows.forEach(BukkitTask::cancel);
        shows.clear();
    }

    private void later(long ticks, Runnable r) {
        shows.removeIf(BukkitTask::isCancelled);
        shows.add(Bukkit.getScheduler().runTaskLater(this, r, ticks));
    }

    // ───────── 리모컨 GUI ─────────
    private final class Remote implements InventoryHolder {
        final Inventory inv = Bukkit.createInventory(this, 36, "포키 공연 리모컨");
        final Map<Integer, String> act = new HashMap<>();

        Remote() {
            put(10, Material.NETHER_STAR, "&d&l공연 시작", "act:시작", "카운트다운 → 제목 → 불꽃 → 반짝이 조명");
            put(16, Material.CAKE, "&d&l공연 끝 (피날레)", "act:끝", "큰 불꽃 + 감사합니다 + 하트");
            put(12, Material.FIREWORK_ROCKET, "&f불꽃 — 기본", "fw:기본", "무대 쪽에서 한 번에");
            put(13, Material.FIREWORK_STAR, "&f불꽃 — 별", "fw:별", "별 모양");
            put(14, Material.PINK_DYE, "&f불꽃 — 하트", "fw:하트", "하트 모양 빛");
            put(21, Material.BLAZE_ROD, "&f불꽃 — 연속", "fw:연속", "바깥에서 가운데로 차례로");
            put(22, Material.TNT_MINECART, "&f불꽃 — 피날레", "fw:피날레", "왕창");
            put(23, Material.SPECTRAL_ARROW, "&e스포트라이트 (나)", "spot", "나한테 빛 + 발광, 한 번 더 누르면 끔");
            put(27, Material.END_ROD, "&f조명 — 반짝이", "light:반짝이", null);
            put(28, Material.RED_DYE, "&f조명 — 하트", "light:하트", null);
            put(29, Material.NOTE_BLOCK, "&f조명 — 음표", "light:음표", null);
            put(30, Material.CHERRY_LEAVES, "&f조명 — 벚꽃", "light:벚꽃", null);
            put(31, Material.AMETHYST_SHARD, "&f조명 — 별빛", "light:별빛", null);
            put(32, Material.GRAY_DYE, "&7조명 끄기", "light:끄기", null);
            put(34, Material.BLACK_CONCRETE, "&7스크린 문구 끄기", "screen:끄기", "문구 바꾸기는 /공연 문구 <내용>");
            put(35, Material.BARRIER, "&c모두 멈춤", "stop", "조명·스포트·예약된 불꽃 멈춤");
            ItemStack pane = item(Material.PINK_STAINED_GLASS_PANE, " ", null);
            for (int i = 0; i < 9; i++) inv.setItem(i, i % 2 == 0 ? pane : item(Material.LIGHT_BLUE_STAINED_GLASS_PANE, " ", null));
        }

        void put(int slot, Material m, String name, String action, String lore) {
            inv.setItem(slot, item(m, name, lore));
            act.put(slot, action);
        }

        @Override public Inventory getInventory() { return inv; }
    }

    private static ItemStack item(Material m, String name, String lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            if (lore != null) meta.setLore(List.of(color("&7" + lore)));
            it.setItemMeta(meta);
        }
        return it;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Remote r)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != r.inv) return;
        String a = r.act.get(e.getRawSlot());
        if (a == null) return;
        if (!p.hasPermission("pokilive.admin")) return;
        if (stage == null) { p.closeInventory(); msg(p, "&c먼저 무대 가운데에 서서 객석을 보고 &e/공연 무대"); return; }
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.4f);
        if (a.equals("act:시작")) { p.closeInventory(); opening(null); }
        else if (a.equals("act:끝")) { p.closeInventory(); finale(); }
        else if (a.startsWith("fw:")) fireworks(a.substring(3));
        else if (a.startsWith("light:")) {
            String m = a.substring(6);
            if (m.equals("끄기")) stopLight(); else startLight(m);
            msg(p, m.equals("끄기") ? "조명 끔" : "조명: " + m);
        }
        else if (a.equals("spot")) msg(p, toggleSpot(p) ? "스포트라이트 켬" : "스포트라이트 끔");
        else if (a.equals("screen:끄기")) { removeScreen(); screenText = null; msg(p, "스크린 문구 끔"); }
        else if (a.equals("stop")) { stopAll(); msg(p, "연출 모두 멈춤"); }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Remote) e.setCancelled(true);
    }

    // ───────── 유틸 ─────────
    private void msg(CommandSender s, String m) {
        s.sendMessage(color(getConfig().getString("messages.prefix", "&d[공연] &f") + m));
    }

    static String color(String s) { return ChatColor.translateAlternateColorCodes('&', s); }
}

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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.block.Block;
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
    private boolean tvOn;            // 치지직 TV 켜져 있으면 스크린 문구 숨김 (영상 가리지 않게)
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
    /**
     * 위치는 월드 이름 + 숫자로 저장 (Location 통째 저장은 서버 켜질 때 월드가 늦게 잡히면 통째로 날아감).
     * 월드를 못 찾으면 나중에 명령어 쓸 때 다시 시도.
     */
    private void loadPlaces() {
        var c = getConfig();
        stage = place(c, "stage");
        double fx = c.getDouble("stage.front-x", 0), fz = c.getDouble("stage.front-z", 0);
        front = (fx == 0 && fz == 0) ? null : new Vector(fx, 0, fz);
        screenLoc = place(c, "screen");
        screenYaw = (float) c.getDouble("screen.yaw", 0);
        // 예전(1.1.2 이하) 형식에서 옮겨오기
        if (stage == null && c.isSet("stage.location")) {
            try { stage = c.getLocation("stage.location"); Vector v = c.getVector("stage.front"); if (v != null) front = v; } catch (Exception ignored) {}
        }
        if (screenLoc == null && c.isSet("screen.location")) {
            try { screenLoc = c.getLocation("screen.location"); } catch (Exception ignored) {}
        }
        if (stage != null && front != null && c.isSet("stage.location")) savePlaces();
    }

    private static Location place(org.bukkit.configuration.ConfigurationSection c, String k) {
        String wn = c.getString(k + ".world");
        if (wn == null || !c.isSet(k + ".x")) return null;
        World w = Bukkit.getWorld(wn);
        if (w == null) return null;
        return new Location(w, c.getDouble(k + ".x"), c.getDouble(k + ".y"), c.getDouble(k + ".z"));
    }

    private void ensurePlaces() {
        if ((stage == null || front == null) && getConfig().isSet("stage.world")) loadPlaces();
        if (screenLoc == null && getConfig().isSet("screen.world")) loadPlaces();
    }

    private void savePlaces() {
        var c = getConfig();
        c.set("stage.location", null);
        c.set("stage.front", null);
        c.set("screen.location", null);
        if (stage != null && stage.getWorld() != null) {
            c.set("stage.world", stage.getWorld().getName());
            c.set("stage.x", stage.getX()); c.set("stage.y", stage.getY()); c.set("stage.z", stage.getZ());
        }
        if (front != null) { c.set("stage.front-x", front.getX()); c.set("stage.front-z", front.getZ()); }
        if (screenLoc != null && screenLoc.getWorld() != null) {
            c.set("screen.world", screenLoc.getWorld().getName());
            c.set("screen.x", screenLoc.getX()); c.set("screen.y", screenLoc.getY()); c.set("screen.z", screenLoc.getZ());
        }
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
        ensurePlaces();
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
        if (sub.equals("티비")) {
            String op = a.length > 1 ? a[1] : "추가";
            List<String> tvs = tvs();
            if (op.equals("목록")) {
                if (tvs.isEmpty()) msg(s, "등록된 TV 없음 — TV 블록 보고 &e/공연 티비");
                for (int k = 0; k < tvs.size(); k++) msg(s, (k + 1) + ". " + tvs.get(k).replace(";", " "));
                return true;
            }
            if (op.equals("끄기")) { tvOff(s); return true; }
            if (op.equals("삭제") && a.length > 2 && a[2].equals("전체")) {
                getConfig().set("tvs", new ArrayList<String>()); saveConfig(); msg(s, "TV 등록 전부 지움"); return true;
            }
            if (!(s instanceof Player p)) return true;
            RayTraceResult hit = p.rayTraceBlocks(80);
            if (hit == null || hit.getHitBlock() == null) { msg(s, "&c영상 프레임(TV) 블록을 바라보고 다시 쳐줘"); return true; }
            Block b = hit.getHitBlock();
            String key = b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ();
            if (op.equals("삭제")) {
                msg(s, tvs.remove(key) ? "이 TV 등록 지움" : "&c이 블록은 등록된 TV가 아니야 (&e/공연 티비 목록&c)");
            } else if (tvs.contains(key)) {
                msg(s, "이미 등록된 TV야 (" + tvs.size() + "대)"); return true;
            } else {
                tvs.add(key);
                msg(s, "TV 등록 (" + b.getType().name().toLowerCase() + " " + key.replace(";", " ") + ") — 지금 " + tvs.size() + "대. 영상 틀면 전부 같이 나와");
            }
            getConfig().set("tvs", tvs); saveConfig();
            return true;
        }
        if (sub.equals("치지직") || sub.equals("다시보기") || sub.equals("클립")) {
            if (tvs().isEmpty()) { msg(s, "&c먼저 영상 프레임(TV)을 바라보고 &e/공연 티비"); return true; }
            String arg = a.length > 1 ? a[1] : (sub.equals("치지직") ? getConfig().getString("chzzk.channel", "") : "");
            if (arg.equals("끄기")) { tvOff(s); return true; }
            String what; java.util.concurrent.Callable<String> job;
            if (sub.equals("치지직")) {
                String id = channelId(arg);
                if (id.isEmpty()) { msg(s, "/공연 치지직 <채널ID 또는 치지직 링크>  (config 에 chzzk.channel 넣으면 생략 가능)"); return true; }
                what = "치지직 라이브"; job = () -> fetchHls(id);
            } else if (sub.equals("다시보기")) {
                String no = videoNo(arg);
                if (no.isEmpty()) { msg(s, "/공연 다시보기 <다시보기 링크 또는 영상 번호>  (chzzk.naver.com/video/숫자)"); return true; }
                what = "다시보기"; job = () -> fetchVod(no);
            } else {
                String uid = clipId(arg);
                if (uid.isEmpty()) { msg(s, "/공연 클립 <클립 링크 또는 클립 ID>  (chzzk.naver.com/clips/...)"); return true; }
                what = "클립"; job = () -> fetchClip(uid);
            }
            msg(s, what + " 주소 가져오는 중...");
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                String url = null, err = null;
                try { url = job.call(); } catch (Exception e) { err = e.getMessage(); }
                final String fu = url, fe = err;
                Bukkit.getScheduler().runTask(this, () -> {
                    if (fu == null) { msg(s, "&c가져오기 실패: " + fe); return; }
                    playOnTv(s, fu, what);
                });
            });
            return true;
        }
        if (sub.equals("리모컨")) {
            if (s instanceof Player p) p.openInventory(new Remote().inv);
            return true;
        }
        if (stage == null || front == null) { msg(s, "&c먼저 무대 가운데에 서서 객석을 보고 &e/공연 무대 &c를 쳐줘"); return true; }
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

    // ───────── 치지직 → WATERFrAMES TV ─────────
    static String channelId(String arg) {
        if (arg == null) return "";
        String t = arg.trim();
        int q = t.indexOf('?'); if (q >= 0) t = t.substring(0, q);
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        int sl = t.lastIndexOf('/'); if (sl >= 0) t = t.substring(sl + 1);
        return t.matches("[0-9a-fA-F]{32}") ? t.toLowerCase() : "";
    }

    /** 치지직 live-detail 에서 HLS 주소(토큰 포함)를 뽑음. 비동기에서 부를 것. */
    static String fetchHls(String id) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.chzzk.naver.com/service/v3/channels/" + id + "/live-detail"))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36")
                .timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<String> res = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                .send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new Exception("치지직 응답 " + res.statusCode());
        JsonObject root = JsonParser.parseString(res.body()).getAsJsonObject();
        JsonElement ce = root.get("content");
        if (ce == null || ce.isJsonNull()) throw new Exception("채널을 못 찾았어");
        JsonObject c = ce.getAsJsonObject();
        JsonElement st = c.get("status");
        if (st == null || st.isJsonNull() || !"OPEN".equals(st.getAsString())) throw new Exception("지금 방송 중이 아니야");
        JsonElement pj = c.get("livePlaybackJson");
        if (pj == null || pj.isJsonNull()) throw new Exception("방송 주소가 없어 (연령 제한 방송일 수 있어)");
        JsonObject pb = JsonParser.parseString(pj.getAsString()).getAsJsonObject();
        for (JsonElement m : pb.getAsJsonArray("media")) {
            JsonObject mo = m.getAsJsonObject();
            if (mo.has("mediaId") && "HLS".equals(mo.get("mediaId").getAsString()) && mo.has("path"))
                return mo.get("path").getAsString();
        }
        throw new Exception("HLS 주소가 없어");
    }

    static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";

    static String get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", UA)
                .timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<String> res = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                .send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new Exception("응답 " + res.statusCode());
        return res.body();
    }

    static JsonObject content(String body, String notFound) throws Exception {
        JsonElement ce = JsonParser.parseString(body).getAsJsonObject().get("content");
        if (ce == null || ce.isJsonNull()) throw new Exception(notFound);
        return ce.getAsJsonObject();
    }

    static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return (e == null || e.isJsonNull()) ? null : e.getAsString();
    }

    static String videoNo(String arg) {
        if (arg == null) return "";
        var m = java.util.regex.Pattern.compile("(?:video/)?(\\d{3,})").matcher(arg.trim());
        return m.find() ? m.group(1) : "";
    }

    static String clipId(String arg) {
        if (arg == null) return "";
        String t = arg.trim();
        int q = t.indexOf('?'); if (q >= 0) t = t.substring(0, q);
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        int sl = t.lastIndexOf('/'); if (sl >= 0) t = t.substring(sl + 1);
        return t.matches("[A-Za-z0-9]{5,20}") ? t : "";
    }

    /** 다시보기: 방송 직후 다시보기는 HLS 주소가 바로 있고, 정식 VOD 는 videoId+inKey 로 네이버 영상 서버를 거침. */
    static String fetchVod(String no) throws Exception {
        JsonObject c = content(get("https://api.chzzk.naver.com/service/v3/videos/" + no), "영상을 못 찾았어");
        String rewind = str(c, "liveRewindPlaybackJson");
        if (rewind != null) {
            for (JsonElement m : JsonParser.parseString(rewind).getAsJsonObject().getAsJsonArray("media")) {
                JsonObject mo = m.getAsJsonObject();
                if ("HLS".equals(str(mo, "mediaId")) && mo.has("path")) return mo.get("path").getAsString();
            }
        }
        String vid = str(c, "videoId"), key = str(c, "inKey");
        if (vid == null || key == null) throw new Exception("볼 수 없는 영상이야 (처리 중이거나 연령·구독 제한)");
        return neon(vid, key, false);
    }

    static String fetchClip(String uid) throws Exception {
        JsonObject c = content(get("https://api.chzzk.naver.com/service/v1/play-info/clip/" + uid), "클립을 못 찾았어");
        String vid = str(c, "videoId"), key = str(c, "inKey");
        if (vid == null || key == null) throw new Exception("볼 수 없는 클립이야");
        return neon(vid, key, true);
    }

    /** 네이버 영상 서버(MPD)에서 재생 주소: 클립은 mp4 한 파일, 다시보기는 HLS(m3u8) 우선. */
    static String neon(String videoId, String inKey, boolean preferMp4) throws Exception {
        String xml = get("https://apis.naver.com/neonplayer/vodplay/v2/playback/" + videoId + "?key=" + inKey);
        var mp4 = java.util.regex.Pattern.compile("<BaseURL>(https://[^<]+?\\.mp4\\?[^<]*)</BaseURL>").matcher(xml);
        var m3u = java.util.regex.Pattern.compile("nvod:m3u=\"([^\"]+)\"").matcher(xml);
        String a = mp4.find() ? mp4.group(1) : null, b = m3u.find() ? m3u.group(1) : null;
        String pick = preferMp4 ? (a != null ? a : b) : (b != null ? b : a);
        if (pick == null) throw new Exception("재생 주소를 못 찾았어");
        return pick.replace("&amp;", "&");
    }

    private void playOnTv(CommandSender s, String url, String what) {
        boolean ok = tvCommand("url " + url);
        tvCommand("alpha 255");
        tvCommand("pause false");
        if (ok) {
            tvOn = true;
            String keep = screenText;
            removeScreen();          // 영상 위에 글자가 덮이지 않게 숨김
            screenText = keep;       // 끄면 다시 띄울 수 있게 기억
        }
        msg(s, ok ? "TV " + tvs().size() + "대에 " + what + " 연결!" : "&cTV 명령 실패 — WATERFrAMES 가 서버에 있는지 확인해줘. 아래 주소를 복사해서 직접 넣어도 돼");
        if (s instanceof Player p) {
            TextComponent t = new TextComponent(color("&7[클릭해서 영상 주소 복사]"));
            t.setClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, url));
            p.spigot().sendMessage(t);
        }
    }

    private void tvOff(CommandSender s) {
        tvCommand("pause true");
        tvCommand("alpha 0");    // 멈춘 화면이 남지 않게 투명 → 뒤의 검은 스크린이 보임
        tvOn = false;
        if (screenText != null && screenLoc != null) showText(screenText);
        msg(s, "TV 멈춤" + (screenText != null ? " — 스크린 문구 다시 켬" : ""));
    }

    /** 등록된 TV 목록 ("월드;x;y;z"). 예전(1.1.x) tv.* 한 대는 자동으로 옮김. */
    private List<String> tvs() {
        var c = getConfig();
        List<String> out = new ArrayList<>(c.getStringList("tvs"));
        if (c.isSet("tv.x")) {
            String old = c.getString("tv.world", "world") + ";" + c.getInt("tv.x") + ";" + c.getInt("tv.y") + ";" + c.getInt("tv.z");
            if (!out.contains(old)) out.add(0, old);
            c.set("tv", null); c.set("tvs", out); saveConfig();
        }
        return out;
    }

    /** 등록된 TV 전부에 /waterframes edit 명령을 콘솔로 보냄. 하나라도 되면 true. */
    private boolean tvCommand(String rest) {
        boolean any = false;
        for (String t : tvs()) {
            String[] k = t.split(";");
            if (k.length < 4) continue;
            World w = Bukkit.getWorld(k[0]);
            if (w == null) continue;
            String cmd = "waterframes edit " + k[1] + " " + k[2] + " " + k[3] + " " + rest;
            if (!w.equals(Bukkit.getWorlds().get(0))) cmd = "execute in " + w.getKey() + " run " + cmd;
            try { any |= Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd); }
            catch (Exception e) { getLogger().warning("TV 명령 실패 (" + t + "): " + e.getMessage()); }
        }
        return any;
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
        msg(s, "&e/공연 티비 [목록|삭제|끄기] &f— 영상 프레임(TV) 보고 치면 등록 (여러 대 가능)");
        msg(s, "&e/공연 치지직 [채널ID] &f/ &e/공연 다시보기 <링크> &f/ &e/공연 클립 <링크> &f— 등록된 TV 전부에 틀기");
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        List<String> opts;
        if (a.length == 1) opts = List.of("리모컨", "무대", "스크린", "시작", "끝", "불꽃", "문구", "조명", "스포트", "멈춤", "티비", "치지직", "다시보기", "클립", "리로드");
        else if (a.length == 2) opts = switch (a[0]) {
            case "불꽃" -> FW;
            case "조명" -> concat(LIGHTS, "끄기");
            case "문구" -> List.of("끄기");
            case "스크린" -> List.of("돌리기");
            case "치지직", "다시보기", "클립" -> List.of("끄기");
            case "티비" -> List.of("목록", "삭제", "끄기");
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
        if (tvOn) return;    // TV 방송 중엔 글자 숨김 (끄면 마지막 문구가 다시 나옴)
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
    // 무대 아치에서 사람한테 내려오는 은은한 빛 원뿔 + 발밑 빛 원 + 주변 실제 밝기(보이지 않는 조명 블록을 보는 사람 화면에만)
    private final Map<UUID, Location> spotLight = new HashMap<>();
    static final Particle.DustOptions BEAM = new Particle.DustOptions(Color.fromRGB(0xFFF4DC), 0.7f);
    static final Particle.DustOptions BEAM_EDGE = new Particle.DustOptions(Color.fromRGB(0xFFE7A8), 0.9f);
    static final Particle.DustOptions POOL = new Particle.DustOptions(Color.fromRGB(0xFFF8E8), 1.1f);

    private boolean toggleSpot(Player t) {
        BukkitTask old = spots.remove(t.getUniqueId());
        if (old != null) { old.cancel(); clearSpotLight(t.getUniqueId()); return false; }
        spots.put(t.getUniqueId(), new BukkitRunnable() {
            int tick = 0;
            @Override public void run() {
                if (!t.isOnline()) { cancel(); spots.remove(t.getUniqueId()); clearSpotLight(t.getUniqueId()); return; }
                Location feet = t.getLocation();
                World w = feet.getWorld();
                tick++;
                // 빛이 나오는 곳: 무대가 있으면 아치 가운데 높이(사람 쪽 x), 없으면 머리 위 9칸
                Location src;
                if (stage != null && front != null && stage.getWorld() == w) {
                    Location arch = at(0, -2, 11.5);
                    src = feet.clone().add(0, arch.getY() - feet.getY(), 0)
                            .add(front.clone().multiply(-2.5));   // 살짝 무대 뒤쪽 위에서 비스듬히
                } else src = feet.clone().add(0, 9, 0);
                Vector d = feet.toVector().subtract(src.toVector());
                double len = d.length();
                Vector dir = d.clone().normalize();
                Vector u = dir.clone().crossProduct(new Vector(0, 1, 0));
                if (u.lengthSquared() < 1e-4) u = new Vector(1, 0, 0);
                u.normalize();
                Vector v = dir.clone().crossProduct(u).normalize();
                var r = ThreadLocalRandom.current();
                // 원뿔: 위는 좁고 아래로 갈수록 넓게, 듬성듬성 → 안개 낀 빛줄기 느낌
                for (int k = 0; k < 22; k++) {
                    double f = Math.sqrt(r.nextDouble());          // 아래쪽에 더 많이
                    double rad = 0.12 + f * 1.15;
                    double ang = r.nextDouble() * Math.PI * 2;
                    double rr = rad * (k % 3 == 0 ? 1.0 : Math.sqrt(r.nextDouble()));
                    Location p = src.clone().add(dir.clone().multiply(f * len))
                            .add(u.clone().multiply(Math.cos(ang) * rr)).add(v.clone().multiply(Math.sin(ang) * rr));
                    if (p.getY() < feet.getY() + 0.15) continue;
                    w.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, k % 3 == 0 ? BEAM_EDGE : BEAM, true);
                }
                // 발밑 빛 원 (가장자리 + 안쪽 조금)
                if (tick % 2 == 0) {
                    for (int k = 0; k < 20; k++) {
                        double ang = k * Math.PI / 10 + tick * 0.05;
                        w.spawnParticle(Particle.DUST, feet.clone().add(Math.cos(ang) * 1.25, 0.06, Math.sin(ang) * 1.25), 1, 0, 0, 0, 0, POOL, true);
                    }
                    for (int k = 0; k < 6; k++) {
                        double ang = r.nextDouble() * Math.PI * 2, rr = Math.sqrt(r.nextDouble()) * 1.1;
                        w.spawnParticle(Particle.DUST, feet.clone().add(Math.cos(ang) * rr, 0.06, Math.sin(ang) * rr), 1, 0, 0, 0, 0, POOL, true);
                    }
                }
                // 실제 밝기: 머리 높이 빈칸에 조명 블록(가짜, 보이지 않음)
                if (tick % 2 == 0) moveSpotLight(t);
            }
        }.runTaskTimer(this, 0L, 2L));
        return true;
    }

    private void moveSpotLight(Player t) {
        Location head = t.getLocation().add(0, 1, 0).getBlock().getLocation();
        if (!head.getBlock().getType().isAir()) head = t.getLocation().getBlock().getLocation();
        Location prev = spotLight.get(t.getUniqueId());
        if (prev != null && prev.equals(head)) {
            // 새로 가까이 온 사람한테도 보이게 가끔 다시 보냄
            if (ThreadLocalRandom.current().nextInt(10) != 0) return;
        }
        if (prev != null && !prev.equals(head)) restoreBlock(prev);
        if (!head.getBlock().getType().isAir()) { spotLight.remove(t.getUniqueId()); return; }
        var light = Bukkit.createBlockData("minecraft:light[level=15]");
        for (Player p : nearby(head)) p.sendBlockChange(head, light);
        spotLight.put(t.getUniqueId(), head);
    }

    private void clearSpotLight(UUID id) {
        Location prev = spotLight.remove(id);
        if (prev != null) restoreBlock(prev);
    }

    private void restoreBlock(Location l) {
        for (Player p : nearby(l)) p.sendBlockChange(l, l.getBlock().getBlockData());
    }

    private static List<Player> nearby(Location l) {
        List<Player> out = new ArrayList<>();
        if (l.getWorld() == null) return out;
        for (Player p : l.getWorld().getPlayers()) if (p.getLocation().distanceSquared(l) < 96 * 96) out.add(p);
        return out;
    }

    private void stopSpots() {
        for (var e : spots.entrySet()) e.getValue().cancel();
        for (UUID id : new ArrayList<>(spotLight.keySet())) clearSpotLight(id);
        spots.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        BukkitTask t = spots.remove(e.getPlayer().getUniqueId());
        if (t != null) t.cancel();
        clearSpotLight(e.getPlayer().getUniqueId());
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
            put(23, Material.SPECTRAL_ARROW, "&e스포트라이트 (나)", "spot", "위에서 내려오는 빛 + 발밑 빛 원, 한 번 더 누르면 끔");
            put(27, Material.END_ROD, "&f조명 — 반짝이", "light:반짝이", null);
            put(28, Material.RED_DYE, "&f조명 — 하트", "light:하트", null);
            put(29, Material.NOTE_BLOCK, "&f조명 — 음표", "light:음표", null);
            put(30, Material.CHERRY_LEAVES, "&f조명 — 벚꽃", "light:벚꽃", null);
            put(31, Material.AMETHYST_SHARD, "&f조명 — 별빛", "light:별빛", null);
            put(32, Material.GRAY_DYE, "&7조명 끄기", "light:끄기", null);
            put(34, Material.BLACK_CONCRETE, "&7스크린 문구 끄기", "screen:끄기", "문구 바꾸기는 /공연 문구 <내용>");
            put(33, Material.LIME_DYE, "&a치지직 라이브 켜기", "chzzk", "config 의 chzzk.channel 방송을 TV에 연결");
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
        ensurePlaces();
        String a = r.act.get(e.getRawSlot());
        if (a == null) return;
        if (!p.hasPermission("pokilive.admin")) return;
        if (stage == null || front == null) { p.closeInventory(); msg(p, "&c먼저 무대 가운데에 서서 객석을 보고 &e/공연 무대"); return; }
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
        else if (a.equals("chzzk")) { p.closeInventory(); p.performCommand("pokilive 치지직"); }
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

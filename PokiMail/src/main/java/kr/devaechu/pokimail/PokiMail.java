package kr.devaechu.pokimail;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 포키 우편함.
 *  - 운영진이 보낸 아이템·보상을 접속 안 해도 우편함에 넣어두고, 유저가 /우편함 에서 받음
 *  - /우편 지급 : 인벤토리에 먼저 넣고, 꽉 차서 남은 건 우편함으로 (후원 보상·전체 지급용, minecraft:give 대신)
 *  - 아이템은 give 명령어 형식 문자열(아이디[컴포넌트])로 저장 → 모드 아이템도 그대로
 *  - 다른 플러그인: PokiMail.giveOrMail(Player, ItemStack, String) (리플렉션으로 부르기 쉬운 static)
 */
public final class PokiMail extends JavaPlugin implements Listener, TabCompleter {

    static PokiMail inst;

    /** 우편 하나. spec 이 있으면 아이템, cmd 가 있으면 받을 때 콘솔 명령어 */
    static final class Mail {
        String id, spec, cmd, name, memo, from;
        int count;
        long at;
    }

    final Map<UUID, List<Mail>> boxes = new HashMap<>();
    private File dataFile;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PokiMail-io");
        t.setDaemon(true);
        return t;
    });
    MailGui gui;

    @Override
    public void onEnable() {
        inst = this;
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "mail.yml");
        load();
        expire();
        gui = new MailGui(this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(gui, this);
        for (String c : List.of("pokimail", "pokimailbox")) {
            var cmd = getCommand(c);
            if (cmd != null) { cmd.setExecutor(this); cmd.setTabCompleter(this); }
        }
        int n = 0;
        for (List<Mail> l : boxes.values()) n += l.size();
        getLogger().info("우편함 켜짐 — 받지 않은 우편 " + n + "개 (" + boxes.size() + "명)");
    }

    @Override
    public void onDisable() {
        if (gui != null) gui.closeAll();
        io.shutdown();
        try { io.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        inst = null;
    }

    // ───────── 저장 ─────────

    private void load() {
        boxes.clear();
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection sec = y.getConfigurationSection("players");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            UUID id;
            try { id = UUID.fromString(key); } catch (IllegalArgumentException e) { continue; }
            List<Mail> list = new ArrayList<>();
            for (Map<?, ?> m : sec.getMapList(key)) {
                Mail ml = new Mail();
                ml.id = str(m.get("id"));
                ml.spec = str(m.get("item"));
                ml.cmd = str(m.get("cmd"));
                ml.name = str(m.get("name"));
                ml.memo = str(m.get("memo"));
                ml.from = str(m.get("from"));
                ml.count = m.get("count") instanceof Number nn ? nn.intValue() : 1;
                ml.at = m.get("at") instanceof Number nn ? nn.longValue() : System.currentTimeMillis();
                if (ml.id == null) ml.id = newId();
                if (ml.spec == null && ml.cmd == null) continue;
                list.add(ml);
            }
            if (!list.isEmpty()) boxes.put(id, list);
        }
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    /** 바뀔 때만. 문자열은 메인 스레드에서 만들고 파일 쓰기는 작업자 스레드 */
    void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of("포키 우편함 — 받지 않은 우편 (직접 고치지 말고 /우편 명령어로)"));
        for (Map.Entry<UUID, List<Mail>> e : boxes.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            List<Map<String, Object>> list = new ArrayList<>();
            for (Mail m : e.getValue()) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("id", m.id);
                if (m.spec != null) o.put("item", m.spec);
                if (m.cmd != null) o.put("cmd", m.cmd);
                if (m.name != null) o.put("name", m.name);
                if (m.memo != null) o.put("memo", m.memo);
                if (m.from != null) o.put("from", m.from);
                o.put("count", m.count);
                o.put("at", m.at);
                list.add(o);
            }
            y.set("players." + e.getKey(), list);
        }
        String text = y.saveToString();
        File f = dataFile;
        Runnable job = () -> {
            try {
                f.getParentFile().mkdirs();
                File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
                Files.writeString(tmp.toPath(), text, StandardCharsets.UTF_8);
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ex) {
                getLogger().warning("mail.yml 저장 실패: " + ex);
            }
        };
        if (io.isShutdown()) job.run(); else io.execute(job);
    }

    private void expire() {
        int days = getConfig().getInt("expire-days", 0);
        if (days <= 0) return;
        long cut = System.currentTimeMillis() - days * 86_400_000L;
        int n = 0;
        for (List<Mail> l : boxes.values()) {
            int before = l.size();
            l.removeIf(m -> m.at < cut);
            n += before - l.size();
        }
        boxes.values().removeIf(List::isEmpty);
        if (n > 0) { getLogger().info(days + "일 지난 우편 " + n + "개 지움"); save(); }
    }

    static String newId() { return Long.toString(System.nanoTime(), 36) + Integer.toString((int) (Math.random() * 1296), 36); }

    List<Mail> box(UUID id) { return boxes.getOrDefault(id, List.of()); }

    // ───────── 넣기 ─────────

    /** 우편함에 넣기 (저장·알림 포함). 같은 아이템·메모·보낸이면 합침 */
    void add(UUID to, Mail m) {
        List<Mail> l = boxes.computeIfAbsent(to, k -> new ArrayList<>());
        if (m.spec != null) {
            for (Mail o : l) {
                if (m.spec.equals(o.spec) && eq(m.memo, o.memo) && eq(m.from, o.from) && o.cmd == null) {
                    o.count += m.count;
                    o.at = m.at;
                    save();
                    announce(to, m);
                    return;
                }
            }
        }
        l.add(m);
        int max = Math.max(10, getConfig().getInt("max-per-player", 200));
        while (l.size() > max) {
            Mail old = l.remove(0);
            getLogger().warning("우편함 가득 → 가장 오래된 우편 지움: " + to + " " + label(old));
        }
        save();
        announce(to, m);
    }

    private static boolean eq(String a, String b) { return a == null ? b == null : a.equals(b); }

    private void announce(UUID to, Mail m) {
        Player p = Bukkit.getPlayer(to);
        if (p == null) return;
        TextComponent t = new TextComponent(TextComponent.fromLegacyText(color("&6[우편함] &f" + label(m) + " &7이(가) 도착했어요 ")));
        TextComponent open = new TextComponent(TextComponent.fromLegacyText(color("&e[열기]")));
        open.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/우편함"));
        open.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(color("&a눌러서 우편함 열기"))));
        t.addExtra(open);
        p.spigot().sendMessage(t);
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.4f);
    }

    /** 아이템 문자열 → 아이템 (틀리면 예외) */
    static ItemStack parse(String spec) {
        return Bukkit.getItemFactory().createItemStack(spec);
    }

    /** 아이템 → give 형식 문자열 (모드 아이템 컴포넌트 포함) */
    static String specOf(ItemStack it) {
        String id = it.getType().getKey().toString();
        ItemMeta meta = it.getItemMeta();
        String comp = meta == null ? "" : meta.getAsComponentString();
        if (comp == null || comp.equals("[]")) comp = "";
        return id + comp;
    }

    static Mail itemMail(String spec, int count, String memo, String from) {
        Mail m = new Mail();
        m.id = newId(); m.spec = spec; m.count = count; m.memo = memo; m.from = from; m.at = System.currentTimeMillis();
        return m;
    }

    /** 인벤토리에 넣고 남은 개수 */
    static int putIn(Player p, ItemStack proto, int count) {
        int max = Math.max(1, proto.getMaxStackSize());
        int left = count;
        while (left > 0) {
            int n = Math.min(left, max);
            ItemStack s = proto.clone();
            s.setAmount(n);
            int notIn = 0;
            for (ItemStack r : p.getInventory().addItem(s).values()) notIn += r.getAmount();
            left -= n - notIn;
            if (notIn > 0) break;
        }
        return left;
    }

    /**
     * 다른 플러그인용: 인벤토리에 넣고, 남으면 우편함으로. 우편함에 넣은 개수를 돌려줌.
     * (PokiGacha 뽑기권·당첨 아이템 등)
     */
    public static int giveOrMail(Player p, ItemStack item, String from) {
        if (p == null || item == null || item.getType().isAir()) return 0;
        int left = putIn(p, item, item.getAmount());
        if (left > 0 && inst != null) {
            inst.add(p.getUniqueId(), itemMail(specOf(item), left, null, from == null ? inst.defFrom() : from));
            p.sendMessage(color("&6[우편함] &e인벤토리가 꽉 차서 " + left + "개는 우편함으로 보냈어요 &7(/우편함)"));
        }
        return left;
    }

    /** 다른 플러그인용: 바로 우편함으로 */
    public static void mail(UUID to, ItemStack item, String memo, String from) {
        if (inst == null || item == null || item.getType().isAir()) return;
        inst.add(to, itemMail(specOf(item), item.getAmount(), memo, from == null ? inst.defFrom() : from));
    }

    /** 다른 플러그인용: 아이템 하나를 count 개만큼 바로 우편함으로 (64개 넘어도 됨) */
    public static void mailCount(UUID to, ItemStack item, int count, String memo, String from) {
        if (inst == null || item == null || item.getType().isAir() || count <= 0) return;
        inst.add(to, itemMail(specOf(item), count, memo, from == null ? inst.defFrom() : from));
    }

    String defFrom() { return getConfig().getString("default-from", "포키 운영진"); }

    // ───────── 받기 ─────────

    /** 우편 하나 받기. 결과 문구 (null = 다 받음) */
    String claim(Player p, Mail m) {
        List<Mail> l = boxes.get(p.getUniqueId());
        if (l == null || !l.contains(m)) return "이미 받은 우편이에요";
        if (m.cmd != null) {
            l.remove(m);
            if (l.isEmpty()) boxes.remove(p.getUniqueId());
            save();
            String c = m.cmd.replace("%player%", p.getName());
            try { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.startsWith("/") ? c.substring(1) : c); }
            catch (RuntimeException ex) { getLogger().warning("우편 명령어 실패: " + c + " — " + ex); }
            getLogger().info("[우편 받음] " + p.getName() + " " + label(m));
            return null;
        }
        ItemStack proto;
        try { proto = parse(m.spec); }
        catch (Exception ex) { return "이 우편 아이템을 만들 수 없어요 — 운영진에게 문의해 주세요"; }
        int left = putIn(p, proto, m.count);
        int got = m.count - left;
        if (got <= 0) return "인벤토리가 꽉 찼어요";
        if (left > 0) { m.count = left; save(); getLogger().info("[우편 받음] " + p.getName() + " " + m.spec + " x" + got + " (남음 " + left + ")"); return "인벤토리가 꽉 차서 " + got + "개만 받았어요 (" + left + "개 남음)"; }
        l.remove(m);
        if (l.isEmpty()) boxes.remove(p.getUniqueId());
        save();
        getLogger().info("[우편 받음] " + p.getName() + " " + m.spec + " x" + got);
        return null;
    }

    /** 전부 받기 → [받은 개수, 못 받은 개수] */
    int[] claimAll(Player p) {
        int ok = 0, fail = 0;
        for (Mail m : new ArrayList<>(box(p.getUniqueId()))) {
            if (claim(p, m) == null) ok++; else fail++;
        }
        return new int[]{ok, fail};
    }

    static String label(Mail m) {
        if (m.cmd != null) return m.name == null ? "보상" : m.name.replace('_', ' ');
        String n = m.name;
        if (n == null) {
            try {
                ItemStack it = parse(m.spec);
                ItemMeta im = it.getItemMeta();
                n = im != null && im.hasDisplayName() ? ChatColor.stripColor(im.getDisplayName())
                        : im != null && im.hasItemName() ? ChatColor.stripColor(im.getItemName())
                        : it.getType().getKey().getKey().replace('_', ' ');
            } catch (Exception e) {
                n = m.spec.replaceAll("\\[.*", "");
            }
        }
        return n + (m.count > 1 ? " x" + m.count : "");
    }

    // ───────── 접속 알림 ─────────

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!getConfig().getBoolean("notify-on-join", true)) return;
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline()) return;
            int n = box(p.getUniqueId()).size();
            if (n == 0) return;
            TextComponent t = new TextComponent(TextComponent.fromLegacyText(color("&6[우편함] &f받지 않은 우편이 &e" + n + "개 &f있어요 ")));
            TextComponent open = new TextComponent(TextComponent.fromLegacyText(color("&e[열기]")));
            open.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/우편함"));
            open.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(color("&a눌러서 우편함 열기"))));
            t.addExtra(open);
            p.spigot().sendMessage(t);
        }, 60L);
    }

    // ───────── 명령어 ─────────

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (c.getName().equals("pokimailbox")) {
            if (!(s instanceof Player p)) { msg(s, "&c플레이어만 쓸 수 있어요"); return true; }
            if (a.length > 0 && (a[0].equals("모두받기") || a[0].equals("전부"))) {
                int[] r = claimAll(p);
                msg(p, r[0] == 0 && r[1] == 0 ? "&7우편함이 비어 있어요" : "&a" + r[0] + "개 받았어요" + (r[1] > 0 ? " &7(" + r[1] + "개는 인벤토리가 꽉 차서 남겨둠)" : ""));
                return true;
            }
            gui.open(p, 0);
            return true;
        }
        if (a.length == 0) { help(s); return true; }
        String from = defFrom();
        switch (a[0]) {
            case "지급", "보내기" -> {
                boolean give = a[0].equals("지급");
                if (a.length < 3) { msg(s, "&c/우편 " + a[0] + " <마크닉|@a" + (give ? "" : "|전체") + "> <아이템[컴포넌트]> [개수] [메모]"); return true; }
                String[] parsed = splitItem(String.join(" ", Arrays.copyOfRange(a, 2, a.length)));
                if (parsed == null) { msg(s, "&c아이템을 못 읽었어요 &7(괄호·따옴표 짝 확인)"); return true; }
                String spec = parsed[0];
                int count = 1;
                String memo = null;
                String rest = parsed[1].trim();
                if (!rest.isEmpty()) {
                    String[] r = rest.split(" ", 2);
                    if (r[0].matches("\\d{1,5}")) { count = Math.max(1, Integer.parseInt(r[0])); memo = r.length > 1 ? r[1].trim() : null; }
                    else memo = rest;
                }
                ItemStack proto;
                try { proto = parse(spec); }
                catch (Exception ex) { msg(s, "&c아이템을 만들 수 없어요: &f" + spec + " &7(" + ex.getMessage() + ")"); return true; }
                spec = specOf(proto); // 정리된 형식으로
                sendItems(s, a[1], spec, proto, count, memo, from, give);
            }
            case "손" -> {
                if (!(s instanceof Player p)) { msg(s, "&c플레이어만 쓸 수 있어요"); return true; }
                if (a.length < 2) { msg(s, "&c/우편 손 <마크닉|@a|전체> [개수] [메모]  &7— 손에 든 아이템 (내 손 아이템은 그대로)"); return true; }
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (hand.getType().isAir()) { msg(s, "&c손에 아이템을 들어 주세요"); return true; }
                int count = hand.getAmount();
                String memo = null;
                if (a.length > 2) {
                    if (a[2].matches("\\d{1,5}")) { count = Math.max(1, Integer.parseInt(a[2])); if (a.length > 3) memo = String.join(" ", Arrays.copyOfRange(a, 3, a.length)); }
                    else memo = String.join(" ", Arrays.copyOfRange(a, 2, a.length));
                }
                sendItems(s, a[1], specOf(hand), hand, count, memo, from, false);
            }
            case "명령" -> {
                if (a.length < 4) { msg(s, "&c/우편 명령 <마크닉|@a|전체> <보일이름(띄어쓰기 _)> <명령어...>  &7예) /우편 명령 전체 코인_1000개 eco give %player% 1000"); return true; }
                String name = a[2];
                String cmd = String.join(" ", Arrays.copyOfRange(a, 3, a.length));
                List<UUID> to = targets(s, a[1], true);
                if (to == null) return true;
                for (UUID id : to) {
                    Mail m = new Mail();
                    m.id = newId(); m.cmd = cmd; m.name = name; m.count = 1; m.from = from; m.at = System.currentTimeMillis();
                    add(id, m);
                }
                getLogger().info("[우편 보냄] " + s.getName() + " → " + a[1] + " (" + to.size() + "명) 명령: " + cmd);
                msg(s, "&a" + to.size() + "명 우편함에 &f" + name.replace('_', ' ') + " &a보냈어요 &7(받을 때 실행: " + cmd + ")");
            }
            case "보기" -> {
                if (a.length < 2) { msg(s, "&c/우편 보기 <마크닉>"); return true; }
                OfflinePlayer op = find(a[1]);
                if (op == null) { msg(s, "&c그런 마크닉이 없어요 (서버에 들어온 적 있는 사람만)"); return true; }
                List<Mail> l = box(op.getUniqueId());
                msg(s, "&f" + op.getName() + " 님 우편함 &7(" + l.size() + "개)");
                SimpleDateFormat f = new SimpleDateFormat("MM/dd HH:mm");
                for (int i = 0; i < l.size() && i < 30; i++) {
                    Mail m = l.get(i);
                    msg(s, "&7" + (i + 1) + ". &f" + label(m) + " &8" + f.format(new Date(m.at)) + (m.memo == null ? "" : " &7- " + m.memo));
                }
            }
            case "회수" -> {
                if (a.length < 3) { msg(s, "&c/우편 회수 <마크닉> <번호|전부>  &7(번호는 /우편 보기)"); return true; }
                OfflinePlayer op = find(a[1]);
                if (op == null) { msg(s, "&c그런 마크닉이 없어요"); return true; }
                List<Mail> l = boxes.get(op.getUniqueId());
                if (l == null || l.isEmpty()) { msg(s, "&7우편함이 비어 있어요"); return true; }
                if (a[2].equals("전부")) {
                    int n = l.size();
                    boxes.remove(op.getUniqueId());
                    save();
                    getLogger().info("[우편 회수] " + s.getName() + " → " + op.getName() + " 전부 " + n + "개");
                    msg(s, "&a" + op.getName() + " 님 우편 " + n + "개를 지웠어요");
                } else if (a[2].matches("\\d+") && Integer.parseInt(a[2]) >= 1 && Integer.parseInt(a[2]) <= l.size()) {
                    Mail m = l.remove(Integer.parseInt(a[2]) - 1);
                    if (l.isEmpty()) boxes.remove(op.getUniqueId());
                    save();
                    getLogger().info("[우편 회수] " + s.getName() + " → " + op.getName() + " " + label(m));
                    msg(s, "&a지웠어요: &f" + label(m));
                } else msg(s, "&c번호를 확인해 주세요 (/우편 보기 " + op.getName() + ")");
            }
            case "리로드" -> { reloadConfig(); expire(); msg(s, "&a설정 다시 읽음"); }
            default -> help(s);
        }
        return true;
    }

    /** 지급(인벤 먼저) / 보내기(우편함만) */
    private void sendItems(CommandSender s, String target, String spec, ItemStack proto, int count, String memo, String from, boolean give) {
        List<UUID> to = targets(s, target, !give);
        if (to == null) return;
        int inInv = 0, mailed = 0;
        for (UUID id : to) {
            Player p = give ? Bukkit.getPlayer(id) : null;
            int left = p != null ? putIn(p, proto, count) : count;
            if (p != null && left < count) inInv++;
            if (left > 0) {
                add(id, itemMail(spec, left, memo, from));
                mailed++;
                if (p != null) p.sendMessage(color("&6[우편함] &e인벤토리가 꽉 차서 " + left + "개는 우편함으로 보냈어요 &7(/우편함)"));
            }
        }
        getLogger().info("[우편 " + (give ? "지급" : "보냄") + "] " + s.getName() + " → " + target + " (" + to.size() + "명) " + spec + " x" + count);
        if (!(s instanceof Player) && to.size() > 1) return; // 콘솔 전체 지급(후원 보상)은 조용히
        msg(s, "&a" + to.size() + "명에게 " + (give ? "지급" : "우편 보냄") + " &f" + spec.replaceAll("\\[.*", "") + " x" + count
                + (give ? " &7(인벤 " + inInv + "명 · 우편함 " + mailed + "명)" : ""));
    }

    /** 마크닉 / @a(접속자) / 전체(들어온 적 있는 모두, allowAll 일 때) */
    private List<UUID> targets(CommandSender s, String t, boolean allowAll) {
        List<UUID> out = new ArrayList<>();
        if (t.equals("@a")) { for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getUniqueId()); return out; }
        if (t.equals("전체")) {
            if (!allowAll) { msg(s, "&c지급은 @a(접속자)까지만 — 접속 안 한 사람까지는 /우편 보내기 전체"); return null; }
            for (OfflinePlayer op : Bukkit.getOfflinePlayers()) if (op.hasPlayedBefore() || op.isOnline()) out.add(op.getUniqueId());
            return out;
        }
        OfflinePlayer op = find(t);
        if (op == null) { msg(s, "&c그런 마크닉이 없어요: " + t + " (서버에 들어온 적 있는 사람만)"); return null; }
        out.add(op.getUniqueId());
        return out;
    }

    private static OfflinePlayer find(String name) {
        Player on = Bukkit.getPlayerExact(name);
        if (on != null) return on;
        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) if (op.getName() != null && op.getName().equalsIgnoreCase(name)) return op;
        return null;
    }

    /** "cobblemon:rare_candy[custom_name='\"사탕\"'] 3 메모" → [아이템, 나머지]. 괄호·따옴표 안 띄어쓰기 허용 */
    static String[] splitItem(String in) {
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < in.length(); i++) {
            char ch = in.charAt(i);
            if (quote != 0) {
                if (ch == '\\') { i++; continue; }
                if (ch == quote) quote = 0;
                continue;
            }
            if (ch == '"' || ch == '\'') quote = ch;
            else if (ch == '[' || ch == '{') depth++;
            else if (ch == ']' || ch == '}') depth--;
            else if (ch == ' ' && depth == 0) return new String[]{in.substring(0, i), in.substring(i + 1)};
        }
        if (depth != 0 || quote != 0) return null;
        return new String[]{in, ""};
    }

    private static void help(CommandSender s) {
        msg(s, "&f/우편 지급 <마크닉|@a> <아이템> [개수] [메모] &7- 인벤에 넣고 남으면 우편함 (minecraft:give 대신)");
        msg(s, "&f/우편 보내기 <마크닉|@a|전체> <아이템> [개수] [메모] &7- 바로 우편함으로 (전체 = 접속 안 한 사람 포함)");
        msg(s, "&f/우편 손 <마크닉|@a|전체> [개수] [메모] &7- 손에 든 아이템과 같은 걸 우편으로 (내 손은 그대로)");
        msg(s, "&f/우편 명령 <대상> <보일이름> <명령어> &7- 받을 때 콘솔 실행 (%player%) · 돈·칭호 등");
        msg(s, "&f/우편 보기 <마크닉> &7/ &f/우편 회수 <마크닉> <번호|전부> &7/ &f/우편 리로드");
    }

    static void msg(CommandSender s, String m) { s.sendMessage(color("&6[우편] &r" + m)); }

    private static final Pattern HEX = Pattern.compile("&#([0-9a-fA-F]{6})");

    static String color(String s) {
        if (s == null) return "";
        Matcher m = HEX.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            StringBuilder rep = new StringBuilder("§x");
            for (char c : m.group(1).toCharArray()) rep.append('§').append(c);
            m.appendReplacement(sb, Matcher.quoteReplacement(rep.toString()));
        }
        m.appendTail(sb);
        return ChatColor.translateAlternateColorCodes('&', sb.toString());
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (c.getName().equals("pokimailbox")) { if (a.length == 1 && "모두받기".startsWith(a[0])) out.add("모두받기"); return out; }
        if (a.length == 1) { for (String o : List.of("지급", "보내기", "손", "명령", "보기", "회수", "리로드")) if (o.startsWith(a[0])) out.add(o); return out; }
        if (a.length == 2) {
            if (!List.of("보기", "회수").contains(a[0])) { out.add("@a"); if (!a[0].equals("지급")) out.add("전체"); }
            String pre = a[1].toLowerCase(Locale.ROOT);
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase(Locale.ROOT).startsWith(pre)) out.add(p.getName());
            out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(pre));
            return out;
        }
        if (a.length == 3 && a[0].equals("회수")) { out.add("전부"); return out; }
        return out;
    }
}

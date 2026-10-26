package kr.devaechu.pokihelp;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.permissions.Permissible;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class PokiHelp extends JavaPlugin implements Listener, TabCompleter {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(KST);
    private static final String PREFIX = "&d[문의] &f";

    private Store store;
    private Gui gui;
    private final Map<UUID, Long> lastSent = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        store = new Store(this);
        store.load();
        gui = new Gui(this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(gui, this);
        for (String c : new String[]{"phelp", "preport", "phelpbox"}) {
            var cmd = getCommand(c);
            if (cmd != null) {
                cmd.setExecutor(this);
                cmd.setTabCompleter(this);
            }
        }
        getLogger().info("미처리 문의 " + store.openCount() + "건 · 운영진 권한 " + getConfig().getStringList("staff-permissions")
                + " · 문의함 " + (getConfig().getBoolean("gui.resource-pack", true) ? "리소스팩" : "기본 상자"));
    }

    @Override
    public void onDisable() {
        if (gui != null) gui.closeAll();
    }

    Store store() {
        return store;
    }

    // ───────── 공통 ─────────

    static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    static void send(CommandSender to, String s) {
        to.sendMessage(color(PREFIX + s));
    }

    /** 10/26 14:52 · 5분 전 */
    static String time(long ms) {
        if (ms <= 0) return "-";
        long min = Math.max(0, (System.currentTimeMillis() - ms) / 60000);
        String ago = min < 1 ? "방금" : min < 60 ? min + "분 전" : min < 1440 ? (min / 60) + "시간 전" : (min / 1440) + "일 전";
        return FMT.format(Instant.ofEpochMilli(ms)) + " · " + ago;
    }

    boolean isStaff(Permissible p) {
        if (p.isOp()) return true;
        for (String perm : getConfig().getStringList("staff-permissions")) {
            if (!perm.isBlank() && p.hasPermission(perm.trim())) return true;
        }
        return false;
    }

    private List<Player> onlineStaff() {
        List<Player> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) if (isStaff(p)) out.add(p);
        return out;
    }

    /** PokiName 한글닉 (PlaceholderAPI 있을 때만) */
    private String nickOf(Player p) {
        try {
            if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI") && Bukkit.getPluginManager().isPluginEnabled("PokiName")) {
                String s = Papi.apply(p, "%pokiname_nick%");
                s = ChatColor.stripColor(color(s));
                if (s != null && !s.isBlank() && !s.contains("%")) return s;
            }
        } catch (Throwable ignored) {
        }
        return p.getName();
    }

    private static TextComponent button(String text, String hover, ClickEvent.Action action, String value) {
        TextComponent c = new TextComponent(TextComponent.fromLegacyText(color(text)));
        c.setClickEvent(new ClickEvent(action, value));
        c.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(color(hover))));
        return c;
    }

    private static void line(Player p, BaseComponent... parts) {
        p.spigot().sendMessage(parts);
    }

    // ───────── 명령어 ─────────

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        switch (cmd.getName()) {
            case "phelp" -> ask(sender, label, args, null);
            case "preport" -> ask(sender, label, args, Ticket.Kind.REPORT);
            case "phelpbox" -> box(sender, args);
            default -> {
                return false;
            }
        }
        return true;
    }

    /** /문의 [종류] <내용>, /신고 <내용> */
    private void ask(CommandSender sender, String label, String[] args, Ticket.Kind forced) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("게임 안에서 써 주세요.");
            return;
        }
        if (args.length == 0) {
            usage(p, label, forced);
            return;
        }
        if (forced == null && args.length == 1 && (args[0].equals("목록") || args[0].equalsIgnoreCase("list"))) {
            mine(p);
            return;
        }
        Ticket.Kind kind = forced;
        int from = 0;
        if (kind == null) {
            kind = Ticket.Kind.parse(args[0]);
            if (kind != null) from = 1;
            else kind = Ticket.Kind.OTHER;
        }
        String msg = ChatColor.stripColor(color(String.join(" ", Arrays.copyOfRange(args, from, args.length))))
                .replace('§', ' ').trim();
        if (msg.isEmpty()) {
            usage(p, label, forced);
            return;
        }
        int min = getConfig().getInt("min-length", 2);
        int max = getConfig().getInt("max-length", 200);
        if (msg.length() < min) {
            send(p, "&c내용을 " + min + "글자 이상 적어 주세요.");
            return;
        }
        if (msg.length() > max) {
            send(p, "&c내용이 너무 길어요. (" + msg.length() + "/" + max + "글자)");
            return;
        }
        if (!isStaff(p)) {
            long cd = getConfig().getLong("cooldown-seconds", 60) * 1000L;
            long left = lastSent.getOrDefault(p.getUniqueId(), 0L) + cd - System.currentTimeMillis();
            if (left > 0) {
                send(p, "&c" + ((left + 999) / 1000) + "초 뒤에 다시 보낼 수 있어요.");
                return;
            }
            int maxOpen = getConfig().getInt("max-open-per-player", 3);
            if (maxOpen > 0 && store.openCount(p.getUniqueId()) >= maxOpen) {
                send(p, "&c아직 확인 안 된 문의가 " + maxOpen + "건 있어요. 운영진이 확인하면 다시 보낼 수 있어요. &7(/문의 목록)");
                return;
            }
        }
        Location l = p.getLocation();
        Ticket t = new Ticket(store.nextId(), p.getUniqueId(), p.getName(), nickOf(p), kind, msg,
                l.getWorld() == null ? "world" : l.getWorld().getName(), l.getX(), l.getY(), l.getZ(),
                System.currentTimeMillis());
        store.add(t);
        lastSent.put(p.getUniqueId(), t.created);
        getLogger().info("새 문의 #" + t.id + " [" + t.kind.label + "] " + t.name + " @ " + t.where() + " : " + t.message);

        List<Player> staff = onlineStaff();
        staff.remove(p);
        send(p, "&a접수됐어요! &7(#" + t.id + " " + t.kind.label + ") &f"
                + (staff.isEmpty() ? "지금 접속한 운영진이 없어서, 들어오면 바로 볼 수 있게 남겨뒀어요." : "운영진이 곧 확인할 거예요."));
        for (Player s : staff) alert(s, t);
    }

    private void usage(Player p, String label, Ticket.Kind forced) {
        if (forced == Ticket.Kind.REPORT) {
            send(p, "&f/" + label + " <내용> &7— 운영진한테만 보내져요. 누가, 어디서, 무엇을 했는지 적어 주세요.");
            return;
        }
        send(p, "&f/" + label + " [종류] <내용> &7— 채팅 대신 운영진한테만 보내져요.");
        List<BaseComponent> row = new ArrayList<>();
        row.add(new TextComponent(color("  &7종류: ")));
        for (Ticket.Kind k : Ticket.Kind.values()) {
            row.add(button(k.tag() + " ", "&f/" + label + " " + k.label + " <내용>", ClickEvent.Action.SUGGEST_COMMAND, "/" + label + " " + k.label + " "));
        }
        row.add(button("&7[내 문의]", "&f내가 보낸 문의 보기", ClickEvent.Action.RUN_COMMAND, "/" + label + " 목록"));
        line(p, row.toArray(new BaseComponent[0]));
    }

    private void mine(Player p) {
        List<Ticket> list = store.of(p.getUniqueId());
        if (list.isEmpty()) {
            send(p, "보낸 문의가 없어요.");
            return;
        }
        send(p, "내 문의 (최근 5건)");
        for (Ticket t : list.subList(0, Math.min(5, list.size()))) {
            String head = t.message.length() > 20 ? t.message.substring(0, 20) + "…" : t.message;
            p.sendMessage(color("  &7#" + t.id + " " + t.kind.tag() + " &f" + head + " "
                    + (t.done ? "&a처리됨" : "&e확인 중")));
        }
    }

    /** 운영진 한 명한테 새 문의 알림 */
    private void alert(Player s, Ticket t) {
        s.sendMessage(color("&d&m                                        "));
        s.sendMessage(color(PREFIX + "&f새 문의 &7#" + t.id + " " + t.kind.tag() + " &f" + t.who()));
        for (String l : Gui.wrap(t.message, 40)) s.sendMessage(color("  &e" + l));
        line(s, new TextComponent(color("  ")),
                button("&a[이동] ", "&f" + t.where(), ClickEvent.Action.RUN_COMMAND, "/문의함 이동 " + t.id),
                button("&b[처리] ", "&f처리 완료로 바꾸고 보낸 사람한테 알림", ClickEvent.Action.RUN_COMMAND, "/문의함 처리 " + t.id),
                button("&d[문의함]", "&f문의함 열기", ClickEvent.Action.RUN_COMMAND, "/문의함"));
        s.sendMessage(color("&d&m                                        "));
        if (getConfig().getBoolean("notify.title", true)) {
            s.sendTitle(color("&d새 문의 #" + t.id), color(t.kind.tag() + " &f" + t.who()), 5, 50, 10);
        }
        String snd = getConfig().getString("notify.sound", "");
        if (snd != null && !snd.isBlank()) {
            Sound sound = Registry.SOUNDS.get(NamespacedKey.minecraft(snd.toLowerCase(Locale.ROOT).replace("minecraft:", "")));
            if (sound != null) s.playSound(s.getLocation(), sound, 1f, 1.2f);
        }
    }

    /** /문의함 [목록|이동|처리|리로드] */
    private void box(CommandSender sender, String[] args) {
        if (!isStaff(sender)) {
            send(sender, "&c운영진만 쓸 수 있어요.");
            return;
        }
        if (args.length == 0) {
            if (sender instanceof Player p) gui.open(p);
            else list(sender);
            return;
        }
        switch (args[0]) {
            case "목록", "list" -> list(sender);
            case "리로드", "reload" -> {
                gui.closeAll();
                reloadConfig();
                store.load();
                send(sender, "&a다시 불러왔어요. 미처리 " + store.openCount() + "건");
            }
            case "이동", "tp", "처리", "done" -> {
                Ticket t = args.length > 1 ? byId(args[1]) : null;
                if (t == null) {
                    send(sender, "&c없는 문의 번호예요.");
                    return;
                }
                if (args[0].equals("처리") || args[0].equals("done")) resolve(sender, t);
                else if (sender instanceof Player p) teleport(p, t);
            }
            default -> send(sender, "&f/문의함 &7[목록|이동 <번호>|처리 <번호>|리로드]");
        }
    }

    private Ticket byId(String s) {
        try {
            return store.get(Integer.parseInt(s.replace("#", "")));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void list(CommandSender sender) {
        List<Ticket> open = store.list(false, null);
        send(sender, "미처리 문의 " + open.size() + "건");
        for (Ticket t : open.subList(0, Math.min(10, open.size()))) {
            sender.sendMessage(color("  &7#" + t.id + " " + t.kind.tag() + " &f" + t.who() + " &7" + time(t.created) + " &f" + t.message));
        }
    }

    // ───────── 처리 (GUI 와 명령어 공통) ─────────

    void teleport(Player staff, Ticket t) {
        Player target = Bukkit.getPlayer(t.uuid);
        Location dest;
        if (target != null && target.isOnline()) {
            dest = target.getLocation();
            send(staff, t.who() + " &f님한테 이동 &7(#" + t.id + ")");
        } else {
            World w = Bukkit.getWorld(t.world);
            if (w == null) {
                send(staff, "&c접수한 월드(" + t.world + ")를 못 찾았어요.");
                return;
            }
            dest = new Location(w, t.x, t.y, t.z);
            send(staff, "보낸 사람이 접속 중이 아니라 문의 보낸 곳으로 이동 &7(#" + t.id + " " + t.where() + ")");
        }
        if (!staff.teleport(dest)) send(staff, "&c이동하지 못했어요.");
    }

    void resolve(CommandSender staff, Ticket t) {
        if (t.done) {
            send(staff, "&7#" + t.id + " 은 이미 처리됐어요.");
            return;
        }
        t.done = true;
        t.doneBy = staff.getName();
        t.doneAt = System.currentTimeMillis();
        Player target = Bukkit.getPlayer(t.uuid);
        t.notified = target != null;
        store.save();
        if (target != null) tellDone(target, t);
        send(staff, "&a#" + t.id + " 처리 완료" + (target != null ? "" : " &7(보낸 사람이 다음에 접속하면 알려줌)"));
        for (Player s : onlineStaff()) {
            if (s != staff) send(s, "&7" + staff.getName() + " 님이 #" + t.id + " 처리");
        }
    }

    void reopen(CommandSender staff, Ticket t) {
        t.done = false;
        t.doneBy = "";
        t.doneAt = 0;
        t.notified = true;
        store.save();
        send(staff, "&e#" + t.id + " 미처리로 되돌림");
    }

    void delete(Player staff, Ticket t) {
        if (store.remove(t.id)) {
            send(staff, "&c#" + t.id + " 삭제");
            getLogger().info(staff.getName() + " 이(가) 문의 #" + t.id + " 삭제: " + t.message);
        }
    }

    private void tellDone(Player p, Ticket t) {
        String head = t.message.length() > 20 ? t.message.substring(0, 20) + "…" : t.message;
        send(p, "&a운영진이 문의를 확인했어요! &7(#" + t.id + " " + head + ")");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline()) return;
            // 꺼져 있을 때 처리된 내 문의
            boolean changed = false;
            for (Ticket t : store.of(p.getUniqueId())) {
                if (t.done && !t.notified) {
                    tellDone(p, t);
                    t.notified = true;
                    changed = true;
                }
            }
            if (changed) store.save();
            // 운영진이면 미처리 건수
            if (isStaff(p)) {
                long n = store.openCount();
                if (n > 0) {
                    line(p, new TextComponent(color(PREFIX + "미처리 문의 &e" + n + "건 ")),
                            button("&d[문의함 열기]", "&f/문의함", ClickEvent.Action.RUN_COMMAND, "/문의함"));
                }
            }
        }, 60L);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length != 1) return List.of();
        List<String> opts = switch (cmd.getName()) {
            case "phelp" -> List.of("버그", "신고", "질문", "기타", "목록");
            case "phelpbox" -> isStaff(sender) ? List.of("목록", "이동", "처리", "리로드") : List.of();
            default -> List.of();
        };
        List<String> out = new ArrayList<>();
        for (String o : opts) if (o.startsWith(args[0])) out.add(o);
        return out;
    }
}

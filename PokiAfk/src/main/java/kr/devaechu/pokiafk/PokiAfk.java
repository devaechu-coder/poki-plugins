package kr.devaechu.pokiafk;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 오래 잠수하면 킥. 1초에 한 번 접속자만 확인 (가벼움).
 * 활동: 시점 돌리기, 땅 위에서 걷기(물속·탈것은 빼서 물길·잠수풀로 못 버팀), 채팅, 명령어, 블록, 우클릭, 인벤토리.
 */
public final class PokiAfk extends JavaPlugin implements Listener, TabCompleter {

    /** 마지막 활동 시각 (채팅은 비동기라 Concurrent) */
    private final Map<UUID, Long> last = new ConcurrentHashMap<>();
    private final Set<UUID> warned = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) last.put(p.getUniqueId(), now);
        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("pokiafk");
        if (cmd != null) { cmd.setExecutor(this); cmd.setTabCompleter(this); }
        Bukkit.getScheduler().runTaskTimer(this, this::check, 20L, 20L);
        getLogger().info("잠수 킥 켜짐: " + minutes() + "분" + (onlyFull() ? " (꽉 찼을 때만)" : ""));
    }

    private int minutes() { return Math.max(1, getConfig().getInt("kick-after-minutes", 60)); }
    private boolean onlyFull() { return getConfig().getBoolean("only-when-full.enabled", false); }

    // ───────── 활동 ─────────

    private void active(Player p) {
        last.put(p.getUniqueId(), System.currentTimeMillis());
        warned.remove(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location f = e.getFrom(), t = e.getTo();
        if (t == null) return;
        Player p = e.getPlayer();
        boolean look = f.getYaw() != t.getYaw() || f.getPitch() != t.getPitch();
        boolean walk = (f.getX() != t.getX() || f.getZ() != t.getZ())
                && !p.isInsideVehicle() && !p.isInWater() && !p.isGliding();
        if (look || walk) active(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncPlayerChatEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        last.put(id, System.currentTimeMillis());
        Bukkit.getScheduler().runTask(this, () -> warned.remove(id));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(PlayerCommandPreprocessEvent e) { active(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent e) { active(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreak(BlockBreakEvent e) { active(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlace(BlockPlaceEvent e) { active(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInv(InventoryClickEvent e) { if (e.getWhoClicked() instanceof Player p) active(p); }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { active(e.getPlayer()); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        last.remove(e.getPlayer().getUniqueId());
        warned.remove(e.getPlayer().getUniqueId());
    }

    // ───────── 1초마다 확인 ─────────

    private void check() {
        long now = System.currentTimeMillis();
        long limit = minutes() * 60_000L;
        long warnMs = Math.max(0, getConfig().getInt("warn-before-seconds", 60)) * 1000L;

        if (onlyFull()) {
            int free = Bukkit.getMaxPlayers() - Bukkit.getOnlinePlayers().size();
            if (free > getConfig().getInt("only-when-full.free-slots", 2)) { warned.clear(); return; }
        }

        List<Player> kick = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("pokiafk.exempt")) continue;
            long idle = now - last.computeIfAbsent(p.getUniqueId(), k -> now);
            if (idle >= limit) kick.add(p);
            else if (warnMs > 0 && idle >= limit - warnMs && warned.add(p.getUniqueId())) warn(p, (limit - idle + 999) / 1000);
        }
        if (kick.isEmpty()) return;

        // 꽉 찼을 때만 모드: 잠수 제일 긴 사람부터, 자리가 생길 만큼만
        if (onlyFull()) {
            kick.sort((a, b) -> Long.compare(last.getOrDefault(a.getUniqueId(), now), last.getOrDefault(b.getUniqueId(), now)));
            int need = getConfig().getInt("only-when-full.free-slots", 2) + 1 - (Bukkit.getMaxPlayers() - Bukkit.getOnlinePlayers().size());
            if (need < kick.size()) kick = kick.subList(0, Math.max(0, need));
        }
        for (Player p : kick) kick(p);
    }

    private void warn(Player p, long seconds) {
        p.sendTitle(fill(getConfig().getString("messages.warn-title", "&e잠수 중이에요"), p, seconds),
                fill(getConfig().getString("messages.warn-subtitle", "&f{seconds}초 뒤에 접속이 끊겨요"), p, seconds), 10, 80, 20);
        p.sendMessage(fill(getConfig().getString("messages.warn-chat", ""), p, seconds));
    }

    private void kick(Player p) {
        String name = p.getName();
        last.remove(p.getUniqueId());
        warned.remove(p.getUniqueId());
        p.kickPlayer(fill(getConfig().getString("messages.kick", "&e잠수로 접속이 끊겼어요"), p, 0));
        getLogger().info(name + " 잠수 " + minutes() + "분 → 킥");
        String staff = getConfig().getString("messages.staff", "");
        if (staff != null && !staff.isBlank()) {
            String m = fill(staff.replace("{name}", name), null, 0);
            for (Player o : Bukkit.getOnlinePlayers()) if (o.hasPermission("pokiafk.admin")) o.sendMessage(m);
        }
    }

    private String fill(String s, Player p, long seconds) {
        if (s == null) return "";
        s = s.replace("{minutes}", String.valueOf(minutes())).replace("{seconds}", String.valueOf(seconds)).replace("\\n", "\n");
        if (p != null) s = s.replace("{name}", p.getName());
        return color(s);
    }

    private static final Pattern HEX = Pattern.compile("&#([0-9a-fA-F]{6})");

    static String color(String s) {
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

    // ───────── /잠수 ─────────

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        String sub = a.length > 0 ? a[0] : "상태";
        switch (sub) {
            case "시간" -> {
                int m;
                try { m = Integer.parseInt(a.length > 1 ? a[1] : ""); } catch (NumberFormatException e) { msg(s, "&c/잠수 시간 <분>  &7예) /잠수 시간 60"); return true; }
                if (m < 5 || m > 1440) { msg(s, "&c5~1440분 사이로 적어 주세요"); return true; }
                getConfig().set("kick-after-minutes", m);
                saveConfig();
                warned.clear();
                msg(s, "&a이제 &f" + m + "분 &a잠수하면 킥해요");
            }
            case "꽉참만" -> {
                String v = a.length > 1 ? a[1] : "";
                if (!v.equals("켜기") && !v.equals("끄기")) { msg(s, "&c/잠수 꽉참만 켜기 [빈자리] | 끄기"); return true; }
                getConfig().set("only-when-full.enabled", v.equals("켜기"));
                if (a.length > 2) {
                    try { getConfig().set("only-when-full.free-slots", Math.max(0, Integer.parseInt(a[2]))); }
                    catch (NumberFormatException ignored) { }
                }
                saveConfig();
                warned.clear();
                msg(s, v.equals("켜기")
                        ? "&a서버가 거의 꽉 찼을 때(빈자리 " + getConfig().getInt("only-when-full.free-slots", 2) + "개 이하)만 킥해요"
                        : "&a사람 수와 상관없이 " + minutes() + "분 잠수면 킥해요");
            }
            case "리로드" -> {
                reloadConfig();
                warned.clear();
                msg(s, "&a설정 다시 읽음 · " + minutes() + "분" + (onlyFull() ? " (꽉 찼을 때만)" : ""));
            }
            default -> {
                long now = System.currentTimeMillis();
                msg(s, "&f잠수 킥: &e" + minutes() + "분" + (onlyFull() ? " &7(꽉 찼을 때만, 빈자리 " + getConfig().getInt("only-when-full.free-slots", 2) + "개 이하)" : " &7(언제나)")
                        + " &7· 접속 " + Bukkit.getOnlinePlayers().size() + "/" + Bukkit.getMaxPlayers());
                List<Player> ps = new ArrayList<>(Bukkit.getOnlinePlayers());
                ps.sort((x, y) -> Long.compare(last.getOrDefault(x.getUniqueId(), now), last.getOrDefault(y.getUniqueId(), now)));
                int n = 0;
                for (Player p : ps) {
                    long idle = (now - last.getOrDefault(p.getUniqueId(), now)) / 60_000L;
                    if (idle < 1) break;
                    msg(s, "&7- &f" + p.getName() + " &e" + idle + "분" + (p.hasPermission("pokiafk.exempt") ? " &7(제외)" : ""));
                    if (++n >= 10) break;
                }
                if (n == 0) msg(s, "&71분 넘게 잠수 중인 사람이 없어요");
            }
        }
        return true;
    }

    private static void msg(CommandSender s, String m) { s.sendMessage(color("&6[잠수] &r" + m)); }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) { for (String o : List.of("상태", "시간", "꽉참만", "리로드")) if (o.startsWith(a[0])) out.add(o); }
        else if (a.length == 2 && a[0].equals("꽉참만")) { for (String o : List.of("켜기", "끄기")) if (o.startsWith(a[1])) out.add(o); }
        else if (a.length == 2 && a[0].equals("시간")) out.add("60");
        return out;
    }
}

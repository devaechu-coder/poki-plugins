package kr.devaechu.pokimaintenance;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 포키 서버 점검 모드.
 * - /점검 시작 [종료 예정] : 운영진 말고는 접속 차단 + 접속 중인 일반 유저 내보내기 + 서버 목록 문구 "[점검 중]"
 * - /점검 끝 : 다시 열기
 * 점검 상태는 state.yml 에 저장 → 서버를 다시 켜도 유지.
 * 런처는 서버 목록 문구의 "[점검 중]" 을 보고 점검을 알아챈다.
 */
public final class PokiMaintenance extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private boolean on;
    private String until; // 종료 예정 (자유 글자, 예: 22:00, 오늘 밤 10시)
    private File stateFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        stateFile = new File(getDataFolder(), "state.yml");
        loadState();
        Bukkit.getPluginManager().registerEvents(this, this);
        PluginCommand cmd = getCommand("maintenance");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        getLogger().info(on ? "점검 모드 켜져 있음" + (until != null ? " (" + until + " 종료 예정)" : "") : "점검 모드 꺼짐");
    }

    // ---------- 운영진 판단 ----------

    private boolean isStaff(Player p) {
        if (p.isOp() || p.hasPermission("poki.maintenance.bypass")) return true;
        for (String s : getConfig().getStringList("staff")) if (s.equalsIgnoreCase(p.getName())) return true;
        return false;
    }

    // ---------- 이벤트 ----------

    // LuckPerms 권한이 붙은 뒤에 확인하려고 HIGH
    @EventHandler(priority = EventPriority.HIGH)
    public void onLogin(PlayerLoginEvent e) {
        if (!on || e.getResult() != PlayerLoginEvent.Result.ALLOWED) return;
        if (isStaff(e.getPlayer())) return;
        e.disallow(PlayerLoginEvent.Result.KICK_OTHER, kickMessage());
    }

    @EventHandler
    public void onPing(ServerListPingEvent e) {
        if (!on) return;
        String u = until != null
                ? getConfig().getString("motd-until-format", "{until} 종료 예정").replace("{until}", until)
                : getConfig().getString("motd-until-unknown", "잠시 후 다시 열려요");
        List<String> lines = getConfig().getStringList("motd");
        if (lines.isEmpty()) lines = List.of("&c&l[점검 중] &f포키 서버", "&7{until}");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(2, lines.size()); i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines.get(i).replace("{until}", u));
        }
        e.setMotd(color(sb.toString()));
    }

    private String kickMessage() {
        String u = until != null
                ? getConfig().getString("until-format", "&e{until} &f종료 예정").replace("{until}", until)
                : getConfig().getString("until-unknown", "&f점검이 끝나면 다시 들어올 수 있어요.");
        List<String> lines = getConfig().getStringList("kick-message");
        if (lines.isEmpty()) lines = List.of("&c&l[ 포키 서버 점검 중 ]", "", "{until}");
        return color(String.join("\n", lines).replace("{until}", u));
    }

    // ---------- 명령어 ----------

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "상태";
        switch (sub) {
            case "시작", "start", "on" -> {
                on = true;
                until = a.length > 1 ? String.join(" ", Arrays.copyOfRange(a, 1, a.length)).trim() : null;
                if (until != null && until.isEmpty()) until = null;
                saveState();
                s.sendMessage(color("&a점검 모드를 켰어요" + (until != null ? " &7(" + until + " 종료 예정)" : "") + "&a. 운영진만 접속할 수 있어요."));
                getLogger().info(s.getName() + " 님이 점검 시작" + (until != null ? " (" + until + ")" : ""));
                kickOthers();
            }
            case "끝", "종료", "stop", "off" -> {
                on = false;
                until = null;
                saveState();
                s.sendMessage(color("&a점검 모드를 껐어요. 이제 모두 접속할 수 있어요."));
                getLogger().info(s.getName() + " 님이 점검 끝");
            }
            case "리로드", "reload" -> {
                reloadConfig();
                s.sendMessage(color("&a설정을 다시 불러왔어요."));
            }
            case "상태", "status" -> {
                s.sendMessage(color(on
                        ? "&e지금 점검 중이에요" + (until != null ? " &7(" + until + " 종료 예정)" : "")
                        : "&a지금은 점검 중이 아니에요."));
                usage(s, label);
            }
            default -> usage(s, label);
        }
        return true;
    }

    private void usage(CommandSender s, String label) {
        s.sendMessage(color("&e/" + label + " 시작 [종료 예정] &7- 예: /" + label + " 시작 22:00"));
        s.sendMessage(color("&e/" + label + " 끝 &7- 점검 끝내고 다시 열기"));
        s.sendMessage(color("&e/" + label + " 상태 &7- 지금 상태 보기"));
    }

    /** 일반 유저에게 알리고 잠시 뒤 내보냄 */
    private void kickOthers() {
        List<Player> targets = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) if (!isStaff(p)) targets.add(p);
        if (targets.isEmpty()) return;
        String msg = color(getConfig().getString("broadcast-start", "&c[점검] &f서버 점검을 시작해요."));
        for (Player p : targets) p.sendMessage(msg);
        long delay = Math.max(0, getConfig().getLong("kick-delay-seconds", 10)) * 20L;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!on) return; // 그 사이에 취소했으면 그대로 둠
            for (Player p : Bukkit.getOnlinePlayers()) if (!isStaff(p)) p.kickPlayer(kickMessage());
        }, delay);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) for (String o : new String[]{"시작", "끝", "상태", "리로드"}) if (o.startsWith(a[0])) out.add(o);
        return out;
    }

    // ---------- 저장 ----------

    private void loadState() {
        if (!stateFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(stateFile);
        on = y.getBoolean("on", false);
        until = y.getString("until");
    }

    private void saveState() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("on", on);
        y.set("until", until);
        try {
            getDataFolder().mkdirs();
            y.save(stateFile);
        } catch (IOException ex) {
            getLogger().warning("state.yml 저장 실패: " + ex.getMessage());
        }
    }

    static String color(String s) {
        if (s == null) return "";
        char[] b = s.toCharArray();
        for (int i = 0; i < b.length - 1; i++) {
            if (b[i] == '&' && "0123456789AaBbCcDdEeFfKkLlMmNnOoRr".indexOf(b[i + 1]) > -1) {
                b[i] = '§';
                b[i + 1] = Character.toLowerCase(b[i + 1]);
            }
        }
        return new String(b);
    }
}

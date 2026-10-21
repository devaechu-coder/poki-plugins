package kr.devaechu.pokiname;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitTask;
import java.util.HashMap;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 포키 서버 이름표 플러그인.
 * 패킷을 건드리지 않고 Bukkit 기본 API(스코어보드 팀, 플레이어 목록 이름, 탭 머리말)만 쓴다.
 * 아크라이트 같은 하이브리드 서버에서 모드 차원 이동을 방해하지 않게 하려는 목적.
 */
public final class PokiName extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final String TEAM_PREFIX = "pn_";
    private static final Pattern HEX = Pattern.compile("&#([0-9a-fA-F]{6})");

    /** uuid -> [마크 닉, 한글닉] */
    private final Map<UUID, String[]> nicks = new LinkedHashMap<>();
    private File dataFile;
    private static final String TAG = "pokiname_tag";
    /** 플레이어 머리 위 한글닉 글씨 */
    private final Map<UUID, TextDisplay> tags = new HashMap<>();
    private final Map<UUID, String> tagText = new HashMap<>();
    private BukkitTask tagTask;
    /** 플레이어가 지금 월드에 머문 틱 수 (접속·차원 이동 직후엔 이름표를 잠깐 안 띄움) */
    private final Map<UUID, String> lastWorld = new HashMap<>();
    private final Map<UUID, Integer> stableTicks = new HashMap<>();
    private int tickCount = 0;
    /** 소지금 순위 (Vault) */
    private MoneyRank moneyRank;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "nicknames.yml");
        loadNicks();

        Bukkit.getPluginManager().registerEvents(this, this);
        PluginCommand cmd = getCommand("pnick");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        cleanupStrayTags();
        for (Player p : Bukkit.getOnlinePlayers()) apply(p);
        refreshHeaders();
        tagTask = Bukkit.getScheduler().runTaskTimer(this, this::tickTags, 1L, 1L);

        moneyRank = new MoneyRank(this);
        moneyRank.start();
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new PokiExpansion(this).register();
            getLogger().info("PlaceholderAPI 연결: %pokiname_nick%, %pokiname_money_1% 등 사용 가능");
        }
        getLogger().info("한글닉 " + nicks.size() + "개 불러옴");
    }

    @Override
    public void onDisable() {
        if (tagTask != null) tagTask.cancel();
        if (moneyRank != null) moneyRank.stop();
        for (UUID id : new ArrayList<>(tags.keySet())) removeTag(id);
    }

    // ---------- 이벤트 ----------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        String[] v = nicks.get(p.getUniqueId());
        if (v != null && !v[0].equals(p.getName())) { // 마크 닉이 바뀌었으면 갱신
            v[0] = p.getName();
            saveNicks();
        }
        apply(p);
        refreshHeaders();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID qid = e.getPlayer().getUniqueId();
        removeTag(qid);
        lastWorld.remove(qid);
        stableTicks.remove(qid);
        // 나간 직후엔 아직 접속자로 잡혀서 1틱 뒤에 갱신
        Bukkit.getScheduler().runTaskLater(this, this::refreshHeaders, 1L);
    }

    // ---------- 적용 ----------

    private void apply(Player p) {
        FileConfiguration c = getConfig();
        String[] v = nicks.get(p.getUniqueId());
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        String teamName = teamName(p.getUniqueId());
        Team team = sb.getTeam(teamName);

        if (v == null) {
            removeTag(p.getUniqueId());
            tagText.remove(p.getUniqueId());
            if (team != null) team.unregister();
            p.setPlayerListName(null);
            p.setDisplayName(null);
            return;
        }
        String nick = v[1];

        if (team == null) team = sb.registerNewTeam(teamName);
        Team old = sb.getEntryTeam(p.getName());
        if (old != null && !old.getName().equals(teamName)) old.removeEntry(p.getName());
        if (displayMode()) {
            // 원래 이름표는 숨기고, 한글닉 글씨를 따로 띄운다 (tickTags)
            team.setPrefix("");
            team.setSuffix("");
            team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
            tagText.put(p.getUniqueId(), color(c.getString("nametag.display-text", "&f{nick}").replace("{nick}", nick).replace("{name}", p.getName())));
            TextDisplay d = tags.get(p.getUniqueId());
            if (d != null && d.isValid()) d.setText(tagText.get(p.getUniqueId()));
        } else {
            // 팀 접두사 + 원래 이름
            removeTag(p.getUniqueId());
            tagText.remove(p.getUniqueId());
            team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.ALWAYS);
            team.setPrefix(color(c.getString("nametag.prefix", "&f{nick} &7").replace("{nick}", nick)));
            team.setSuffix(color(c.getString("nametag.suffix", "").replace("{nick}", nick)));
            try {
                team.setColor(ChatColor.valueOf(c.getString("nametag.name-color", "GRAY").toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                team.setColor(ChatColor.GRAY);
            }
        }
        team.addEntry(p.getName());

        // Tab 목록 이름 / 채팅 이름
        p.setPlayerListName(color(c.getString("tab-name", "&f{nick}").replace("{nick}", nick).replace("{name}", p.getName())));
        p.setDisplayName(color(c.getString("display-name", "{nick}").replace("{nick}", nick).replace("{name}", p.getName())));
    }

    private boolean displayMode() {
        return !"prefix".equalsIgnoreCase(getConfig().getString("nametag.mode", "display"));
    }

    /** 매 틱: 한글닉 글씨를 플레이어 머리 위로 옮긴다. */
    private void tickTags() {
        if (!displayMode()) return;
        tickCount++;
        double offset = 0.6;
        try { offset = Double.parseDouble(getConfig().getString("nametag.height-offset", "0.6")); } catch (NumberFormatException ignored) { }
        int waitTicks = 20;
        boolean seeThrough = "true".equalsIgnoreCase(getConfig().getString("nametag.see-through", "false"));
        try { waitTicks = Integer.parseInt(getConfig().getString("nametag.spawn-delay-ticks", "20")); } catch (NumberFormatException ignored) { }

        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            String text = tagText.get(id);
            if (text == null) continue;

            // 접속·차원 이동 직후: 이름표를 지우고 잠깐 기다렸다가 다시 띄움 (로딩 중 잔상 방지)
            String worldName = p.getWorld().getName();
            if (!worldName.equals(lastWorld.get(id))) {
                lastWorld.put(id, worldName);
                stableTicks.put(id, 0);
                removeTag(id);
                continue;
            }
            int stable = stableTicks.merge(id, 1, Integer::sum);

            boolean hidden = p.isDead() || p.getGameMode() == GameMode.SPECTATOR || p.isInvisible();
            if (hidden || stable < waitTicks) { removeTag(id); continue; }

            Location at = p.getLocation().add(0, p.getHeight() + offset, 0);
            TextDisplay d = tags.get(id);
            if (d != null && (!d.isValid() || !d.getWorld().getName().equals(worldName)
                    || d.getLocation().distanceSquared(at) > 64)) {
                // 사라졌거나, 다른 월드거나, 멀리 순간이동(8칸 넘게) → 새로 만듦
                removeTag(id);
                d = null;
            }
            if (d == null) {
                d = spawnTag(p, at, text);
                if (d == null) continue;
            } else {
                d.teleport(at);
            }
            d.setSeeThrough(seeThrough && !p.isSneaking());
        }

        // 5초마다 주인 없는 이름표 글씨 정리
        if (tickCount % 100 == 0) cleanupOrphanTags();
    }

    private TextDisplay spawnTag(Player p, Location at, String text) {
        try {
            World w = p.getWorld();
            TextDisplay d = w.spawn(at, TextDisplay.class);
            d.setPersistent(false);
            d.addScoreboardTag(TAG);
            d.setBillboard(Display.Billboard.CENTER);
            d.setTeleportDuration(1);
            d.setViewRange(0.5f);
            d.setShadowed(false);
            d.setText(text);
            p.hideEntity(this, d); // 본인 화면엔 안 보이게
            tags.put(p.getUniqueId(), d);
            return d;
        } catch (RuntimeException ex) {
            getLogger().warning("이름표 생성 실패 (" + p.getName() + "): " + ex.getMessage());
            return null;
        }
    }

    private void removeTag(UUID id) {
        TextDisplay d = tags.remove(id);
        if (d != null && d.isValid()) d.remove();
    }

    /** 지금 관리 중이 아닌 이름표 글씨를 지운다 (잔상 정리) */
    private void cleanupOrphanTags() {
        java.util.Set<UUID> live = new java.util.HashSet<>();
        for (TextDisplay d : tags.values()) if (d.isValid()) live.add(d.getUniqueId());
        for (World w : Bukkit.getWorlds())
            for (TextDisplay d : w.getEntitiesByClass(TextDisplay.class))
                if (d.getScoreboardTags().contains(TAG) && !live.contains(d.getUniqueId())) d.remove();
    }

    /** 서버가 꺼질 때 못 지운 이름표 글씨 정리 */
    private void cleanupStrayTags() {
        for (World w : Bukkit.getWorlds())
            for (TextDisplay d : w.getEntitiesByClass(TextDisplay.class))
                if (d.getScoreboardTags().contains(TAG)) d.remove();
    }

    private void refreshHeaders() {
        FileConfiguration c = getConfig();
        List<String> header = c.getStringList("header");
        List<String> footer = c.getStringList("footer");
        String online = String.valueOf(Bukkit.getOnlinePlayers().size());
        String max = String.valueOf(Bukkit.getMaxPlayers());
        for (Player p : Bukkit.getOnlinePlayers()) {
            String[] v = nicks.get(p.getUniqueId());
            String shown = v != null ? v[1] : p.getName();
            p.setPlayerListHeaderFooter(fill(header, p, shown, online, max), fill(footer, p, shown, online, max));
        }
    }

    private String fill(List<String> lines, Player p, String shown, String online, String max) {
        if (lines == null || lines.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines.get(i).replace("{player}", shown).replace("{name}", p.getName())
                    .replace("{online}", online).replace("{max}", max));
        }
        return color(sb.toString());
    }

    // ---------- 명령어 ----------

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("pokiname.admin")) {
            s.sendMessage(color("&c권한이 없어요."));
            return true;
        }
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "설정", "set" -> {
                if (a.length < 3) { usage(s, label); return true; }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) { s.sendMessage(color("&c" + a[1] + " 님이 접속해 있지 않아요. (접속 중인 플레이어만 설정 가능)")); return true; }
                String nick = String.join(" ", java.util.Arrays.copyOfRange(a, 2, a.length));
                nicks.put(t.getUniqueId(), new String[]{t.getName(), nick});
                saveNicks();
                apply(t);
                refreshHeaders();
                s.sendMessage(color("&a" + t.getName() + " &f→ &e" + nick + " &f로 설정했어요."));
            }
            case "해제", "remove" -> {
                if (a.length < 2) { usage(s, label); return true; }
                UUID id = findByName(a[1]);
                if (id == null) { s.sendMessage(color("&c" + a[1] + " 님은 한글닉이 없어요.")); return true; }
                nicks.remove(id);
                saveNicks();
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t != null) apply(t);
                else {
                    Team team = Bukkit.getScoreboardManager().getMainScoreboard().getTeam(teamName(id));
                    if (team != null) team.unregister();
                }
                refreshHeaders();
                s.sendMessage(color("&a" + a[1] + " 님의 한글닉을 지웠어요."));
            }
            case "목록", "list" -> {
                if (nicks.isEmpty()) { s.sendMessage(color("&7등록된 한글닉이 없어요.")); return true; }
                s.sendMessage(color("&e한글닉 목록 (" + nicks.size() + "명)"));
                for (String[] v : nicks.values()) s.sendMessage(color("&7- &f" + v[0] + " &7→ &e" + v[1]));
            }
            case "리로드", "reload" -> {
                reloadConfig();
                loadNicks();
                if (moneyRank != null) { moneyRank.start(); }
                for (Player p : Bukkit.getOnlinePlayers()) apply(p);
                refreshHeaders();
                s.sendMessage(color("&a설정을 다시 불러왔어요."));
            }
            default -> usage(s, label);
        }
        return true;
    }

    private void usage(CommandSender s, String label) {
        s.sendMessage(color("&e/" + label + " 설정 <마크닉> <한글닉> &7- 한글닉 지정"));
        s.sendMessage(color("&e/" + label + " 해제 <마크닉> &7- 한글닉 지우기"));
        s.sendMessage(color("&e/" + label + " 목록 &7- 등록된 한글닉 보기"));
        s.sendMessage(color("&e/" + label + " 리로드 &7- config.yml 다시 불러오기"));
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (!s.hasPermission("pokiname.admin")) return out;
        if (a.length == 1) {
            for (String o : new String[]{"설정", "해제", "목록", "리로드"}) if (o.startsWith(a[0])) out.add(o);
        } else if (a.length == 2) {
            String sub = a[0].toLowerCase(Locale.ROOT);
            if (sub.equals("설정") || sub.equals("set")) {
                for (Player p : Bukkit.getOnlinePlayers())
                    if (p.getName().toLowerCase(Locale.ROOT).startsWith(a[1].toLowerCase(Locale.ROOT))) out.add(p.getName());
            } else if (sub.equals("해제") || sub.equals("remove")) {
                for (String[] v : nicks.values())
                    if (v[0].toLowerCase(Locale.ROOT).startsWith(a[1].toLowerCase(Locale.ROOT))) out.add(v[0]);
            }
        }
        return out;
    }

    // ---------- 다른 곳에서 쓰는 한글닉 조회 ----------

    /** UUID 로 한글닉 찾기. 없으면 fallback(보통 마크닉) */
    String nickOf(UUID id, String fallback) {
        String[] v = nicks.get(id);
        return v != null ? v[1] : fallback;
    }

    /** 마크닉으로 한글닉 찾기 (접속 안 해도 됨). 없으면 마크닉 그대로 */
    String nickByName(String name) {
        UUID id = findByName(name);
        return id != null ? nicks.get(id)[1] : name;
    }

    MoneyRank moneyRank() {
        return moneyRank;
    }

    // ---------- 저장 ----------

    private UUID findByName(String name) {
        for (Map.Entry<UUID, String[]> e : nicks.entrySet())
            if (e.getValue()[0].equalsIgnoreCase(name)) return e.getKey();
        return null;
    }

    private void loadNicks() {
        nicks.clear();
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection sec = y.getConfigurationSection("players");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            try {
                ConfigurationSection ps = sec.getConfigurationSection(key);
                if (ps == null || ps.getString("nick") == null) continue;
                String name = ps.getString("name");
                nicks.put(UUID.fromString(key), new String[]{name == null ? "?" : name, ps.getString("nick")});
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void saveNicks() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, String[]> e : nicks.entrySet()) {
            y.set("players." + e.getKey() + ".name", e.getValue()[0]);
            y.set("players." + e.getKey() + ".nick", e.getValue()[1]);
        }
        try {
            getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("nicknames.yml 저장 실패: " + ex.getMessage());
        }
    }

    // ---------- 유틸 ----------

    private static String teamName(UUID id) {
        return TEAM_PREFIX + id.toString().replace("-", "").substring(0, 13);
    }

    /** &a 같은 색 코드와 &#RRGGBB 헥스 색을 마크 색 코드로 바꾼다. */
    static String color(String s) {
        if (s == null) return "";
        Matcher m = HEX.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            StringBuilder rep = new StringBuilder("§x");
            for (char ch : m.group(1).toLowerCase(Locale.ROOT).toCharArray()) rep.append('§').append(ch);
            m.appendReplacement(sb, Matcher.quoteReplacement(rep.toString()));
        }
        m.appendTail(sb);
        char[] b = sb.toString().toCharArray();
        for (int i = 0; i < b.length - 1; i++) {
            if (b[i] == '&' && "0123456789AaBbCcDdEeFfKkLlMmNnOoRr".indexOf(b[i + 1]) > -1) {
                b[i] = '§';
                b[i + 1] = Character.toLowerCase(b[i + 1]);
            }
        }
        return new String(b);
    }
}

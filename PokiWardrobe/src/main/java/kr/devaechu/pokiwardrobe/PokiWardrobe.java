package kr.devaechu.pokiwardrobe;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 포키 서버 치장 — 옷장 GUI(/치장), 모자, 파티클.
 * 패킷을 건드리지 않고 Bukkit 기본 API만 쓴다 (아크라이트 호환).
 *
 * 다른 플러그인에서 해금하기: PokiWardrobe.api().unlock(uuid, "꽃_모자")
 */
public final class PokiWardrobe extends JavaPlugin implements Listener {

    static final String USE = "poki.wardrobe.use";
    static final String ADMIN = "poki.wardrobe.admin";

    private static PokiWardrobe instance;

    private final Map<String, Cosmetic> cosmetics = new LinkedHashMap<>();
    private Store store;
    private Hats hats;
    private Particles particles;
    private BukkitTask checkTask;

    /** 다른 플러그인·스크립트에서 쓰는 해금 API (2차: 코블달러 구매, 후원 보상, 이벤트) */
    public static PokiWardrobe api() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        if (!new File(getDataFolder(), "cosmetics.yml").exists()) saveResource("cosmetics.yml", false);
        store = new Store(this);
        store.load();
        loadCosmetics();
        hats = new Hats(this);
        particles = new Particles(this);

        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(hats, this);
        Bukkit.getPluginManager().registerEvents(new Menu.Clicks(this), this);
        bind("wardrobe", new UserCommand());
        bind("wardrobeadmin", new AdminCommand());

        particles.start();
        // 2초마다 모자 점검: 머리 칸이 아닌 곳의 모자 아이템 지우고, 벗겨진 모자 다시 씌우기
        checkTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) { hats.sweep(p); hats.sync(p); }
        }, 40L, 40L);
        for (Player p : Bukkit.getOnlinePlayers()) hats.sync(p);
        getLogger().info("치장 " + cosmetics.size() + "개"
                + (openToAll() ? " — 모두 사용 가능" : " — 운영진(" + USE + ")만 사용 가능"));
    }

    @Override
    public void onDisable() {
        if (checkTask != null) checkTask.cancel();
        if (particles != null) particles.stop();
        // 머리에 씌운 모자는 벗겨둠 (플러그인을 빼도 모자 아이템이 안 남게). 다시 켜지면 다시 씌움
        if (hats != null) for (Player p : Bukkit.getOnlinePlayers()) hats.takeOff(p);
        instance = null;
    }

    private void bind(String name, Object handler) {
        PluginCommand c = getCommand(name);
        if (c == null) return;
        c.setExecutor((CommandExecutor) handler);
        c.setTabCompleter((TabCompleter) handler);
    }

    boolean openToAll() {
        return getConfig().getBoolean("open-to-everyone", false);
    }

    boolean canUse(CommandSender s) {
        return openToAll() || s.hasPermission(USE) || s.hasPermission(ADMIN);
    }

    Store store() { return store; }
    Hats hats() { return hats; }

    Cosmetic cosmetic(String id) {
        return id == null ? null : cosmetics.get(id.toLowerCase(Locale.ROOT));
    }

    List<Cosmetic> cosmetics(Cosmetic.Category c) {
        List<Cosmetic> out = new ArrayList<>();
        for (Cosmetic x : cosmetics.values()) if (x.category() == c) out.add(x);
        return out;
    }

    /** id 또는 이름(띄어쓰기는 _ 나 그냥 띄어쓰기)으로 찾기 */
    Cosmetic find(String key) {
        if (key == null) return null;
        Cosmetic c = cosmetic(key.trim().replace(' ', '_'));
        if (c != null) return c;
        String plain = key.replace('_', ' ').trim();
        for (Cosmetic x : cosmetics.values())
            if (org.bukkit.ChatColor.stripColor(Text.color(x.name())).equals(plain)) return x;
        return null;
    }

    // ---------- 해금 API ----------

    /** 치장 해금. 새로 얻었으면 true. 접속 중이면 알림 */
    public boolean unlock(UUID player, String cosmeticId) {
        Cosmetic c = cosmetic(cosmeticId);
        if (c == null) return false;
        OfflinePlayer op = Bukkit.getOfflinePlayer(player);
        Store.Data d = store.getOrCreate(player, op.getName());
        boolean added = d.owned.add(c.id());
        if (added) {
            store.save();
            Player online = op.getPlayer();
            if (online != null) online.sendMessage(Text.color("&e새 치장을 얻었어요! &f" + c.name() + " &7(/치장 에서 착용)"));
        }
        return added;
    }

    /** 치장 회수. 착용 중이었으면 벗김 */
    public boolean lock(UUID player, String cosmeticId) {
        Cosmetic c = cosmetic(cosmeticId);
        Store.Data d = store.get(player);
        if (c == null || d == null || !d.owned.remove(c.id())) return false;
        if (c.id().equals(d.worn.get(c.category()))) {
            d.worn.remove(c.category());
            Player online = Bukkit.getPlayer(player);
            if (online != null && c.category() == Cosmetic.Category.HAT) hats.takeOff(online);
        }
        store.save();
        return true;
    }

    public boolean has(UUID player, String cosmeticId) {
        Cosmetic c = cosmetic(cosmeticId);
        Store.Data d = store.get(player);
        return c != null && d != null && d.owned.contains(c.id());
    }

    // ---------- 착용 ----------

    void wear(Player p, Cosmetic c) {
        Store.Data d = store.getOrCreate(p.getUniqueId(), p.getName());
        if (!d.owned.contains(c.id())) return;
        if (c.category() == Cosmetic.Category.HAT) {
            String err = hats.wear(p, c);
            if (err != null) { p.sendMessage(Text.color("&c" + err)); return; }
        }
        d.worn.put(c.category(), c.id());
        store.save();
        p.playSound(p.getLocation(), org.bukkit.Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.8f, 1.2f);
        p.sendMessage(Text.color("&a착용했어요: &f" + c.name()));
    }

    void takeOff(Player p, Cosmetic.Category cat, boolean say) {
        Store.Data d = store.get(p.getUniqueId());
        if (d == null || d.worn.remove(cat) == null) return;
        if (cat == Cosmetic.Category.HAT) hats.takeOff(p);
        store.save();
        if (say) p.sendMessage(Text.color("&7" + cat.label + " 치장을 벗었어요."));
    }

    void toggleHideParticles(Player p) {
        Store.Data d = store.getOrCreate(p.getUniqueId(), p.getName());
        d.hideParticles = !d.hideParticles;
        store.save();
        p.sendMessage(Text.color(d.hideParticles ? "&7다른 사람 파티클을 숨겼어요." : "&7다른 사람 파티클이 다시 보여요."));
    }

    // ---------- 접속 ----------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Store.Data d = store.get(p.getUniqueId());
        if (d != null && !p.getName().equals(d.name)) { d.name = p.getName(); store.save(); }
        Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline()) { hats.sweep(p); hats.sync(p); } }, 10L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        hats.takeOff(e.getPlayer());
    }

    // ---------- 설정 ----------

    private void loadCosmetics() {
        cosmetics.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "cosmetics.yml"));
        ConfigurationSection sec = y.getConfigurationSection("cosmetics");
        if (sec == null) return;
        for (String rawId : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(rawId);
            if (s == null) continue;
            String id = rawId.toLowerCase(Locale.ROOT);
            Cosmetic.Category cat = Cosmetic.Category.of(s.getString("category"));
            if (cat == null) { warn(id, "category 는 모자/파티클 중 하나"); continue; }
            Material mat = Material.matchMaterial(s.getString(cat == Cosmetic.Category.HAT ? "material" : "icon", "PAPER"));
            if (mat == null || !mat.isItem()) { warn(id, "아이템 이름이 잘못됨 (" + s.getString("material", s.getString("icon")) + ")"); continue; }
            Particle particle = null;
            Color dust = null;
            if (cat == Cosmetic.Category.PARTICLE) {
                try {
                    particle = Particle.valueOf(s.getString("particle", "").toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    warn(id, "파티클 이름이 잘못됨 (" + s.getString("particle") + ")");
                    continue;
                }
                if (particle == Particle.DUST) dust = parseColor(s.getString("color", "#FFFFFF"));
                else if (particle != Particle.NOTE && particle.getDataType() != Void.class) {
                    warn(id, particle + " 는 추가 데이터가 필요한 파티클이라 못 씀 (DUST·NOTE 나 데이터 없는 파티클만)");
                    continue;
                }
            }
            List<String> desc = s.isList("description") ? s.getStringList("description")
                    : s.getString("description") == null ? List.of() : List.of(s.getString("description"));
            cosmetics.put(id, new Cosmetic(id, s.getString("name", id), cat, mat, s.getInt("model", 0),
                    particle, dust, s.getInt("count", 2), desc, s.getString("unlock", "")));
        }
    }

    private void warn(String id, String why) {
        getLogger().warning("cosmetics.yml: " + id + " 건너뜀 — " + why);
    }

    private static Color parseColor(String hex) {
        try {
            return Color.fromRGB(Integer.parseInt(hex.replace("#", "").replace("&", ""), 16));
        } catch (RuntimeException ex) {
            return Color.WHITE;
        }
    }

    private void reload() {
        reloadConfig();
        store.load();
        loadCosmetics();
        particles.start();
        for (Player p : Bukkit.getOnlinePlayers()) hats.sync(p);
    }

    // ---------- 명령어 ----------

    private static String join(String[] a, int from) {
        return String.join(" ", Arrays.copyOfRange(a, from, a.length));
    }

    /** /치장 [파티클숨기기|벗기] */
    private final class UserCommand implements CommandExecutor, TabCompleter {
        @Override
        public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
            if (!(s instanceof Player p)) { s.sendMessage("게임 안에서 써주세요."); return true; }
            if (!canUse(p)) { p.sendMessage(Text.color("&7치장은 아직 준비 중이에요.")); return true; }
            String sub = a.length > 0 ? a[0] : "";
            switch (sub) {
                case "파티클숨기기", "hideparticles" -> toggleHideParticles(p);
                case "벗기", "off" -> {
                    for (Cosmetic.Category c : Cosmetic.Category.values()) takeOff(p, c, false);
                    p.sendMessage(Text.color("&7치장을 전부 벗었어요."));
                }
                case "파티클" -> Menu.open(PokiWardrobe.this, p, Cosmetic.Category.PARTICLE, 0);
                default -> Menu.open(PokiWardrobe.this, p, Cosmetic.Category.HAT, 0);
            }
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
            List<String> out = new ArrayList<>();
            if (a.length == 1 && canUse(s))
                for (String o : List.of("파티클", "파티클숨기기", "벗기")) if (o.startsWith(a[0])) out.add(o);
            return out;
        }
    }

    /** /치장관리 지급|회수|보기|목록|리로드 */
    private final class AdminCommand implements CommandExecutor, TabCompleter {
        @Override
        public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
            if (!s.hasPermission(ADMIN)) { s.sendMessage(Text.color("&c권한이 없어요.")); return true; }
            String sub = a.length > 0 ? a[0] : "";
            switch (sub) {
                case "지급", "give", "회수", "take" -> {
                    boolean give = sub.equals("지급") || sub.equals("give");
                    if (a.length < 3) { usage(s, label); return true; }
                    UUID id = target(s, a[1], give);
                    if (id == null) return true;
                    List<Cosmetic> list = new ArrayList<>();
                    String key = join(a, 2);
                    if (key.equals("전체") || key.equalsIgnoreCase("all")) list.addAll(cosmetics.values());
                    else {
                        Cosmetic c = find(key);
                        if (c == null) { s.sendMessage(Text.color("&c그런 치장이 없어요: " + key + " &7(/" + label + " 목록)")); return true; }
                        list.add(c);
                    }
                    int n = 0;
                    for (Cosmetic c : list) if (give ? unlock(id, c.id()) : lock(id, c.id())) n++;
                    s.sendMessage(Text.color("&a" + a[1] + " 님 치장 " + n + "개 " + (give ? "지급" : "회수")
                            + (list.size() == 1 ? " (" + list.get(0).name() + "&a)" : "")));
                }
                case "보기", "check" -> {
                    if (a.length < 2) { usage(s, label); return true; }
                    UUID id = target(s, a[1], false);
                    Store.Data d = id == null ? null : store.get(id);
                    if (d == null || d.owned.isEmpty()) { s.sendMessage(Text.color("&7" + a[1] + " 님은 치장이 없어요.")); return true; }
                    s.sendMessage(Text.color("&e" + a[1] + " 님의 치장 (" + d.owned.size() + "개)"));
                    for (String cid : d.owned) {
                        Cosmetic c = cosmetic(cid);
                        boolean on = c != null && cid.equals(d.worn.get(c.category()));
                        s.sendMessage(Text.color("&7- " + (c == null ? "&c" + cid + " (목록에 없음)" : "&8[" + c.category().label + "] &f" + c.name() + " &8" + cid)
                                + (on ? " &a[착용 중]" : "")));
                    }
                }
                case "목록", "list" -> {
                    s.sendMessage(Text.color("&e치장 전체 (" + cosmetics.size() + "개)"));
                    for (Cosmetic c : cosmetics.values())
                        s.sendMessage(Text.color("&7- &8[" + c.category().label + "] &f" + c.name() + " &7명령어: &f" + c.id()));
                }
                case "리로드", "reload" -> {
                    reload();
                    s.sendMessage(Text.color("&a치장 설정을 다시 불러왔어요. (" + cosmetics.size() + "개)"));
                }
                default -> usage(s, label);
            }
            return true;
        }

        private void usage(CommandSender s, String label) {
            s.sendMessage(Text.color("&6/" + label + " 지급 <마크닉> <치장|전체> &7- 치장 주기"));
            s.sendMessage(Text.color("&6/" + label + " 회수 <마크닉> <치장|전체> &7- 치장 뺏기"));
            s.sendMessage(Text.color("&6/" + label + " 보기 <마크닉> &7- 가진 치장 보기"));
            s.sendMessage(Text.color("&6/" + label + " 목록 &7- 치장 목록"));
            s.sendMessage(Text.color("&6/" + label + " 리로드 &7- config.yml · cosmetics.yml 다시 불러오기"));
        }

        @SuppressWarnings("deprecation")
        private UUID target(CommandSender s, String name, boolean mustHavePlayed) {
            Player online = Bukkit.getPlayerExact(name);
            if (online != null) return online.getUniqueId();
            UUID known = store.findByName(name);
            if (known != null) return known;
            OfflinePlayer op = Bukkit.getOfflinePlayer(name);
            if (op.hasPlayedBefore()) return op.getUniqueId();
            s.sendMessage(Text.color("&c" + name + " 님은 " + (mustHavePlayed ? "이 서버에 접속한 적이 없어요." : "치장 기록이 없어요.")));
            return null;
        }

        @Override
        public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
            List<String> out = new ArrayList<>();
            if (!s.hasPermission(ADMIN)) return out;
            if (a.length == 1) {
                for (String o : List.of("지급", "회수", "보기", "목록", "리로드")) if (o.startsWith(a[0])) out.add(o);
            } else if (a.length == 2 && List.of("지급", "회수", "보기", "give", "take", "check").contains(a[0])) {
                for (Player p : Bukkit.getOnlinePlayers())
                    if (p.getName().toLowerCase(Locale.ROOT).startsWith(a[1].toLowerCase(Locale.ROOT))) out.add(p.getName());
            } else if (a.length == 3 && List.of("지급", "회수", "give", "take").contains(a[0])) {
                if ("전체".startsWith(a[2])) out.add("전체");
                for (String id : cosmetics.keySet()) if (id.startsWith(a[2])) out.add(id);
            }
            return out;
        }
    }
}

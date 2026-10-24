package kr.devaechu.pokistats;

import kr.devaechu.pokistats.rank.Category;
import kr.devaechu.pokistats.rank.RankDb;
import kr.devaechu.pokistats.rank.Ranks;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display.Billboard;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * 로비 순위 홀로그램 (TOP 3). 별도 플러그인 없이 글자 엔티티(TextDisplay) — PokiName 칭호와 같은 방식.
 * 엔티티는 저장하지 않음(setPersistent false) → 서버를 껐다 켜도 겹치지 않고, 위치는 holograms.yml 에 저장해서 다시 만듦.
 * 1분마다 순위 갱신할 때 글자를 바꾸고, 청크가 다시 로드돼서 사라졌으면 다시 만듦.
 */
final class Holograms {

    static final String TAG = "pokistats_holo";

    record Holo(int id, Category cat, boolean weekly, String world, double x, double y, double z) {
        Location loc() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z);
        }
    }

    private final File file;
    private final Logger log;
    private final Ranks ranks;
    private final List<Holo> holos = new ArrayList<>();
    private final Map<Integer, UUID> entities = new HashMap<>();
    private int size = 10;

    Holograms(File file, Ranks ranks, Logger log) {
        this.file = file;
        this.ranks = ranks;
        this.log = log;
    }

    void setSize(int size) {
        this.size = Math.max(1, Math.min(10, size));
    }

    void load() {
        holos.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection s = y.getConfigurationSection("holograms");
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            ConfigurationSection h = s.getConfigurationSection(k);
            if (h == null) continue;
            Category c = Category.find(h.getString("category"));
            if (c == null) continue;
            try {
                holos.add(new Holo(Integer.parseInt(k), c, h.getBoolean("weekly"), h.getString("world"),
                        h.getDouble("x"), h.getDouble("y"), h.getDouble("z")));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of("로비 순위 홀로그램 — /명예의전당 홀로그램 으로 관리"));
        for (Holo h : holos) {
            String p = "holograms." + h.id() + ".";
            y.set(p + "category", h.cat().key);
            y.set(p + "weekly", h.weekly());
            y.set(p + "world", h.world());
            y.set(p + "x", h.x());
            y.set(p + "y", h.y());
            y.set(p + "z", h.z());
        }
        try {
            y.save(file);
        } catch (IOException e) {
            log.warning("holograms.yml 저장 실패: " + e);
        }
    }

    List<Holo> list() {
        return List.copyOf(holos);
    }

    Holo add(Category c, boolean weekly, Location l) {
        int id = 1;
        for (Holo h : holos) id = Math.max(id, h.id() + 1);
        Holo h = new Holo(id, c, weekly && c.hasWeekly(), l.getWorld().getName(), l.getX(), l.getY(), l.getZ());
        holos.add(h);
        save();
        update();
        return h;
    }

    boolean remove(int id) {
        boolean ok = holos.removeIf(h -> h.id() == id);
        kill(id);
        if (ok) save();
        return ok;
    }

    boolean move(int id, Location l) {
        for (int i = 0; i < holos.size(); i++) {
            Holo h = holos.get(i);
            if (h.id() != id) continue;
            holos.set(i, new Holo(id, h.cat(), h.weekly(), l.getWorld().getName(), l.getX(), l.getY(), l.getZ()));
            kill(id);
            save();
            update();
            return true;
        }
        return false;
    }

    /** 메인 스레드 — 글자 갱신, 없으면 만들기 */
    void update() {
        Ranks.Snapshot s = ranks.snapshot();
        for (Holo h : holos) {
            Location l = h.loc();
            if (l == null || !l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) continue;
            String text = text(h, s);
            Entity e = entities.containsKey(h.id()) ? Bukkit.getEntity(entities.get(h.id())) : null;
            if (e instanceof TextDisplay td && td.isValid()) {
                if (!text.equals(td.getText())) td.setText(text);
                if (td.getLocation().distanceSquared(l) > 0.01) td.teleport(l);
                continue;
            }
            TextDisplay td = l.getWorld().spawn(l, TextDisplay.class, d -> {
                d.setPersistent(false);
                d.addScoreboardTag(TAG);
                d.setBillboard(Billboard.CENTER);
                d.setLineWidth(1000);
                d.setShadowed(true);
                d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0)); // 배경 없이 (소지금 판처럼)
                d.setText(text);
            });
            entities.put(h.id(), td.getUniqueId());
        }
    }

    // ── 글자 모양: config.yml 의 hologram.format (순위별로 hologram.formats.<종류> 에서 덮어쓰기) ──

    private org.bukkit.configuration.ConfigurationSection cfg;

    void setConfig(org.bukkit.configuration.ConfigurationSection hologramSection) {
        this.cfg = hologramSection;
    }

    /** formats.<종류>.<항목> → format.<항목> → 기본값 */
    private String str(Category c, String field, String def) {
        if (cfg != null) {
            for (String path : new String[]{"formats." + c.key + "." + field, "format." + field}) {
                if (cfg.isString(path)) return cfg.getString(path);
            }
        }
        return def;
    }

    private List<String> list(Category c, String field, List<String> def) {
        if (cfg != null) {
            for (String path : new String[]{"formats." + c.key + "." + field, "format." + field}) {
                if (cfg.isList(path)) return cfg.getStringList(path);
                if (cfg.isString(path)) return List.of(cfg.getString(path));
            }
        }
        return def;
    }

    private static final String BAR = "&#78DFFF&m                                                  ";

    private String text(Holo h, Ranks.Snapshot s) {
        Category c = h.cat();
        List<RankDb.Row> rows = s.get(c, h.weekly());
        String period = !c.hasWeekly() ? "" : h.weekly() ? "이번 주" : "전체";
        String foot = footer(c);
        List<String> out = new ArrayList<>();

        for (String l : list(c, "header", List.of(
                "&#7CFFB2&l✦ &#7CFFB2&l포키 {title} &#FFD166&l랭킹 &#7CFFB2&l✦", BAR))) out.add(fill(l, c, period, foot));
        if (h.weekly()) {
            String w = str(c, "weekly", "&#B8C7D9이번 주");
            if (!w.isBlank()) out.add(Math.min(1, out.size()), fill(w, c, period, foot));
        }
        String lineDef = "&#8FA8C7&l◆&r &#E3ECF8{rank}등 : &e{nick} &f/ &a{value}";
        String[] topDef = {
                "&#FFD166&l①&r &#FFF2CC{rank}등 : &e{nick} &f/ &a{value}",
                "&#C9D6E8&l②&r &#F2F6FC{rank}등 : &e{nick} &f/ &a{value}",
                "&#D99A72&l③&r &#FFF0E6{rank}등 : &e{nick} &f/ &a{value}"};
        for (int i = 1; i <= size; i++) {
            RankDb.Row r = Display.at(rows, i);
            String l;
            if (r == null) l = str(c, "empty", "&#8FA8C7&l◆&r &#E3ECF8{rank}등 : &7-");
            else l = str(c, "line-" + i, i <= 3 ? topDef[i - 1] : str(c, "line", lineDef));
            if (r != null) {
                l = l.replace("{nick}", Display.nick(r.name())).replace("{name}", r.name())
                        .replace("{value}", c.format(r.value()));
            }
            out.add(fill(l.replace("{rank}", String.valueOf(i)), c, period, foot));
        }
        for (String l : list(c, "footer", List.of(BAR, "&#B8C7D9&o{footer}"))) {
            if (l.contains("{footer}") && foot.isBlank()) continue; // 문구가 없으면 그 줄은 빼기
            out.add(fill(l, c, period, foot));
        }
        return Display.color(String.join("\n", out));
    }

    private String fill(String l, Category c, String period, String foot) {
        return l.replace("{title}", c.title).replace("{size}", String.valueOf(size))
                .replace("{period}", period).replace("{footer}", foot);
    }

    private final java.util.Map<String, String> footers = new java.util.HashMap<>();

    void setFooters(java.util.Map<String, String> m) {
        footers.clear();
        footers.putAll(m);
    }

    private String footer(Category c) {
        String f = footers.get(c.key);
        if (f == null) f = footers.get(c.title.replace(" ", ""));
        return f == null ? "" : f;
    }

    private void kill(int id) {
        UUID u = entities.remove(id);
        if (u != null) {
            Entity e = Bukkit.getEntity(u);
            if (e != null) e.remove();
        }
    }

    /** 끌 때·리로드 때 — 우리가 만든 글자 엔티티 전부 지우기 */
    void clear() {
        for (UUID u : entities.values()) {
            Entity e = Bukkit.getEntity(u);
            if (e != null) e.remove();
        }
        entities.clear();
        for (World w : Bukkit.getWorlds()) {
            for (TextDisplay td : w.getEntitiesByClass(TextDisplay.class)) {
                if (td.getScoreboardTags().contains(TAG)) td.remove();
            }
        }
    }
}

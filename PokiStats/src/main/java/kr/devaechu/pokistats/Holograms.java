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
    private int size = 3;

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
                d.setLineWidth(400);
                d.setShadowed(true);
                d.setBackgroundColor(Color.fromARGB(96, 0, 0, 0));
                d.setText(text);
            });
            entities.put(h.id(), td.getUniqueId());
        }
    }

    private String text(Holo h, Ranks.Snapshot s) {
        Category c = h.cat();
        List<RankDb.Row> rows = s.get(c, h.weekly());
        StringBuilder sb = new StringBuilder();
        sb.append("&6&l✦ ").append(c.title).append(" TOP ").append(size).append(" ✦");
        if (c.hasWeekly()) sb.append(h.weekly() ? "\n&7이번 주" : "\n&7전체");
        for (int i = 1; i <= size; i++) {
            RankDb.Row r = Display.at(rows, i);
            String medal = i == 1 ? "&6" : i == 2 ? "&f" : i == 3 ? "&c" : "&e";
            sb.append('\n').append(medal).append(i).append("위 ");
            if (r == null) sb.append("&8-");
            else sb.append("&f").append(Display.nick(r.name())).append(" &7· &a").append(c.format(r.value()));
        }
        return Display.color(sb.toString());
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

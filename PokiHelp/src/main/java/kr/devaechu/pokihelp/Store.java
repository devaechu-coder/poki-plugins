package kr.devaechu.pokihelp;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/** tickets.yml 에 문의 저장 (메인 스레드에서만 씀) */
final class Store {

    private final PokiHelp plugin;
    private final File file;
    private final Map<Integer, Ticket> tickets = new LinkedHashMap<>();
    private int nextId = 1;

    Store(PokiHelp plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "tickets.yml");
    }

    void load() {
        tickets.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        nextId = Math.max(1, y.getInt("next-id", 1));
        ConfigurationSection sec = y.getConfigurationSection("tickets");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                ConfigurationSection t = sec.getConfigurationSection(key);
                if (t == null) continue;
                try {
                    int id = Integer.parseInt(key);
                    Ticket.Kind kind = Ticket.Kind.parse(t.getString("kind"));
                    Ticket tk = new Ticket(id, UUID.fromString(t.getString("uuid", "")),
                            t.getString("name", "?"), t.getString("nick", ""),
                            kind == null ? Ticket.Kind.OTHER : kind, t.getString("message", ""),
                            t.getString("world", "world"), t.getDouble("x"), t.getDouble("y"), t.getDouble("z"),
                            t.getLong("created"));
                    tk.done = t.getBoolean("done");
                    tk.doneBy = t.getString("done-by", "");
                    tk.doneAt = t.getLong("done-at");
                    tk.notified = t.getBoolean("notified", true);
                    tickets.put(id, tk);
                    nextId = Math.max(nextId, id + 1);
                } catch (RuntimeException e) {
                    plugin.getLogger().warning("tickets.yml 의 문의 " + key + " 를 못 읽음: " + e.getMessage());
                }
            }
        }
        // 오래된 처리 완료 문의 지우기
        int days = plugin.getConfig().getInt("keep-done-days", 30);
        if (days > 0) {
            long cut = System.currentTimeMillis() - days * 86_400_000L;
            if (tickets.values().removeIf(t -> t.done && t.notified && t.doneAt > 0 && t.doneAt < cut)) save();
        }
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("next-id", nextId);
        for (Ticket t : tickets.values()) {
            String p = "tickets." + t.id + ".";
            y.set(p + "uuid", t.uuid.toString());
            y.set(p + "name", t.name);
            y.set(p + "nick", t.nick);
            y.set(p + "kind", t.kind.name());
            y.set(p + "message", t.message);
            y.set(p + "world", t.world);
            y.set(p + "x", t.x);
            y.set(p + "y", t.y);
            y.set(p + "z", t.z);
            y.set(p + "created", t.created);
            y.set(p + "done", t.done);
            if (t.done) {
                y.set(p + "done-by", t.doneBy);
                y.set(p + "done-at", t.doneAt);
            }
            if (!t.notified) y.set(p + "notified", false);
        }
        try {
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "tickets.yml 저장 실패", e);
        }
    }

    int nextId() {
        return nextId++;
    }

    void add(Ticket t) {
        tickets.put(t.id, t);
        save();
    }

    Ticket get(int id) {
        return tickets.get(id);
    }

    boolean remove(int id) {
        if (tickets.remove(id) == null) return false;
        save();
        return true;
    }

    /** 처리 여부·종류로 거르고 최신순 (kind == null 이면 전체) */
    List<Ticket> list(boolean done, Ticket.Kind kind) {
        List<Ticket> out = new ArrayList<>();
        for (Ticket t : tickets.values()) {
            if (t.done == done && (kind == null || t.kind == kind)) out.add(t);
        }
        out.sort(Comparator.comparingLong((Ticket t) -> done ? t.doneAt : t.created).reversed());
        return out;
    }

    /** 그 사람 문의 최신순 */
    List<Ticket> of(UUID uuid) {
        List<Ticket> out = new ArrayList<>();
        for (Ticket t : tickets.values()) if (t.uuid.equals(uuid)) out.add(t);
        out.sort(Comparator.comparingLong((Ticket t) -> t.created).reversed());
        return out;
    }

    long openCount() {
        return tickets.values().stream().filter(t -> !t.done).count();
    }

    long openCount(UUID uuid) {
        return tickets.values().stream().filter(t -> !t.done && t.uuid.equals(uuid)).count();
    }
}

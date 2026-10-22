package kr.devaechu.pokiname;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.scheduler.BukkitTask;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 순위 공통 — 일정 주기로 값을 모아서 정렬하고, 한글닉·금액 형식으로 보여준다.
 * 소지금(MoneyRank), 코블달러(CobbleRank) 가 이걸 쓴다.
 */
abstract class Rank {

    record Entry(UUID id, String name, double value) { }

    protected final PokiName plugin;
    private final String key;
    private final String label;
    private final String defaultFormat;
    private BukkitTask task;
    private volatile List<Entry> top = Collections.emptyList();
    /** 마지막으로 명령어를 실행한 1등 (바뀔 때만 실행) */
    private UUID lastFirst;

    Rank(PokiName plugin, String key, String label, String defaultFormat) {
        this.plugin = plugin;
        this.key = key;
        this.label = label;
        this.defaultFormat = defaultFormat;
    }

    /** 켤 수 있으면 준비하고 true. 못 켜면 이유를 로그에 남기고 false */
    protected abstract boolean prepare();

    /** 모든 플레이어의 값 (메인 스레드에서 호출) */
    protected abstract List<Entry> collect();

    void start() {
        stop();
        lastFirst = null; // 리로드하면 한 번 다시 적용
        top = Collections.emptyList();
        ConfigurationSection c = section();
        if (!c.getBoolean("enabled", true) || !prepare()) return;
        long every = Math.max(2, c.getLong("interval-seconds", 10)) * 20L;
        plugin.getLogger().info(label + " 순위 켜짐 — " + (every / 20) + "초마다, 제외: " + c.getStringList("exclude")
                + ", 1등 변경 명령어 " + c.getStringList("on-first-change").size() + "개");
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 40L, every);
    }

    void stop() {
        if (task != null) task.cancel();
        task = null;
    }

    protected ConfigurationSection section() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection(key);
        return c != null ? c : plugin.getConfig().createSection(key);
    }

    void refresh() {
        ConfigurationSection c = section();
        int size = Math.max(1, Math.min(50, c.getInt("size", 10)));
        boolean hideZero = c.getBoolean("hide-zero", true);
        Set<String> exclude = new HashSet<>();
        for (String s : c.getStringList("exclude")) exclude.add(s.toLowerCase(Locale.ROOT));

        List<Entry> all = new ArrayList<>();
        for (Entry e : collect()) {
            if (e.name() == null || Double.isNaN(e.value()) || (hideZero && e.value() < 1)) continue;
            // 마크닉·한글닉 둘 다로 뺄 수 있음
            if (exclude.contains(e.name().toLowerCase(Locale.ROOT))
                    || exclude.contains(plugin.nickOf(e.id(), e.name()).toLowerCase(Locale.ROOT))) continue;
            all.add(e);
        }
        all.sort((a, b) -> Double.compare(b.value(), a.value()));
        top = List.copyOf(all.subList(0, Math.min(size, all.size())));
        onFirstChanged();
    }

    /** 1등이 바뀌면 config 의 명령어 실행 (예: 1등 NPC 스킨 바꾸기) */
    private void onFirstChanged() {
        Entry first = get(1);
        if (first == null || first.id().equals(lastFirst)) return;
        lastFirst = first.id();
        for (String cmd : section().getStringList("on-first-change")) {
            String c = cmd.replace("{name}", first.name())
                    .replace("{nick}", plugin.nickOf(first.id(), first.name()))
                    .replace("{value}", formatValue(first.value()));
            if (c.startsWith("/")) c = c.substring(1);
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c);
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("1등 변경 명령어 실패 (" + c + "): " + ex.getMessage());
            }
        }
    }

    /** 1부터 시작하는 순위. 없으면 null */
    Entry get(int rank) {
        List<Entry> t = top;
        return rank >= 1 && rank <= t.size() ? t.get(rank - 1) : null;
    }

    String formatValue(double v) {
        DecimalFormat f = new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.US));
        f.setRoundingMode(java.math.RoundingMode.FLOOR);
        return section().getString("value-format", defaultFormat).replace("{value}", f.format(v));
    }

    String line(int rank) {
        Entry e = get(rank);
        if (e == null) return section().getString("empty-line", "").replace("{rank}", String.valueOf(rank));
        return section().getString("line", "&f{rank}등 : &e{nick} &f/ &a{value}")
                .replace("{rank}", String.valueOf(rank))
                .replace("{nick}", plugin.nickOf(e.id(), e.name()))
                .replace("{name}", e.name())
                .replace("{value}", formatValue(e.value()));
    }
}

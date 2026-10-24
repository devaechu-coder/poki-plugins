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
    /** 마지막으로 명령어를 실행한 1등 — uuid + 마크닉 + 한글닉 (한글닉만 바뀌어도 다시 실행) */
    private String lastFirst;
    /** 서버 켜진 직후엔 Citizens NPC 가 아직 안 불러와졌을 수 있어서 이 시각 전엔 1등 명령어를 미룸 */
    private long readyAt;
    private final List<String> lastResults = new ArrayList<>();
    private int lastCount;

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
        readyAt = System.currentTimeMillis() + Math.max(0, section().getLong("first-change-delay-seconds", 20)) * 1000L;
        top = Collections.emptyList();
        ConfigurationSection c = section();
        if (!c.getBoolean("enabled", true) || !prepare()) return;
        long every = Math.max(2, c.getLong("interval-seconds", 10)) * 20L;
        plugin.getLogger().info(label + " 순위 켜짐 — " + (every / 20) + "초마다, 제외: " + excludeList()
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
        for (String s : excludeList()) exclude.add(s.toLowerCase(Locale.ROOT));

        List<Entry> all = new ArrayList<>();
        for (Entry e : collect()) {
            if (e.name() == null || Double.isNaN(e.value()) || (hideZero && e.value() < 1)) continue;
            // 마크닉·한글닉 둘 다로 뺄 수 있음
            if (exclude.contains(e.name().toLowerCase(Locale.ROOT))
                    || exclude.contains(PokiName.stripColor(plugin.nickOf(e.id(), e.name())).trim().toLowerCase(Locale.ROOT))) continue;
            all.add(e);
        }
        lastCount = all.size();
        all.sort((a, b) -> Double.compare(b.value(), a.value()));
        top = List.copyOf(all.subList(0, Math.min(size, all.size())));
        onFirstChanged();
    }

    /**
     * 순위에서 뺄 사람 — 맨 위 rank-exclude(모든 순위 공통) + 이 순위의 exclude.
     * 리스트([a, b])도, 쉼표로 쓴 글자("a, b")도, 한 명("a")도 받음. 색 코드·앞뒤 띄어쓰기는 무시.
     */
    List<String> excludeList() {
        List<String> out = new ArrayList<>();
        readNames(plugin.getConfig(), "rank-exclude", out);
        readNames(section(), "exclude", out);
        return out;
    }

    private static void readNames(ConfigurationSection c, String path, List<String> out) {
        if (c == null || !c.contains(path)) return;
        List<String> raw = c.isList(path) ? c.getStringList(path) : List.of(String.valueOf(c.getString(path, "")).split(","));
        for (String n : raw) {
            String v = n == null ? "" : PokiName.stripColor(n).trim();
            if (!v.isEmpty() && !out.contains(v)) out.add(v);
        }
    }

    /**
     * 1등이 바뀌면 config 의 명령어 실행 (예: 1등 NPC 이름·스킨 바꾸기).
     * - 1등이 같아도 한글닉이 바뀌면 다시 실행 (예전엔 uuid 만 봐서 NPC 이름이 옛 닉에 멈춰 있었음)
     * - 서버 켜진 직후(first-change-delay-seconds)와 Citizens 가 아직 안 켜졌을 땐 미뤘다가 다음 갱신 때 실행
     *   (예전엔 Citizens 가 NPC 를 불러오기 전에 실행돼서 실패해도 "실행함" 으로 처리돼 다시 안 했음)
     */
    private void onFirstChanged() {
        Entry first = get(1);
        if (first == null) return;
        String nick = PokiName.stripColor(plugin.nickOf(first.id(), first.name())).trim();
        String key = first.id() + "|" + first.name() + "|" + nick;
        if (key.equals(lastFirst)) return;
        List<String> cmds = section().getStringList("on-first-change");
        if (cmds.isEmpty()) {
            lastFirst = key;
            return;
        }
        if (System.currentTimeMillis() < readyAt) return;
        boolean npc = cmds.stream().anyMatch(c -> c.trim().replaceFirst("^/", "").toLowerCase(Locale.ROOT).startsWith("npc "));
        if (npc && !Bukkit.getPluginManager().isPluginEnabled("Citizens")) return;
        lastFirst = key;
        runFirstCommands(first, nick, cmds);
    }

    private void runFirstCommands(Entry first, String nick, List<String> cmds) {
        lastResults.clear();
        plugin.getLogger().info(label + " 1등: " + nick + " (" + first.name() + ") → 명령어 " + cmds.size() + "개 실행");
        for (String cmd : cmds) {
            String c = cmd.replace("{name}", first.name())
                    .replace("{nick}", nick)
                    .replace("{value}", formatValue(first.value())).trim();
            if (c.startsWith("/")) c = c.substring(1);
            String npcProblem = checkNpc(c);
            if (npcProblem != null) {
                plugin.getLogger().warning("1등 변경 명령어 건너뜀 — " + npcProblem + ": " + c);
                lastResults.add("&c건너뜀 &7" + c + " &c(" + npcProblem + ")");
                continue;
            }
            try {
                boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c);
                if (!ok) plugin.getLogger().warning("1등 변경 명령어가 안 먹힘 (없는 명령어거나 형식이 틀림): " + c);
                lastResults.add((ok ? "&a실행 &7" : "&c안 먹힘 &7") + c);
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("1등 변경 명령어 실패 (" + c + "): " + ex.getMessage());
                lastResults.add("&c오류 &7" + c + " &c(" + ex.getMessage() + ")");
            }
        }
    }

    /** "npc ... --id N" 이면 그 번호 NPC 가 실제로 있는지 Citizens 에 물어봄. 문제 없으면 null */
    private static String checkNpc(String c) {
        if (!c.toLowerCase(Locale.ROOT).startsWith("npc ")) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("--id\\s+(\\d+)").matcher(c);
        if (!m.find()) return "--id 번호가 없음 (콘솔엔 선택된 NPC 가 없어서 --id 가 꼭 필요)";
        int id = Integer.parseInt(m.group(1));
        try {
            Class<?> api = Class.forName("net.citizensnpcs.api.CitizensAPI");
            Object reg = api.getMethod("getNPCRegistry").invoke(null);
            Object npc = reg.getClass().getMethod("getById", int.class).invoke(reg, id);
            return npc == null ? id + "번 NPC 가 없음 (/npc list 로 번호 확인)" : null;
        } catch (Throwable t) {
            return null; // 확인 못 하면 그냥 실행
        }
    }

    /** /한글닉 순위적용 — 지금 1등 기준으로 명령어 바로 다시 실행 */
    String forceFirst() {
        Entry first = get(1);
        if (first == null) return "지금 1등이 없어요 (순위가 비어 있음)";
        List<String> cmds = section().getStringList("on-first-change");
        if (cmds.isEmpty()) return key + ".on-first-change 가 비어 있어요";
        String nick = PokiName.stripColor(plugin.nickOf(first.id(), first.name())).trim();
        lastFirst = first.id() + "|" + first.name() + "|" + nick;
        runFirstCommands(first, nick, cmds);
        return null;
    }

    /** /한글닉 순위확인 — 지금 순위가 어떻게 계산되는지 */
    List<String> debug() {
        List<String> out = new ArrayList<>();
        ConfigurationSection c = section();
        out.add("&e" + label + " 순위 &7(" + key + ") — " + (task != null ? "&a켜짐" : "&c꺼짐 (enabled 또는 준비 실패 — 콘솔 확인)"));
        String src = source();
        if (src != null) out.add("&7읽는 곳: &f" + src);
        out.add("&7대상 " + lastCount + "명 · " + c.getLong("interval-seconds", 10) + "초마다 · 상위 " + c.getInt("size", 10) + "명 · 0원 숨김 " + c.getBoolean("hide-zero", true));
        out.add("&7제외: &f" + excludeList());
        List<Entry> t = top;
        for (int i = 0; i < Math.min(5, t.size()); i++) {
            Entry e = t.get(i);
            out.add("&f" + (i + 1) + "등 &e" + plugin.nickOf(e.id(), e.name()) + " &7(" + e.name() + ") &a" + formatValue(e.value()));
        }
        if (t.isEmpty()) out.add("&c순위가 비어 있어요");
        List<String> cmds = c.getStringList("on-first-change");
        out.add("&71등 변경 명령어 " + cmds.size() + "개" + (cmds.isEmpty() ? "" : " — 마지막 실행 결과:"));
        if (!cmds.isEmpty() && lastResults.isEmpty()) out.add("&7  아직 실행 전 (서버 켜고 " + c.getLong("first-change-delay-seconds", 20) + "초 뒤 · Citizens 켜진 뒤)");
        for (String r : lastResults) out.add("  " + r);
        return out;
    }

    /** 값을 어디서 읽는지 (디버그용) */
    protected String source() {
        return null;
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

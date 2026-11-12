package kr.devaechu.pokiname;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.TabCompleteEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 명령어에 한글닉을 쓰면 마크닉으로 바꿔서 실행 (예: /tpa 애츄 → /tpa Dev_Aechu).
 * config.yml 의 nick-commands 에 적힌 명령어의 정해진 자리만 바꿈. 플레이어가 직접 친 것만 (콘솔·자동 실행은 안 건드림).
 *  - 그 자리 글자가 실제 마크닉(접속 중이거나 서버에 들어온 적 있음)이면 그대로 둠
 *  - 같은 한글닉이 여러 명이면 바꾸지 않고 마크닉으로 쳐 달라고 안내
 *  - 한글닉에 띄어쓰기가 있으면 이어지는 낱말까지 맞춰 봄 (최대 3낱말)
 */
final class NickArgs implements Listener {
    private final PokiName plugin;

    NickArgs(PokiName plugin) { this.plugin = plugin; }

    /** "명령어 낱말들" → 바꿀 자리들 (그 낱말들 다음부터 1번) */
    private Map<List<String>, List<Integer>> rules() {
        Map<List<String>, List<Integer>> out = new LinkedHashMap<>();
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("nick-commands");
        if (sec == null || !plugin.getConfig().getBoolean("nick-commands-enabled", true)) return out;
        for (String key : sec.getKeys(false)) {
            List<String> words = Arrays.asList(key.trim().toLowerCase(Locale.ROOT).split("\\s+"));
            List<Integer> pos = sec.getIntegerList(key);
            if (!words.isEmpty() && !pos.isEmpty()) out.put(words, pos);
        }
        return out;
    }

    private static String bare(String label) {
        String l = label.toLowerCase(Locale.ROOT);
        int c = l.indexOf(':');
        return c >= 0 ? l.substring(c + 1) : l;
    }

    /** 이 명령어에 맞는 규칙: [규칙 낱말 수, 자리들] */
    private Map.Entry<List<String>, List<Integer>> match(List<String> tokens) {
        Map.Entry<List<String>, List<Integer>> best = null;
        for (Map.Entry<List<String>, List<Integer>> r : rules().entrySet()) {
            List<String> w = r.getKey();
            if (tokens.size() < w.size()) continue;
            if (!bare(tokens.get(0)).equals(w.get(0))) continue;
            boolean ok = true;
            for (int i = 1; i < w.size(); i++) if (!tokens.get(i).equalsIgnoreCase(w.get(i))) { ok = false; break; }
            if (ok && (best == null || w.size() > best.getKey().size())) best = r;
        }
        return best;
    }

    private static boolean isRealName(String s) {
        if (Bukkit.getPlayerExact(s) != null) return true;
        for (OfflinePlayer op : Bukkit.getOfflinePlayers())
            if (op.getName() != null && op.getName().equalsIgnoreCase(s)) return true;
        return false;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        String msg = e.getMessage();
        if (!msg.startsWith("/") || msg.length() < 2) return;
        List<String> t = new ArrayList<>(Arrays.asList(msg.substring(1).split(" ", -1)));
        var rule = match(t);
        if (rule == null) return;
        int base = rule.getKey().size(); // 명령어 낱말 다음 자리
        boolean changed = false;
        // 뒤쪽 자리부터 바꿔야 앞 자리 번호가 안 밀림
        List<Integer> pos = new ArrayList<>(rule.getValue());
        pos.sort((a, b) -> b - a);
        for (int p : pos) {
            int i = base + p - 1;
            if (i >= t.size() || t.get(i).isEmpty()) continue;
            String arg = t.get(i);
            if (arg.startsWith("@") || isRealName(arg)) continue;
            // 띄어쓰기 있는 한글닉: 긴 것부터 맞춰 봄
            for (int len = Math.min(3, t.size() - i); len >= 1; len--) {
                String cand = String.join(" ", t.subList(i, i + len));
                List<String> names = plugin.namesForNick(cand);
                if (names.isEmpty()) continue;
                if (names.size() > 1) {
                    e.getPlayer().sendMessage(PokiName.color("&e'" + cand + "' 한글닉이 여러 명이에요: &f" + String.join(", ", names) + " &e— 마크닉으로 입력해 주세요."));
                    e.setCancelled(true);
                    return;
                }
                for (int k = 1; k < len; k++) t.remove(i + 1);
                t.set(i, names.get(0));
                changed = true;
                break;
            }
        }
        if (changed) e.setMessage("/" + String.join(" ", t));
    }

    /** Tab 자동완성에 한글닉도 (플러그인 명령어만 — 마크 기본 명령어는 안 될 수 있음) */
    @EventHandler(ignoreCancelled = true)
    public void onTab(TabCompleteEvent e) {
        if (!(e.getSender() instanceof Player)) return;
        String buf = e.getBuffer();
        if (!buf.startsWith("/")) return;
        List<String> t = new ArrayList<>(Arrays.asList(buf.substring(1).split(" ", -1)));
        var rule = match(t);
        if (rule == null) return;
        int at = t.size() - rule.getKey().size(); // 지금 치는 자리 (1부터)
        if (!rule.getValue().contains(at)) return;
        String typed = t.get(t.size() - 1).toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>(e.getCompletions());
        for (String n : plugin.allNicks())
            if (!n.contains(" ") && n.toLowerCase(Locale.ROOT).startsWith(typed) && !out.contains(n)) out.add(n);
        e.setCompletions(out);
    }
}

package kr.devaechu.pokiname;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.RegisteredServiceProvider;
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
 * 소지금 순위 — Vault(Essentials) 잔액을 일정 주기로 직접 계산한다.
 * Essentials 의 baltop 은 누가 /baltop 을 쳐야 갱신돼서 홀로그램이 옛날 값을 보여주던 문제를 대신함.
 */
final class MoneyRank {

    record Entry(UUID id, String name, double value) { }

    private final PokiName plugin;
    private Economy econ;
    private BukkitTask task;
    private volatile List<Entry> top = Collections.emptyList();

    MoneyRank(PokiName plugin) {
        this.plugin = plugin;
    }

    void start() {
        stop();
        ConfigurationSection c = section();
        if (!c.getBoolean("enabled", true)) return;
        if (!hookVault()) {
            plugin.getLogger().warning("Vault 경제 플러그인을 못 찾아서 소지금 순위를 끕니다.");
            return;
        }
        long every = Math.max(2, c.getLong("interval-seconds", 10)) * 20L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 40L, every);
    }

    void stop() {
        if (task != null) task.cancel();
        task = null;
    }

    private boolean hookVault() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        econ = rsp == null ? null : rsp.getProvider();
        return econ != null;
    }

    private ConfigurationSection section() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("money-rank");
        return c != null ? c : plugin.getConfig().createSection("money-rank");
    }

    /** 모든 플레이어 잔액을 읽어서 순위를 다시 만든다 (메인 스레드) */
    void refresh() {
        if (econ == null) return;
        ConfigurationSection c = section();
        int size = Math.max(1, Math.min(50, c.getInt("size", 10)));
        boolean hideZero = c.getBoolean("hide-zero", true);
        Set<String> exclude = new HashSet<>();
        for (String s : c.getStringList("exclude")) exclude.add(s.toLowerCase(Locale.ROOT));

        List<Entry> all = new ArrayList<>();
        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            String name = op.getName();
            if (name == null || exclude.contains(name.toLowerCase(Locale.ROOT))) continue;
            double bal;
            try {
                if (!econ.hasAccount(op)) continue;
                bal = econ.getBalance(op);
            } catch (RuntimeException ex) {
                continue;
            }
            if (Double.isNaN(bal) || (hideZero && bal < 1)) continue;
            all.add(new Entry(op.getUniqueId(), name, bal));
        }
        all.sort((a, b) -> Double.compare(b.value(), a.value()));
        top = List.copyOf(all.subList(0, Math.min(size, all.size())));
    }

    /** 1부터 시작하는 순위. 없으면 null */
    Entry get(int rank) {
        List<Entry> t = top;
        return rank >= 1 && rank <= t.size() ? t.get(rank - 1) : null;
    }

    String formatValue(double v) {
        DecimalFormat f = new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.US));
        f.setRoundingMode(java.math.RoundingMode.FLOOR);
        return section().getString("value-format", "${value}").replace("{value}", f.format(v));
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

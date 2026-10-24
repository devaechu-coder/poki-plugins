package kr.devaechu.pokiname;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * 소지금 순위 — Vault(Essentials) 잔액을 직접 계산.
 * Essentials 의 baltop 은 누가 /baltop 을 쳐야 갱신돼서 홀로그램이 옛날 값을 보여주던 문제를 대신함.
 * 한 번이라도 접속한 사람 전부 대상 (Essentials 가 오프라인 잔액도 들고 있음).
 */
final class MoneyRank extends Rank {

    private Economy econ;

    MoneyRank(PokiName plugin) {
        super(plugin, "money-rank", "소지금", "${value}");
    }

    private String econInfo;

    /**
     * Vault 에 돈 플러그인이 여러 개 등록돼 있으면(Essentials·CMI 등) Vault 가 고른 것과 유저가 쓰는 돈이 다를 수 있음.
     * config money-rank.economy 에 플러그인 이름(Essentials, CMI …)을 적으면 그걸 씀. 비우면 Vault 기본.
     */
    @Override
    protected boolean prepare() {
        econ = null;
        econInfo = null;
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            plugin.getLogger().warning("Vault 가 없어서 소지금 순위를 끕니다.");
            return false;
        }
        String want = section().getString("economy", "").trim();
        List<String> names = new ArrayList<>();
        RegisteredServiceProvider<Economy> pick = null;
        for (RegisteredServiceProvider<Economy> r : Bukkit.getServicesManager().getRegistrations(Economy.class)) {
            names.add(r.getPlugin().getName() + "(" + r.getProvider().getName() + ")");
            if (!want.isEmpty() && pick == null
                    && (r.getPlugin().getName().equalsIgnoreCase(want) || r.getProvider().getName().equalsIgnoreCase(want))) pick = r;
        }
        if (pick == null) {
            if (!want.isEmpty()) plugin.getLogger().warning("money-rank.economy '" + want + "' 를 못 찾아서 Vault 기본으로 씀. 등록된 것: " + names);
            pick = Bukkit.getServicesManager().getRegistration(Economy.class);
        }
        if (pick == null) {
            plugin.getLogger().warning("Vault 에 등록된 돈 플러그인이 없어서 소지금 순위를 끕니다.");
            return false;
        }
        econ = pick.getProvider();
        econInfo = pick.getPlugin().getName() + " (" + econ.getName() + ")" + (names.size() > 1 ? " · 등록된 것 전부: " + names : "");
        plugin.getLogger().info("소지금 순위가 읽는 돈: " + econInfo);
        return true;
    }

    @Override
    protected String source() {
        return econInfo == null ? "&c없음" : econInfo;
    }

    @Override
    protected List<Entry> collect() {
        List<Entry> out = new ArrayList<>();
        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            String name = op.getName();
            if (name == null) continue;
            try {
                if (!econ.hasAccount(op)) continue;
                out.add(new Entry(op.getUniqueId(), name, econ.getBalance(op)));
            } catch (RuntimeException ignored) {
            }
        }
        return out;
    }
}

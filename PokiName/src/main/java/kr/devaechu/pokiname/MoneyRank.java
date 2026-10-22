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

    @Override
    protected boolean prepare() {
        if (Bukkit.getPluginManager().getPlugin("Vault") != null) {
            RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
            econ = rsp == null ? null : rsp.getProvider();
        }
        if (econ == null) plugin.getLogger().warning("Vault 경제 플러그인을 못 찾아서 소지금 순위를 끕니다.");
        return econ != null;
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

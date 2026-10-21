package kr.devaechu.pokiname;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/**
 * PlaceholderAPI 연결 — 홀로그램·스코어보드·Skript 에서 한글닉과 순위를 쓸 수 있게.
 *
 * %pokiname_nick%                 보는 사람(또는 대상)의 한글닉, 없으면 마크닉
 * %pokiname_nick_<마크닉>%         그 마크닉의 한글닉 (접속 안 해도 됨), 없으면 마크닉
 * %pokiname_money_<n>%            소지금 n등 한 줄 (config 의 money-rank.line 형식)
 * %pokiname_money_<n>_nick%       n등 한글닉
 * %pokiname_money_<n>_name%       n등 마크닉
 * %pokiname_money_<n>_value%      n등 금액 ($7,199)
 * %pokiname_money_<n>_raw%        n등 금액 숫자 그대로
 */
final class PokiExpansion extends PlaceholderExpansion {

    private final PokiName plugin;

    PokiExpansion(PokiName plugin) {
        this.plugin = plugin;
    }

    @Override public String getIdentifier() { return "pokiname"; }
    @Override public String getAuthor() { return "DevAechu"; }
    @Override public String getVersion() { return plugin.getDescription().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        String p = params == null ? "" : params;

        if (p.equals("nick")) {
            if (player == null) return "";
            return plugin.nickOf(player.getUniqueId(), player.getName() == null ? "" : player.getName());
        }
        if (p.startsWith("nick_")) {
            return plugin.nickByName(p.substring(5));
        }
        if (p.startsWith("money_")) {
            MoneyRank rank = plugin.moneyRank();
            if (rank == null) return "";
            String[] parts = p.substring(6).split("_", 2);
            int n;
            try { n = Integer.parseInt(parts[0]); } catch (NumberFormatException ex) { return null; }
            if (parts.length == 1) return PokiName.color(rank.line(n));
            MoneyRank.Entry e = rank.get(n);
            if (e == null) return "";
            return switch (parts[1]) {
                case "nick" -> plugin.nickOf(e.id(), e.name());
                case "name" -> e.name();
                case "value" -> rank.formatValue(e.value());
                case "raw" -> String.valueOf(e.value());
                default -> null;
            };
        }
        return null;
    }
}

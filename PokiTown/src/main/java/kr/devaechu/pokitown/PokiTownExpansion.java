package kr.devaechu.pokitown;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.Map;

/**
 * %pokitown_name%   마을 표시 이름 (마을 없으면 빈칸)
 * %pokitown_color%  마을 색 코드 (&#8FD3FF 등)
 * %pokitown_towny%  Towny 마을 이름 그대로
 * %pokitown_role%   이장 / 부이장 / 주민
 */
final class PokiTownExpansion extends PlaceholderExpansion {
    @Override public String getIdentifier() { return "pokitown"; }
    @Override public String getAuthor() { return "DevAechu"; }
    @Override public String getVersion() { return PokiTown.inst == null ? "" : PokiTown.inst.getDescription().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null || PokiTown.inst == null) return "";
        Map<String, String> t = PokiTownApi.townOf(player.getUniqueId());
        if (t == null) return "";
        return switch (params == null ? "" : params) {
            case "name" -> t.get("display");
            case "color" -> t.get("color");
            case "towny" -> t.get("name");
            case "role" -> switch (t.get("role")) { case "mayor" -> "이장"; case "assistant" -> "부이장"; default -> "주민"; };
            default -> null;
        };
    }
}

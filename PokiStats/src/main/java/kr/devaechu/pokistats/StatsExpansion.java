package kr.devaechu.pokistats;

import kr.devaechu.pokistats.rank.Category;
import kr.devaechu.pokistats.rank.RankDb;
import kr.devaechu.pokistats.rank.Ranks;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.List;

/**
 * PlaceholderAPI — 다른 홀로그램·스코어보드 플러그인에서도 명예의 전당 순위를 쓸 수 있게.
 *
 * %pokistats_<종류>_<n>%          n등 한 줄 (한글닉 · 값)
 * %pokistats_<종류>_<n>_name%     n등 한글닉
 * %pokistats_<종류>_<n>_value%    n등 값 (120마리, $152,000, 340C)
 * %pokistats_<종류>_week_<n>...%  이번 주 순위 (위와 같은 형식)
 * %pokistats_<종류>_me%           보는 사람의 순위 (n위, 없으면 -)
 * %pokistats_<종류>_me_value%     보는 사람의 값
 * <종류>: capture dex shiny legend battle pvp streak evolve hatch tower raid gym masterball money cobble
 *        (한글 이름도 됨: 포획왕, 이로치사냥꾼 …)
 */
final class StatsExpansion extends PlaceholderExpansion {

    private final PokiStats plugin;
    private final Ranks ranks;

    StatsExpansion(PokiStats plugin, Ranks ranks) {
        this.plugin = plugin;
        this.ranks = ranks;
    }

    @Override public String getIdentifier() { return "pokistats"; }
    @Override public String getAuthor() { return "DevAechu"; }
    @Override public String getVersion() { return plugin.getDescription().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (params == null) return null;
        String[] parts = params.split("_");
        if (parts.length < 2) return null;
        Category c = Category.find(parts[0]);
        if (c == null) return null;
        int i = 1;
        boolean weekly = false;
        if (parts[i].equals("week")) {
            weekly = true;
            i++;
            if (i >= parts.length) return null;
        }
        List<RankDb.Row> rows = ranks.snapshot().get(c, weekly);
        if (parts[i].equals("me")) {
            if (player == null) return "";
            int my = Display.indexOf(rows, player.getUniqueId().toString());
            boolean value = i + 1 < parts.length && parts[i + 1].equals("value");
            if (my < 0) return "-";
            return value ? c.format(rows.get(my - 1).value()) : my + "위";
        }
        int n;
        try {
            n = Integer.parseInt(parts[i]);
        } catch (NumberFormatException ex) {
            return null;
        }
        RankDb.Row r = Display.at(rows, n);
        String field = i + 1 < parts.length ? parts[i + 1] : "";
        if (r == null) return field.isEmpty() ? Display.color("&7" + n + "위 -") : "";
        return switch (field) {
            case "" -> Display.color("&f" + n + "위 " + Display.nick(r.name()) + " &7· &a" + c.format(r.value()));
            case "name" -> Display.nick(r.name());
            case "value" -> c.format(r.value());
            default -> null;
        };
    }
}

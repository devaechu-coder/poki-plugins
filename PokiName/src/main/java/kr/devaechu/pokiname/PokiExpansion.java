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
 * %pokiname_cobble_<n>% ...        코블달러 순위 (money 와 같은 형식)
 * %pokiname_biome%                지금 있는 바이옴 이름 (한글, 없으면 "Orchid Swamp" 식)
 * %pokiname_biome_1% / _2%        긴 바이옴 이름을 두 줄로 나눈 것 (biome.max-length 글자 기준)
 * %pokiname_title%               장착한 이미지 칭호 색 글씨 (없으면 빈칸)
 * %pokiname_title_name%          장착한 이미지 칭호 이름만 (색 없음)
 * %pokiname_text_title%          장착한 글씨 칭호 색 글씨 (없으면 빈칸)
 * %pokiname_list_title%          Tab 규칙 결과: 이미지 칭호 > 글씨 칭호 (없으면 빈칸)
 * %pokiname_town%                마을 이름 (PokiTown 표시 이름·색 그대로, 없으면 빈칸)
 * %pokiname_boost%              경험치 부스트 중이면 "&6⚡ 경험치 2배 &f9:59", 아니면 빈칸 (스코어보드용)
 * %pokiname_boost_time%         남은 시간만 (9:59), 아니면 빈칸
 */
final class PokiExpansion extends PlaceholderExpansion {

    private final PokiName plugin;

    PokiExpansion(PokiName plugin) { this(plugin, "pokiname"); }

    private final String id;

    PokiExpansion(PokiName plugin, String id) {
        this.plugin = plugin;
        this.id = id;
    }

    @Override public String getIdentifier() { return id; }
    @Override public String getAuthor() { return "DevAechu"; }
    @Override public String getVersion() { return plugin.getDescription().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        String p = params == null ? "" : params;

        // 예전 NickName 플러그인 호환: %nickname_name% (색 적용 한글닉, 없으면 마크닉), %nickname_raw% (색 코드 그대로)
        if (id.equals("nickname")) {
            if (player == null) return "";
            String fb = player.getName() == null ? "" : player.getName();
            if (p.equals("raw")) return plugin.nickOf(player.getUniqueId(), fb);
            return PokiName.color(plugin.nickOf(player.getUniqueId(), fb));
        }

        if (p.equals("nick")) {
            if (player == null) return "";
            return plugin.nickOf(player.getUniqueId(), player.getName() == null ? "" : player.getName());
        }
        if (p.equals("biome") || p.equals("biome_1") || p.equals("biome_2")) {
            BiomeName b = plugin.biomeName();
            if (b == null || player == null) return "";
            if (p.equals("biome")) return b.get(player.getUniqueId());
            return b.part(player.getUniqueId(), p.endsWith("1") ? 1 : 2);
        }
        if (p.equals("title") || p.equals("title_name")) {
            if (player == null || plugin.titles() == null) return "";
            if (p.equals("title_name")) return plugin.titles().nameOf(player.getUniqueId());
            Titles.Def d = plugin.titles().equipped(player.getUniqueId());
            return d == null ? "" : d.chat();
        }
        if (p.equals("text_title") || p.equals("list_title")) {
            if (player == null || plugin.titles() == null) return "";
            Titles.Def d = p.equals("text_title") ? plugin.titles().equippedText(player.getUniqueId()) : plugin.titles().listTitle(player.getUniqueId());
            return d == null ? "" : d.chat();
        }
        if (p.equals("town")) {
            if (player == null) return "";
            return PokiName.color(plugin.townPlain(player.getUniqueId()));
        }
        if (p.equals("boost") || p.equals("boost_time")) {
            String t = Boost.left();
            if (t.isEmpty()) return "";
            return p.equals("boost") ? PokiName.color(plugin.getConfig().getString("boost-line", "&6⚡ 경험치 2배 &f{time}").replace("{time}", t)) : t;
        }
        if (p.startsWith("nick_")) {
            return plugin.nickByName(p.substring(5));
        }
        Rank rank = null;
        String rest = null;
        if (p.startsWith("money_")) { rank = plugin.moneyRank(); rest = p.substring(6); }
        else if (p.startsWith("cobble_")) { rank = plugin.cobbleRank(); rest = p.substring(7); }
        if (rest != null) {
            if (rank == null) return "";
            String[] parts = rest.split("_", 2);
            int n;
            try { n = Integer.parseInt(parts[0]); } catch (NumberFormatException ex) { return null; }
            if (parts.length == 1) return PokiName.color(rank.line(n));
            Rank.Entry e = rank.get(n);
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

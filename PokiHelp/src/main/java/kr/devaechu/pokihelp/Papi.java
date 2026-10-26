package kr.devaechu.pokihelp;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.entity.Player;

/** PlaceholderAPI 를 따로 둬서, 없을 때 클래스를 안 불러오게 */
final class Papi {
    private Papi() {
    }

    static String apply(Player p, String s) {
        return PlaceholderAPI.setPlaceholders(p, s);
    }
}

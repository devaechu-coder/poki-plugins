package kr.devaechu.pokigacha;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.UUID;

/** 포키 우편함(PokiMail)이 있으면 거기로 보냄. 리플렉션이라 PokiMail 없이도 빌드·실행됨 */
final class Mailbox {
    private Mailbox() {}

    /** 우편함에 넣었으면 true, PokiMail 이 없으면 false */
    static boolean mail(UUID to, ItemStack item, int count, String memo) {
        Plugin pm = Bukkit.getPluginManager().getPlugin("PokiMail");
        if (pm == null || !pm.isEnabled()) return false;
        try {
            Method m = pm.getClass().getMethod("mailCount", UUID.class, ItemStack.class, int.class, String.class, String.class);
            m.invoke(null, to, item, count, memo, null);
            return true;
        } catch (Throwable t) {
            Bukkit.getLogger().warning("[PokiGacha] 우편함으로 못 보냄: " + t);
            return false;
        }
    }
}

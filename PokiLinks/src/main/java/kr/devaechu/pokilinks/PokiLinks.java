package kr.devaechu.pokilinks;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 포키 서버 바로가기 링크.
 * 클릭 링크는 바닐라 /tellraw 로 보낸다 (추가 API 없이 아크라이트에서도 동작).
 */
public final class PokiLinks extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        switch (cmd.getName().toLowerCase(java.util.Locale.ROOT)) {
            case "wiki" -> send(s, "wiki");
            case "launcher" -> send(s, "launcher");
            case "links" -> {
                ConfigurationSection links = getConfig().getConfigurationSection("links");
                if (links == null || links.getKeys(false).isEmpty()) { s.sendMessage(color("&7등록된 링크가 없어요.")); return true; }
                for (String key : links.getKeys(false)) send(s, key);
            }
            case "pokilinks" -> {
                if (a.length > 0 && a[0].equalsIgnoreCase("reload")) {
                    reloadConfig();
                    s.sendMessage(color("&a바로가기 설정을 다시 불러왔어요."));
                } else {
                    s.sendMessage(color("&e/pokilinks reload &7- config.yml 다시 불러오기"));
                }
            }
            default -> { return false; }
        }
        return true;
    }

    private void send(CommandSender s, String key) {
        String base = "links." + key + ".";
        String url = getConfig().getString(base + "url", "");
        if (url.isEmpty()) { s.sendMessage(color("&c'" + key + "' 링크가 설정되지 않았어요.")); return; }
        String title = color(getConfig().getString(base + "title", key));
        String desc = color(getConfig().getString(base + "description", ""));
        String button = color(getConfig().getString("button", "&e&n▶ 클릭해서 열기"));
        String hover = color(getConfig().getString("hover", "{url}").replace("{url}", url));

        if (!(s instanceof Player p)) { // 콘솔
            s.sendMessage(title + " " + desc + " " + url);
            return;
        }
        String json = "[\"\","
                + "{\"text\":" + q(title + (desc.isEmpty() ? "" : " " + desc) + "\n") + "},"
                + "{\"text\":" + q(button) + ","
                + "\"clickEvent\":{\"action\":\"open_url\",\"value\":" + q(url) + "},"
                + "\"hoverEvent\":{\"action\":\"show_text\",\"contents\":" + q(hover) + "}}]";
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "minecraft:tellraw " + p.getName() + " " + json);
    }

    /** JSON 문자열 */
    private static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> { }
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }

    static String color(String s) {
        if (s == null) return "";
        char[] b = s.toCharArray();
        for (int i = 0; i < b.length - 1; i++) {
            if (b[i] == '&' && "0123456789AaBbCcDdEeFfKkLlMmNnOoRr".indexOf(b[i + 1]) > -1) {
                b[i] = '§';
                b[i + 1] = Character.toLowerCase(b[i + 1]);
            }
        }
        return new String(b);
    }
}

package kr.devaechu.pokiwardrobe;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** &a 색 코드와 &#RRGGBB 헥스 색을 마크 색 코드로 (PokiName 과 같은 규칙) */
final class Text {

    private static final Pattern HEX = Pattern.compile("&#([0-9a-fA-F]{6})");

    private Text() { }

    static String color(String s) {
        if (s == null) return "";
        Matcher m = HEX.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            StringBuilder rep = new StringBuilder("§x");
            for (char ch : m.group(1).toLowerCase(Locale.ROOT).toCharArray()) rep.append('§').append(ch);
            m.appendReplacement(sb, Matcher.quoteReplacement(rep.toString()));
        }
        m.appendTail(sb);
        char[] b = sb.toString().toCharArray();
        for (int i = 0; i < b.length - 1; i++) {
            if (b[i] == '&' && "0123456789AaBbCcDdEeFfKkLlMmNnOoRr".indexOf(b[i + 1]) > -1) {
                b[i] = '§';
                b[i + 1] = Character.toLowerCase(b[i + 1]);
            }
        }
        return new String(b);
    }

    static List<String> color(List<String> lines) {
        List<String> out = new ArrayList<>();
        for (String l : lines) out.add(color(l));
        return out;
    }
}

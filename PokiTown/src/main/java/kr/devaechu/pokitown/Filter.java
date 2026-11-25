package kr.devaechu.pokitown;

import java.util.List;
import java.util.Locale;

/** 마을 이름 욕설 거르기 — 포키폰 SNS 필터(SnsFilter)와 같은 목록. 한글/영문만 남겨서 봄 */
final class Filter {
    private Filter() {}

    private static final List<String> WORDS = List.of(
            "시발", "씨발", "씨빨", "시빨", "씨바", "시바새", "ㅅㅂ", "ㅆㅂ", "십새", "씹새",
            "병신", "븅신", "빙신", "ㅂㅅ", "ㅄ",
            "좆", "존나", "졸라", "ㅈㄴ", "ㅈ같",
            "개새", "개세끼", "개색", "개쌔", "ㅅㄲ",
            "니애미", "니엄마", "느금", "느개비", "ㄴㄱㅁ",
            "지랄", "ㅈㄹ", "미친놈", "미친년", "미친새", "또라이", "닥쳐",
            "엠창", "염병", "육시럴", "썅", "쌍년", "쌍놈", "창녀", "섹스", "야동",
            "fuck", "shit", "bitch", "sibal", "ssibal", "tlqkf", "qudtls");

    static boolean profane(String text) {
        if (text == null || text.isEmpty()) return false;
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) sb.append(Character.toLowerCase(c));
        }
        String s = sb.toString().toLowerCase(Locale.ROOT);
        for (String w : WORDS) if (s.contains(w)) return true;
        return false;
    }
}

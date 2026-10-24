package kr.devaechu.pokistats;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 작은 JSON 만들기 (의존성 없이). 이벤트 한 줄 = 객체 하나 */
final class Json {

    private final Map<String, String> fields = new LinkedHashMap<>();

    /** 공통 필드: id(중복 방지용 고유값), t(종류), at(ms) */
    static Json event(String type, long at) {
        return new Json().put("id", UUID.randomUUID().toString()).put("t", type).put("at", at);
    }

    Json put(String k, String v) {
        fields.put(k, v == null ? "null" : str(v));
        return this;
    }

    Json put(String k, long v) {
        fields.put(k, Long.toString(v));
        return this;
    }

    Json put(String k, boolean v) {
        fields.put(k, Boolean.toString(v));
        return this;
    }

    /** 이미 JSON 인 값 그대로 (배열·객체) */
    Json putRaw(String k, String json) {
        fields.put(k, json);
        return this;
    }

    Json putStrings(String k, Collection<String> vs) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String v : vs) {
            if (!first) sb.append(',');
            sb.append(str(v));
            first = false;
        }
        fields.put(k, sb.append(']').toString());
        return this;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (!first) sb.append(',');
            sb.append(str(e.getKey())).append(':').append(e.getValue());
            first = false;
        }
        return sb.append('}').toString();
    }

    static String str(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}

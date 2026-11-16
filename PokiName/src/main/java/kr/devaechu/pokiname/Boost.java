package kr.devaechu.pokiname;

import org.bukkit.Bukkit;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 경험치 부스트 남은 시간 — PokiEvents 모드가 쓰는 config/poki-boost.json 의 endAt 을 읽음 (1초 캐시) */
final class Boost {
    private static final Pattern END = Pattern.compile("\"endAt\"\\s*:\\s*(\\d+)");
    private static long readAt, endAt;

    private Boost() { }

    private static synchronized long endAt() {
        long now = System.currentTimeMillis();
        if (now - readAt < 1000) return endAt;
        readAt = now;
        try {
            File f = new File(Bukkit.getWorldContainer(), "config/poki-boost.json");
            if (!f.isFile()) { endAt = 0; return 0; }
            Matcher m = END.matcher(Files.readString(f.toPath(), StandardCharsets.UTF_8));
            endAt = m.find() ? Long.parseLong(m.group(1)) : 0;
        } catch (Exception e) {
            endAt = 0;
        }
        return endAt;
    }

    /** 남은 시간 "9:59", 부스트 없으면 "" */
    static String left() {
        long rest = endAt() - System.currentTimeMillis();
        if (rest <= 0) return "";
        long sec = rest / 1000;
        return String.format("%d:%02d", sec / 60, sec % 60);
    }
}

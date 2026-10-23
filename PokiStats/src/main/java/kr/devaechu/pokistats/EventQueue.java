package kr.devaechu.pokistats;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.logging.Logger;

/**
 * 아직 못 보낸 기록 (JSON 한 줄씩). 파일(queue.jsonl)에도 저장해서 서버가 꺼지거나 통계 서버가 죽어도 안 잃어버림.
 */
final class EventQueue {

    private final File file;
    private final Logger log;
    private final Deque<String> lines = new ArrayDeque<>();
    private int max;
    private long dropped;

    EventQueue(File file, int max, Logger log) {
        this.file = file;
        this.max = Math.max(1000, max);
        this.log = log;
    }

    synchronized void setMax(int max) {
        this.max = Math.max(1000, max);
        trim();
    }

    synchronized void load() {
        if (!file.exists()) return;
        try {
            for (String l : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                if (!l.isBlank()) lines.addLast(l.strip());
            }
            trim();
            if (!lines.isEmpty()) log.info("못 보낸 기록 " + lines.size() + "개를 불러왔어요 — 다음 전송 때 같이 보냄");
        } catch (IOException e) {
            log.warning("queue.jsonl 을 못 읽었어요: " + e);
        }
    }

    /** 파일로 저장 (임시 파일에 쓰고 바꿔치기 → 쓰다가 꺼져도 깨지지 않음) */
    synchronized void save() {
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            file.getParentFile().mkdirs();
            try (BufferedWriter w = Files.newBufferedWriter(tmp.toPath(), StandardCharsets.UTF_8)) {
                for (String l : lines) {
                    w.write(l);
                    w.newLine();
                }
            }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e2) {
                log.warning("queue.jsonl 저장 실패: " + e2);
            }
        }
    }

    synchronized void add(String json) {
        lines.addLast(json);
        trim();
    }

    /** 앞에서부터 n 개 보기 (지우지 않음) */
    synchronized List<String> peek(int n) {
        List<String> out = new ArrayList<>(Math.min(n, lines.size()));
        for (String l : lines) {
            if (out.size() >= n) break;
            out.add(l);
        }
        return out;
    }

    /** 보낸 만큼 앞에서 지우기 */
    synchronized void drop(int n) {
        for (int i = 0; i < n && !lines.isEmpty(); i++) lines.removeFirst();
    }

    synchronized int size() {
        return lines.size();
    }

    synchronized long dropped() {
        return dropped;
    }

    private void trim() {
        while (lines.size() > max) {
            lines.removeFirst();
            dropped++;
        }
    }
}

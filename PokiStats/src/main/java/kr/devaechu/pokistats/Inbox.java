package kr.devaechu.pokistats;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import kr.devaechu.pokistats.rank.Ranks;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

/**
 * PokiEvents 모드가 inbox 폴더에 남긴 이벤트 파일(.jsonl)을 읽어서
 * ① 통계 서버 전송 큐에 넣고 ② 인게임 순위 DB 에도 같이 반영한 뒤 파일을 지움.
 */
final class Inbox {

    private final File dir;
    private final EventQueue queue;
    private final Ranks ranks;
    private final Logger log;
    long read;

    Inbox(File dir, EventQueue queue, Ranks ranks, Logger log) {
        this.dir = dir;
        this.queue = queue;
        this.ranks = ranks;
        this.log = log;
        dir.mkdirs();
    }

    /** 비동기 스레드에서 1초마다 */
    synchronized void poll() {
        File[] files = dir.listFiles((d, n) -> n.endsWith(".jsonl"));
        if (files == null || files.length == 0) return;
        Arrays.sort(files);
        List<JsonObject> events = new ArrayList<>();
        for (File f : files) {
            List<String> lines;
            try {
                lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                continue; // 아직 쓰는 중이면 다음에
            }
            for (String l : lines) {
                if (l.isBlank()) continue;
                try {
                    JsonElement el = JsonParser.parseString(l);
                    if (!el.isJsonObject()) continue;
                    JsonObject o = el.getAsJsonObject();
                    if (!o.has("id") || !o.has("t") || !o.has("at")) continue;
                    queue.add(o.toString());
                    events.add(o);
                } catch (RuntimeException ex) {
                    log.warning("inbox 의 깨진 줄은 건너뜀: " + f.getName());
                }
            }
            if (!f.delete()) log.warning("inbox 파일을 못 지웠어요 (다음에 다시 읽어도 id 로 중복 무시됨): " + f.getName());
        }
        read += events.size();
        ranks.apply(events);
    }
}

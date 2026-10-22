package kr.devaechu.pokidonate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 연동 목록 (메모리). 저장은 Db 가, 토큰 갱신은 TokenKeeper 가 한다. */
final class Links {

    private final Map<UUID, Link> byUuid = new ConcurrentHashMap<>();
    private final Map<String, UUID> byChannel = new ConcurrentHashMap<>();

    synchronized void load(Collection<Link> all) {
        byUuid.clear();
        byChannel.clear();
        all.forEach(this::put);
    }

    synchronized void put(Link l) {
        Link old = byUuid.put(l.uuid(), l);
        if (old != null && !old.channelId().equals(l.channelId())) byChannel.remove(old.channelId());
        byChannel.put(l.channelId(), l.uuid());
    }

    synchronized Link remove(UUID uuid) {
        Link old = byUuid.remove(uuid);
        if (old != null) byChannel.remove(old.channelId());
        return old;
    }

    Link get(UUID uuid) {
        return byUuid.get(uuid);
    }

    Link byChannel(String channelId) {
        UUID u = channelId == null ? null : byChannel.get(channelId);
        return u == null ? null : byUuid.get(u);
    }

    /** 마크 닉네임 또는 치지직 채널명으로 찾기 */
    Link byName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (Link l : byUuid.values()) if (l.playerName().toLowerCase(Locale.ROOT).equals(n)) return l;
        for (Link l : byUuid.values()) if (l.channelName().toLowerCase(Locale.ROOT).equals(n)) return l;
        return null;
    }

    List<Link> all() {
        List<Link> out = new ArrayList<>(byUuid.values());
        out.sort((a, b) -> a.playerName().compareToIgnoreCase(b.playerName()));
        return out;
    }
}

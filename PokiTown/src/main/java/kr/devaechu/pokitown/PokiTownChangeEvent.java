package kr.devaechu.pokitown;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * 접속 중인 플레이어의 마을 소속·직책·알바·마을 표시 이름·색이 바뀌면 (메인 스레드에서) 발생.
 * 다른 플러그인은 리플렉션 대신 {@link PokiTownApi#onChange} 로 받아도 됨.
 */
public final class PokiTownChangeEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;

    public PokiTownChangeEvent(Player player) { this.player = player; }

    public Player getPlayer() { return player; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}

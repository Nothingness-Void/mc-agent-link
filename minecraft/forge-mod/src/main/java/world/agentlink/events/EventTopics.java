package world.agentlink.events;

import java.util.Set;

public final class EventTopics {
    public static final String CHAT = "chat";
    public static final String PLAYER_JOIN = "player_join";
    public static final String PLAYER_LEAVE = "player_leave";
    public static final String PLAYER_DEATH = "player_death";

    public static final Set<String> KNOWN = Set.of(CHAT, PLAYER_JOIN, PLAYER_LEAVE, PLAYER_DEATH);

    private EventTopics() {}
}

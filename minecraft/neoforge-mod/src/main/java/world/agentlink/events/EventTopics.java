package world.agentlink.events;

import java.util.Set;

/**
 * Event topics the {@link ForgeEventBridge} publishes into the {@link EventBuffer}.
 *
 * <p>0.4.x carried four: chat, join, leave, death. That is enough to know who is around and answer
 * questions, and not enough to know what happened. An agent asked "who griefed spawn?" or "why did
 * the shop chest empty?" had no way to answer, because the events that would say so were never
 * recorded. The additions below are the ones that make after-the-fact investigation possible —
 * block placement and breaking, container access, command use, explosions.
 *
 * <p>High-frequency topics ({@link #BLOCK_PLACE}, {@link #BLOCK_BREAK}, {@link #ITEM_PICKUP}) are
 * gated behind {@code events.verbose_topics} because a busy server generates thousands per minute
 * and they would otherwise evict everything else from the ring buffer within seconds. See
 * {@link ForgeEventBridge} for the throttling.
 */
public final class EventTopics {

    // --- 0.4.x set (always on) ---------------------------------------------
    public static final String CHAT = "chat";
    public static final String PLAYER_JOIN = "player_join";
    public static final String PLAYER_LEAVE = "player_leave";
    public static final String PLAYER_DEATH = "player_death";

    // --- 0.5.0 additions --------------------------------------------------
    /** A player ran a command. The single most useful audit topic for "who did that". */
    public static final String COMMAND = "command";
    /** A player opened a container (chest, barrel, shulker, ...). */
    public static final String CONTAINER_OPEN = "container_open";
    /** A non-player living entity died. Separate from PLAYER_DEATH so filters stay cheap. */
    public static final String ENTITY_DEATH = "entity_death";
    /** A player respawned, with the location they came back at. */
    public static final String PLAYER_RESPAWN = "player_respawn";
    /** A player moved between dimensions. */
    public static final String DIMENSION_CHANGE = "dimension_change";
    /** A player earned an advancement — useful progression signal for server-side automation. */
    public static final String ADVANCEMENT = "advancement";
    /** Something exploded. Records the epicenter and how many blocks it removed. */
    public static final String EXPLOSION = "explosion";
    /** A player was damaged but survived. */
    public static final String PLAYER_HURT = "player_hurt";
    /** Server-level lifecycle notes emitted by agent-link itself (task finished, and so on). */
    public static final String SERVER = "server";

    // --- verbose (opt-in) -------------------------------------------------
    public static final String BLOCK_PLACE = "block_place";
    public static final String BLOCK_BREAK = "block_break";
    public static final String ITEM_PICKUP = "item_pickup";
    public static final String ITEM_DROP = "item_drop";

    /**
     * Topics recorded only when the operator enables them. They are legitimate and often exactly
     * what an investigation needs — they are simply too voluminous to keep on by default.
     */
    public static final Set<String> VERBOSE = Set.of(
            BLOCK_PLACE, BLOCK_BREAK, ITEM_PICKUP, ITEM_DROP);

    public static final Set<String> KNOWN = Set.of(
            CHAT, PLAYER_JOIN, PLAYER_LEAVE, PLAYER_DEATH,
            COMMAND, CONTAINER_OPEN, ENTITY_DEATH, PLAYER_RESPAWN, DIMENSION_CHANGE,
            ADVANCEMENT, EXPLOSION, PLAYER_HURT, SERVER,
            BLOCK_PLACE, BLOCK_BREAK, ITEM_PICKUP, ITEM_DROP);

    private EventTopics() {}
}

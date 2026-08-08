package world.agentlink.api;

import world.agentlink.config.AgentLinkConfig;
import world.agentlink.dispatch.RequestDispatcher;
import world.agentlink.dispatch.Tool;

import java.util.List;
import java.util.UUID;

/**
 * Public, stable entry point for mc-agent-link addon mods. Everything reachable through this
 * facade is part of the supported API surface; the underlying classes (in
 * {@code world.agentlink.dispatch}, {@code world.agentlink.transport}, {@code world.agentlink.config})
 * are internal and may change between releases.
 *
 * <p>Typical usage from an addon's {@code @Mod} constructor:
 *
 * <pre>{@code
 * public class MyAddon {
 *     public static final String MOD_ID = "myaddon";
 *
 *     public MyAddon() {
 *         AgentLinkApi.registerTool(MOD_ID, new MyEchoTool());
 *     }
 * }
 * }</pre>
 *
 * <p>Tools registered here are auto-prefixed with {@code <modid>__} so they coexist safely with
 * built-in tools and with other addons. The MCP host (Claude Code etc.) sees them in
 * {@code tools/list}; in-game approval flows them through the same {@link Roles} gate as
 * built-in tools.
 */
public final class AgentLinkApi {

    private AgentLinkApi() {}

    private static final AgentRequestApi REQUESTS = new AgentRequestApi();
    private static final AgentEventApi EVENTS = new AgentEventApi();
    private static final AgentTaskApi TASKS = new AgentTaskApi();
    private static final AgentServerApi SERVER = new AgentServerApi();
    private static final AgentDiagnosticsApi DIAGNOSTICS = new AgentDiagnosticsApi();
    private static final AgentBuildZoneApi BUILD_ZONES = new AgentBuildZoneApi();
    private static final AgentRoleApi ROLES = new AgentRoleApi();
    private static final AgentMessageApi MESSAGES = new AgentMessageApi();
    private static final AgentNbtApi NBT = new AgentNbtApi();
    private static final AgentItemApi ITEMS = new AgentItemApi();
    private static final AgentPlayerApi PLAYERS = new AgentPlayerApi();
    private static final AgentInventoryApi INVENTORY = new AgentInventoryApi();
    private static final AgentEffectApi EFFECTS = new AgentEffectApi();
    private static final AgentEntityApi ENTITIES = new AgentEntityApi();
    private static final AgentEntitySpawnApi ENTITY_SPAWN = new AgentEntitySpawnApi();
    private static final AgentWorldApi WORLDS = new AgentWorldApi();
    private static final AgentChunkApi CHUNKS = new AgentChunkApi();
    private static final AgentScoreboardApi SCOREBOARDS = new AgentScoreboardApi();
    private static final AgentServerControlApi SERVER_CONTROL = new AgentServerControlApi();
    private static final AgentContainerApi CONTAINERS = new AgentContainerApi();
    private static final AgentProgressionApi PROGRESSION = new AgentProgressionApi();
    private static final AgentBlockApi BLOCKS = new AgentBlockApi();

    /** Shared in-game request queue and request-owner notifications. */
    public static AgentRequestApi requests() {
        return REQUESTS;
    }

    /** Event history and live event publication facade. */
    public static AgentEventApi events() {
        return EVENTS;
    }

    /** Task status and cooperative cancellation facade. */
    public static AgentTaskApi tasks() {
        return TASKS;
    }

    /** Server lifecycle and main-thread bridge. */
    public static AgentServerApi server() {
        return SERVER;
    }

    /** Bounded server health snapshots and conservative candidate diagnosis. */
    public static AgentDiagnosticsApi diagnostics() {
        return DIAGNOSTICS;
    }

    /** Read-only operator-declared build-zone geometry. */
    public static AgentBuildZoneApi buildZones() {
        return BUILD_ZONES;
    }

    /** Shared admin/guest identity queries. */
    public static AgentRoleApi roles() {
        return ROLES;
    }

    /** Chat delivery for addon status and player-facing actions. */
    public static AgentMessageApi messages() {
        return MESSAGES;
    }

    /** Lossless SNBT/JSON conversion and vanilla NBT-path operations. */
    public static AgentNbtApi nbt() {
        return NBT;
    }

    /** Vanilla item-spec parser shared by tools and addon mods. */
    public static AgentItemApi items() {
        return ITEMS;
    }

    /** Online player lookup and operator/ban/whitelist administration. */
    public static AgentPlayerApi players() {
        return PLAYERS;
    }

    /** Slot-level player inventory operations with client synchronization. */
    public static AgentInventoryApi inventory() {
        return INVENTORY;
    }

    /** Typed status-effect lookup and application for living entities. */
    public static AgentEffectApi effects() {
        return EFFECTS;
    }

    /** Loaded-entity lookup and typed entity controls. */
    public static AgentEntityApi entities() {
        return ENTITIES;
    }

    /** Native entity creation with optional NBT and common mob initialization controls. */
    public static AgentEntitySpawnApi entitySpawn() {
        return ENTITY_SPAWN;
    }

    /** Dimension lookup and typed spawn/world-border controls. */
    public static AgentWorldApi worlds() {
        return WORLDS;
    }

    /** Force-loaded chunk lifecycle operations. */
    public static AgentChunkApi chunks() {
        return CHUNKS;
    }

    /** Scoreboard objectives, scores, display slots, and teams. */
    public static AgentScoreboardApi scoreboards() {
        return SCOREBOARDS;
    }

    /** Save, reload, stop, and runtime-distance controls. */
    public static AgentServerControlApi serverControl() {
        return SERVER_CONTROL;
    }

    /** Typed block-container slot operations. */
    public static AgentContainerApi containers() {
        return CONTAINERS;
    }

    /** Player recipes and advancement progress. */
    public static AgentProgressionApi progression() {
        return PROGRESSION;
    }

    /** Native block parser, undoable single writes, fills, and undo-stack access. */
    public static AgentBlockApi blocks() {
        return BLOCKS;
    }

    // ---------- Tool registration ----------

    /**
     * Register a tool exposed to MCP hosts and the WebSocket transport. Returns the actual
     * registered name including the auto-prefix {@code <modId>__<tool.name()>}. Safe to call
     * before the server has started — the registration is queued until the dispatcher is built.
     *
     * @throws IllegalArgumentException if {@code modId} or {@code tool.name()} is blank.
     */
    public static String registerTool(String modId, Tool tool) {
        return RequestDispatcher.registerAddonTool(modId, tool);
    }

    // ---------- Roles ----------

    /** Player UUIDs configured under {@code [roles].admin_uuids} in agent-link.toml. */
    public static List<UUID> adminUuids() {
        return roles().adminUuids();
    }

    /** Player UUIDs configured under {@code [roles].guest_uuids}. May be empty when unset. */
    public static List<UUID> guestUuids() {
        return roles().guestUuids();
    }

    /**
     * True when the given player is configured as an admin (see {@link #adminUuids()}). Returns
     * false for null UUIDs and when the admin list is empty.
     */
    public static boolean isAdmin(UUID uuid) {
        return roles().isAdmin(uuid);
    }

    /**
     * True when the given player is treated as guest. A player is guest if they are explicitly
     * listed under {@code [roles].guest_uuids}, or — when both lists are non-empty — they are
     * NOT an admin. Returns false when the admin list is empty (no role enforcement).
     */
    public static boolean isGuest(UUID uuid) {
        return roles().isGuest(uuid);
    }

    /** Effective in-game role for an identity and its vanilla permission level. */
    public static AgentRoleApi.Role role(UUID uuid, boolean hasOpPermission) {
        return roles().role(uuid, hasOpPermission);
    }

    /** True when an identity may use an in-game Agent command or GUI. */
    public static boolean canInteract(UUID uuid, boolean hasOpPermission) {
        return roles().canInteract(uuid, hasOpPermission);
    }

    // ---------- Config snapshot ----------

    /**
     * Read-only view of the base mod's current configuration. Returns null only during very
     * early startup before {@code AgentLinkConfig.load()} has run.
     */
    public static ConfigView config() {
        AgentLinkConfig.Snapshot snap = AgentLinkConfig.get();
        return snap == null ? null : new ConfigView(snap);
    }

    // ---------- Build zones ----------

    /**
     * True when {@code (x, y, z)} in {@code dimension} falls inside an operator-declared build zone.
     *
     * <p>Exposed for addons that do their own world writing: an addon can use this to mirror the base
     * mod's geometric permission model instead of inventing a second one the operator has to
     * configure separately. Returns false when no zones are configured — the safe default, matching
     * how the base mod treats an undeclared footprint as unbounded.
     *
     * <p>Note this reports containment only. It does not perform an approval check; an addon tool
     * still goes through the ordinary approval pipeline, and only tools the base mod recognizes as
     * spatial are eligible for the geometric exemption.
     */
    public static boolean isInBuildZone(String dimension, int x, int y, int z) {
        return buildZones().containsPoint(dimension, x, y, z);
    }

    /** Whether the operator configured any build zones at all. */
    public static boolean buildZonesConfigured() {
        return buildZones().configured();
    }

    /** Stable subset of the internal {@code AgentLinkConfig.Snapshot}. */
    public static final class ConfigView {
        private final AgentLinkConfig.Snapshot snap;
        ConfigView(AgentLinkConfig.Snapshot snap) { this.snap = snap; }
        public String version() { return snap.version(); }
        public String mcpToken() { return snap.token(); }
        public String mcpEndpoint() {
            String host = snap.allowRemote() ? "0.0.0.0" : "127.0.0.1";
            return "http://" + host + ":" + snap.mcpListenPort() + "/mcp";
        }
        public boolean mcpEnabled() { return snap.mcpEnabled(); }
        public int mcpListenPort() { return snap.mcpListenPort(); }
        public boolean approvalEnabled() { return snap.approvalEnabled(); }
        public int approvalTimeoutSeconds() { return snap.approvalTimeoutSeconds(); }
    }
}

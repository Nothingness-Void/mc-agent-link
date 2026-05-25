package world.agentlink.api;

import world.agentlink.config.AgentLinkConfig;
import world.agentlink.dispatch.RequestDispatcher;
import world.agentlink.dispatch.Tool;

import java.util.Collections;
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
        AgentLinkConfig.Snapshot snap = AgentLinkConfig.get();
        if (snap == null) return Collections.emptyList();
        List<UUID> list = snap.roleAdminUuids();
        return list == null ? Collections.emptyList() : list;
    }

    /** Player UUIDs configured under {@code [roles].guest_uuids}. May be empty when unset. */
    public static List<UUID> guestUuids() {
        AgentLinkConfig.Snapshot snap = AgentLinkConfig.get();
        if (snap == null) return Collections.emptyList();
        List<UUID> list = snap.roleGuestUuids();
        return list == null ? Collections.emptyList() : list;
    }

    /**
     * True when the given player is configured as an admin (see {@link #adminUuids()}). Returns
     * false for null UUIDs and when the admin list is empty.
     */
    public static boolean isAdmin(UUID uuid) {
        if (uuid == null) return false;
        return adminUuids().contains(uuid);
    }

    /**
     * True when the given player is treated as guest. A player is guest if they are explicitly
     * listed under {@code [roles].guest_uuids}, or — when both lists are non-empty — they are
     * NOT an admin. Returns false when the admin list is empty (no role enforcement).
     */
    public static boolean isGuest(UUID uuid) {
        if (uuid == null) return false;
        if (guestUuids().contains(uuid)) return true;
        List<UUID> admins = adminUuids();
        if (admins.isEmpty()) return false;
        return !admins.contains(uuid);
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

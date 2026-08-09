package world.agentlink.api;

import world.agentlink.config.AgentLinkConfig;

import java.util.List;
import java.util.UUID;

/** Stable role and identity queries shared by addon mods. */
public final class AgentRoleApi {

    /** Effective in-game role. CONSOLE tokens are evaluated separately by the MCP transport. */
    public enum Role {
        ADMIN,
        OP,
        PLAYER
    }

    AgentRoleApi() {}

    public List<UUID> adminUuids() {
        AgentLinkConfig.Snapshot snapshot = AgentLinkConfig.get();
        return snapshot == null || snapshot.roleAdminUuids() == null
                ? List.of()
                : List.copyOf(snapshot.roleAdminUuids());
    }

    public List<UUID> guestUuids() {
        AgentLinkConfig.Snapshot snapshot = AgentLinkConfig.get();
        return snapshot == null || snapshot.roleGuestUuids() == null
                ? List.of()
                : List.copyOf(snapshot.roleGuestUuids());
    }

    public boolean isAdmin(UUID uuid) {
        return uuid != null && adminUuids().contains(uuid);
    }

    public boolean isGuest(UUID uuid) {
        if (uuid == null) return false;
        if (guestUuids().contains(uuid)) return true;
        List<UUID> admins = adminUuids();
        return !admins.isEmpty() && !admins.contains(uuid);
    }

    /**
     * Classify a player using the configured admin UUIDs and vanilla permission level.
     * An explicitly configured admin keeps the ADMIN role even when they are not OP.
     */
    public Role role(UUID uuid, boolean hasOpPermission) {
        if (isAdmin(uuid)) return Role.ADMIN;
        return hasOpPermission ? Role.OP : Role.PLAYER;
    }

    /** True when the player may use an in-game Agent entry point. */
    public boolean canInteract(UUID uuid, boolean hasOpPermission) {
        return role(uuid, hasOpPermission) != Role.PLAYER;
    }
}

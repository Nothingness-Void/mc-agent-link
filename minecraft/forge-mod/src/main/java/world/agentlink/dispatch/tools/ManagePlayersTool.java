package world.agentlink.dispatch.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import world.agentlink.api.AgentApiException;
import world.agentlink.api.AgentLinkApi;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolArgs;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

import java.util.UUID;

/** Structured player administration without forcing agents through command output parsing. */
public final class ManagePlayersTool implements Tool {

    private final MinecraftServer mc;

    public ManagePlayersTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "manage_players";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        String action = ToolArgs.requireString(args, "action").trim().toLowerCase();
        try {
            return switch (action) {
                case "list" -> list();
                case "kick" -> kick(args);
                case "ban" -> ban(args);
                case "pardon", "unban" -> pardon(args);
                case "ban_ip" -> banIp(args);
                case "pardon_ip", "unban_ip" -> pardonIp(args);
                case "op" -> op(args);
                case "deop" -> deop(args);
                case "whitelist_add" -> whitelistAdd(args);
                case "whitelist_remove" -> whitelistRemove(args);
                case "whitelist_enable" -> whitelistToggle(true);
                case "whitelist_disable" -> whitelistToggle(false);
                case "whitelist_reload" -> whitelistReload();
                default -> throw new ToolException("INVALID_ARGS", "Unknown player action: " + action);
            };
        } catch (AgentApiException ex) {
            throw new ToolException(ex.code(), ex.getMessage());
        }
    }

    private JsonObject list() throws AgentApiException {
        JsonObject result = new JsonObject();
        JsonArray online = new JsonArray();
        for (ServerPlayer player : AgentLinkApi.players().online(mc)) {
            JsonObject row = new JsonObject();
            row.addProperty("name", player.getGameProfile().getName());
            row.addProperty("uuid", player.getUUID().toString());
            row.addProperty("op", mc.getPlayerList().isOp(player.getGameProfile()));
            row.addProperty("ip", player.getIpAddress());
            online.add(row);
        }
        result.add("online", online);
        result.add("ops", strings(AgentLinkApi.players().opNames(mc)));
        result.add("whitelist", strings(AgentLinkApi.players().whitelistNames(mc)));
        result.add("banned_players", strings(AgentLinkApi.players().bannedNames(mc)));
        result.add("banned_ips", strings(AgentLinkApi.players().bannedIps(mc)));
        result.addProperty("whitelist_enabled", mc.getPlayerList().isUsingWhitelist());
        return result;
    }

    private JsonObject kick(JsonObject args) throws AgentApiException {
        ServerPlayer player = online(args);
        String reason = ToolArgs.optString(args, "reason", "Kicked by an operator");
        AgentLinkApi.players().kick(mc, player, reason);
        return changed("kick", player.getGameProfile());
    }

    private JsonObject ban(JsonObject args) throws AgentApiException {
        GameProfile profile = profile(args);
        boolean changed = AgentLinkApi.players().ban(mc, profile,
                duration(args), ToolArgs.optString(args, "source", "agent-link"),
                ToolArgs.optString(args, "reason", "Banned by an operator"));
        JsonObject result = changed("ban", profile);
        result.addProperty("changed", changed);
        result.addProperty("expires_in_seconds", duration(args));
        return result;
    }

    private JsonObject pardon(JsonObject args) throws AgentApiException {
        GameProfile profile = profile(args);
        JsonObject result = changed("pardon", profile);
        result.addProperty("changed", AgentLinkApi.players().pardon(mc, profile));
        return result;
    }

    private JsonObject banIp(JsonObject args) throws AgentApiException {
        String ip = ToolArgs.optString(args, "ip", null);
        if (ip == null || ip.isBlank()) ip = online(args).getIpAddress();
        boolean changed = AgentLinkApi.players().banIp(mc, ip, duration(args),
                ToolArgs.optString(args, "source", "agent-link"),
                ToolArgs.optString(args, "reason", "Banned by an operator"));
        JsonObject result = new JsonObject();
        result.addProperty("action", "ban_ip");
        result.addProperty("ip", ip);
        result.addProperty("changed", changed);
        return result;
    }

    private JsonObject pardonIp(JsonObject args) throws AgentApiException, ToolException {
        String ip = ToolArgs.requireString(args, "ip");
        JsonObject result = new JsonObject();
        result.addProperty("action", "pardon_ip");
        result.addProperty("ip", ip);
        result.addProperty("changed", AgentLinkApi.players().pardonIp(mc, ip));
        return result;
    }

    private JsonObject op(JsonObject args) throws AgentApiException {
        GameProfile profile = profile(args);
        int level = ToolArgs.optInt(args, "level", mc.getOperatorUserPermissionLevel());
        boolean bypass = ToolArgs.optBool(args, "bypass_player_limit", false);
        AgentLinkApi.players().op(mc, profile, level, bypass);
        JsonObject result = changed("op", profile);
        result.addProperty("level", level);
        result.addProperty("bypass_player_limit", bypass);
        return result;
    }

    private JsonObject deop(JsonObject args) throws AgentApiException {
        GameProfile profile = profile(args);
        JsonObject result = changed("deop", profile);
        result.addProperty("changed", AgentLinkApi.players().deop(mc, profile));
        return result;
    }

    private JsonObject whitelistAdd(JsonObject args) throws AgentApiException {
        GameProfile profile = profile(args);
        JsonObject result = changed("whitelist_add", profile);
        result.addProperty("changed", AgentLinkApi.players().whitelistAdd(mc, profile));
        return result;
    }

    private JsonObject whitelistRemove(JsonObject args) throws AgentApiException {
        GameProfile profile = profile(args);
        JsonObject result = changed("whitelist_remove", profile);
        result.addProperty("changed", AgentLinkApi.players().whitelistRemove(mc, profile));
        return result;
    }

    private JsonObject whitelistToggle(boolean enabled) throws AgentApiException {
        AgentLinkApi.players().setWhitelistEnabled(mc, enabled);
        JsonObject result = new JsonObject();
        result.addProperty("action", enabled ? "whitelist_enable" : "whitelist_disable");
        result.addProperty("enabled", enabled);
        return result;
    }

    private JsonObject whitelistReload() throws AgentApiException {
        AgentLinkApi.players().reloadWhitelist(mc);
        JsonObject result = new JsonObject();
        result.addProperty("action", "whitelist_reload");
        result.addProperty("reloaded", true);
        return result;
    }

    private GameProfile profile(JsonObject args) throws AgentApiException {
        return AgentLinkApi.players().profile(mc, playerName(args), uuid(args, "uuid"));
    }

    private ServerPlayer online(JsonObject args) throws AgentApiException {
        UUID uuid = uuid(args, "uuid");
        if (uuid != null) return AgentLinkApi.players().online(mc, uuid);
        return AgentLinkApi.players().online(mc, playerName(args));
    }

    private static String playerName(JsonObject args) {
        String name = ToolArgs.optString(args, "name", null);
        return name == null ? ToolArgs.optString(args, "player_name", null) : name;
    }

    private static UUID uuid(JsonObject args, String key) throws AgentApiException {
        String raw = ToolArgs.optString(args, key, null);
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw new AgentApiException("INVALID_ARGS", "Invalid UUID: " + raw);
        }
    }

    private static long duration(JsonObject args) throws AgentApiException {
        long seconds = ToolArgs.optLong(args, "duration_seconds", 0);
        if (seconds < 0) throw new AgentApiException("INVALID_ARGS", "duration_seconds must be >= 0");
        return seconds;
    }

    private static JsonObject changed(String action, GameProfile profile) {
        JsonObject result = new JsonObject();
        result.addProperty("action", action);
        result.addProperty("name", profile.getName());
        result.addProperty("uuid", profile.getId().toString());
        return result;
    }

    private static JsonArray strings(String[] values) {
        JsonArray array = new JsonArray();
        if (values != null) for (String value : values) array.add(value);
        return array;
    }
}

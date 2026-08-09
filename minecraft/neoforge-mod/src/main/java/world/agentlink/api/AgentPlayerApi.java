package world.agentlink.api;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.IpBanListEntry;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.server.players.UserBanListEntry;
import net.minecraft.server.players.UserWhiteListEntry;
import net.minecraft.world.level.GameType;

import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Player lookup, inventory-independent administration, and server list management. */
public final class AgentPlayerApi {

    public record GameModeChange(GameType previous, GameType current, boolean changed) {}

    AgentPlayerApi() {}

    public List<ServerPlayer> online(MinecraftServer server) throws AgentApiException {
        if (server == null) throw new AgentApiException("SERVER_UNAVAILABLE", "server is not running");
        return List.copyOf(server.getPlayerList().getPlayers());
    }

    public ServerPlayer online(MinecraftServer server, String name) throws AgentApiException {
        if (server == null) throw new AgentApiException("SERVER_UNAVAILABLE", "server is not running");
        if (name == null || name.isBlank()) throw new AgentApiException("INVALID_ARGS", "name is required");
        ServerPlayer exact = server.getPlayerList().getPlayerByName(name.trim());
        if (exact != null) return exact;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getGameProfile().getName().equalsIgnoreCase(name.trim())) return player;
        }
        throw new AgentApiException("NOT_FOUND", "Player is not online: " + name);
    }

    public ServerPlayer online(MinecraftServer server, UUID uuid) throws AgentApiException {
        if (server == null) throw new AgentApiException("SERVER_UNAVAILABLE", "server is not running");
        if (uuid == null) throw new AgentApiException("INVALID_ARGS", "uuid is required");
        ServerPlayer player = server.getPlayerList().getPlayer(uuid);
        if (player == null) throw new AgentApiException("NOT_FOUND", "Player is not online: " + uuid);
        return player;
    }

    public void setHealth(ServerPlayer player, float health) throws AgentApiException {
        requirePlayer(player);
        if (!Float.isFinite(health) || health < 0 || health > player.getMaxHealth()) {
            throw new AgentApiException("INVALID_ARGS", "health must be between 0 and " + player.getMaxHealth());
        }
        player.setHealth(health);
    }

    public void setAbsorption(ServerPlayer player, float absorption) throws AgentApiException {
        requirePlayer(player);
        if (!Float.isFinite(absorption) || absorption < 0) {
            throw new AgentApiException("INVALID_ARGS", "absorption must be finite and >= 0");
        }
        player.setAbsorptionAmount(absorption);
    }

    public void setFood(ServerPlayer player, int food, float saturation) throws AgentApiException {
        requirePlayer(player);
        if (food < 0 || food > 20 || !Float.isFinite(saturation) || saturation < 0) {
            throw new AgentApiException("INVALID_ARGS", "food must be 0..20 and saturation must be >= 0");
        }
        player.getFoodData().setFoodLevel(food);
        player.getFoodData().setSaturation(saturation);
    }

    public void setAir(ServerPlayer player, int air) throws AgentApiException {
        requirePlayer(player);
        if (air < 0 || air > player.getMaxAirSupply()) {
            throw new AgentApiException("INVALID_ARGS", "air must be 0.." + player.getMaxAirSupply());
        }
        player.setAirSupply(air);
    }

    public void setExperience(ServerPlayer player, Integer points, Integer levels)
            throws AgentApiException {
        requirePlayer(player);
        if (points != null) {
            if (points < 0) throw new AgentApiException("INVALID_ARGS", "experience_points must be >= 0");
            player.setExperiencePoints(points);
        }
        if (levels != null) {
            if (levels < 0) throw new AgentApiException("INVALID_ARGS", "experience_levels must be >= 0");
            player.setExperienceLevels(levels);
        }
    }

    public void setFire(ServerPlayer player, int seconds) throws AgentApiException {
        requirePlayer(player);
        if (seconds < 0 || seconds > 3600) throw new AgentApiException("INVALID_ARGS", "fire_seconds must be 0..3600");
        if (seconds == 0) player.clearFire(); else player.igniteForSeconds(seconds);
    }

    public void setAbilities(ServerPlayer player, Boolean invulnerable, Boolean mayFly, Boolean flying)
            throws AgentApiException {
        requirePlayer(player);
        if (invulnerable != null) player.getAbilities().invulnerable = invulnerable;
        if (mayFly != null) player.getAbilities().mayfly = mayFly;
        if (flying != null) player.getAbilities().flying = flying;
        player.onUpdateAbilities();
    }

    /** Change a player's game mode using vanilla names or numeric aliases. */
    public GameModeChange setGameMode(ServerPlayer player, String value) throws AgentApiException {
        requirePlayer(player);
        String raw = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        GameType mode = switch (raw) {
            case "survival", "s", "0" -> GameType.SURVIVAL;
            case "creative", "c", "1" -> GameType.CREATIVE;
            case "adventure", "a", "2" -> GameType.ADVENTURE;
            case "spectator", "sp", "3" -> GameType.SPECTATOR;
            default -> throw new AgentApiException("INVALID_ARGS",
                    "gamemode must be survival, creative, adventure, or spectator");
        };
        GameType before = player.gameMode.getGameModeForPlayer();
        boolean changed = before != mode;
        if (changed) player.setGameMode(mode);
        return new GameModeChange(before, player.gameMode.getGameModeForPlayer(), changed);
    }

    /** Resolve a profile for online or cached/offline administration. */
    public GameProfile profile(MinecraftServer server, String name, UUID uuid) throws AgentApiException {
        if (server == null) throw new AgentApiException("SERVER_UNAVAILABLE", "server is not running");
        if (uuid != null) {
            ServerPlayer online = server.getPlayerList().getPlayer(uuid);
            if (online != null) return online.getGameProfile();
            Optional<GameProfile> cached = server.getProfileCache().get(uuid);
            if (cached.isPresent()) return cached.get();
            if (name != null && !name.isBlank()) return new GameProfile(uuid, name.trim());
            throw new AgentApiException("PROFILE_NOT_FOUND",
                    "UUID is not online or cached; provide player_name with the UUID: " + uuid);
        }
        if (name == null || name.isBlank()) {
            throw new AgentApiException("INVALID_ARGS", "name or uuid is required");
        }
        ServerPlayer online = server.getPlayerList().getPlayerByName(name.trim());
        if (online != null) return online.getGameProfile();
        Optional<GameProfile> cached = server.getProfileCache().get(name.trim());
        if (cached.isPresent()) return cached.get();
        throw new AgentApiException("PROFILE_NOT_FOUND",
                "Player is offline and not cached: " + name
                        + ". Provide uuid, or let the player join once first.");
    }

    public void kick(MinecraftServer server, ServerPlayer player, String reason) throws AgentApiException {
        requireServer(server);
        if (player == null) throw new AgentApiException("NOT_FOUND", "player is required");
        player.connection.disconnect(Component.literal(nonBlank(reason, "Kicked by an operator")));
    }

    public boolean op(MinecraftServer server, GameProfile profile, int level, boolean bypassLimit)
            throws AgentApiException {
        requireServer(server);
        validateOpLevel(level);
        var ops = server.getPlayerList().getOps();
        ops.remove(profile);
        ops.add(new ServerOpListEntry(profile, level, bypassLimit));
        ServerPlayer online = server.getPlayerList().getPlayer(profile.getId());
        if (online != null) server.getPlayerList().sendPlayerPermissionLevel(online);
        return true;
    }

    public boolean deop(MinecraftServer server, GameProfile profile) throws AgentApiException {
        requireServer(server);
        boolean existed = server.getPlayerList().isOp(profile);
        server.getPlayerList().deop(profile);
        ServerPlayer online = server.getPlayerList().getPlayer(profile.getId());
        if (online != null) server.getPlayerList().sendPlayerPermissionLevel(online);
        return existed;
    }

    public boolean ban(MinecraftServer server, GameProfile profile, long durationSeconds,
                       String source, String reason) throws AgentApiException {
        requireServer(server);
        Date now = new Date();
        Date expires = durationSeconds > 0 ? new Date(now.getTime() + durationSeconds * 1000L) : null;
        boolean existed = server.getPlayerList().getBans().isBanned(profile);
        server.getPlayerList().getBans().add(new UserBanListEntry(profile, now,
                nonBlank(source, "agent-link"), expires, nonBlank(reason, "Banned by an operator")));
        ServerPlayer online = server.getPlayerList().getPlayer(profile.getId());
        if (online != null) {
            online.connection.disconnect(Component.literal(nonBlank(reason, "Banned by an operator")));
        }
        return !existed;
    }

    public boolean pardon(MinecraftServer server, GameProfile profile) throws AgentApiException {
        requireServer(server);
        boolean existed = server.getPlayerList().getBans().isBanned(profile);
        server.getPlayerList().getBans().remove(profile);
        return existed;
    }

    public boolean banIp(MinecraftServer server, String ip, long durationSeconds,
                         String source, String reason) throws AgentApiException {
        requireServer(server);
        String normalized = normalizeIp(ip);
        Date now = new Date();
        Date expires = durationSeconds > 0 ? new Date(now.getTime() + durationSeconds * 1000L) : null;
        boolean existed = server.getPlayerList().getIpBans().isBanned(normalized);
        server.getPlayerList().getIpBans().add(new IpBanListEntry(normalized, now,
                nonBlank(source, "agent-link"), expires, nonBlank(reason, "Banned by an operator")));
        for (ServerPlayer player : server.getPlayerList().getPlayersWithAddress(normalized)) {
            player.connection.disconnect(Component.literal(nonBlank(reason, "Banned by an operator")));
        }
        return !existed;
    }

    public boolean pardonIp(MinecraftServer server, String ip) throws AgentApiException {
        requireServer(server);
        String normalized = normalizeIp(ip);
        boolean existed = server.getPlayerList().getIpBans().isBanned(normalized);
        server.getPlayerList().getIpBans().remove(normalized);
        return existed;
    }

    public boolean whitelistAdd(MinecraftServer server, GameProfile profile) throws AgentApiException {
        requireServer(server);
        boolean existed = server.getPlayerList().getWhiteList().isWhiteListed(profile);
        server.getPlayerList().getWhiteList().add(new UserWhiteListEntry(profile));
        return !existed;
    }

    public boolean whitelistRemove(MinecraftServer server, GameProfile profile) throws AgentApiException {
        requireServer(server);
        boolean existed = server.getPlayerList().getWhiteList().isWhiteListed(profile);
        server.getPlayerList().getWhiteList().remove(profile);
        return existed;
    }

    public void setWhitelistEnabled(MinecraftServer server, boolean enabled) throws AgentApiException {
        requireServer(server);
        server.getPlayerList().setUsingWhiteList(enabled);
    }

    public void reloadWhitelist(MinecraftServer server) throws AgentApiException {
        requireServer(server);
        server.getPlayerList().reloadWhiteList();
    }

    public String[] whitelistNames(MinecraftServer server) throws AgentApiException {
        requireServer(server);
        return server.getPlayerList().getWhiteListNames();
    }

    public String[] bannedNames(MinecraftServer server) throws AgentApiException {
        requireServer(server);
        return server.getPlayerList().getBans().getUserList();
    }

    public String[] bannedIps(MinecraftServer server) throws AgentApiException {
        requireServer(server);
        return server.getPlayerList().getIpBans().getUserList();
    }

    public String[] opNames(MinecraftServer server) throws AgentApiException {
        requireServer(server);
        return server.getPlayerList().getOpNames();
    }

    private static void validateOpLevel(int level) throws AgentApiException {
        if (level < 1 || level > 4) {
            throw new AgentApiException("INVALID_ARGS", "op level must be between 1 and 4");
        }
    }

    private static void requirePlayer(ServerPlayer player) throws AgentApiException {
        if (player == null) throw new AgentApiException("NOT_FOUND", "player is required");
    }

    private static void requireServer(MinecraftServer server) throws AgentApiException {
        if (server == null) throw new AgentApiException("SERVER_UNAVAILABLE", "server is not running");
    }

    private static String normalizeIp(String ip) throws AgentApiException {
        if (ip == null || ip.isBlank()) throw new AgentApiException("INVALID_ARGS", "ip is required");
        String value = ip.trim();
        if (value.startsWith("/") && value.length() > 1) value = value.substring(1);
        if (value.contains(":")) {
            // IPv6 is valid; a port suffix is not accepted because the ban list stores addresses.
            if (value.startsWith("[") && value.endsWith("]")) value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}

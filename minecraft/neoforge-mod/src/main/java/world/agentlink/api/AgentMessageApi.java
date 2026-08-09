package world.agentlink.api;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Server and player chat delivery helpers for addon status and user-facing controls. */
public final class AgentMessageApi {

    AgentMessageApi() {}

    public int broadcast(MinecraftServer server, Component message) throws AgentApiException {
        if (server == null) throw new AgentApiException("SERVER_UNAVAILABLE", "server is not running");
        if (message == null) throw new AgentApiException("INVALID_ARGS", "message is required");
        server.getPlayerList().broadcastSystemMessage(message, false);
        return server.getPlayerCount();
    }

    public void send(ServerPlayer player, Component message) throws AgentApiException {
        if (player == null) throw new AgentApiException("NOT_FOUND", "player is required");
        if (message == null) throw new AgentApiException("INVALID_ARGS", "message is required");
        player.sendSystemMessage(message);
    }
}

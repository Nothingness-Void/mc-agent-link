package world.agentlink.dispatch.tools;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import world.agentlink.api.AgentApiException;
import world.agentlink.api.AgentLinkApi;
import world.agentlink.api.AgentPlayerApi;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolArgs;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

/**
 * Change a player's game mode.
 *
 * <p>Small tool, but it closes a specific gap: an agent building for a player often needs to put
 * them in spectator to get them out of the way, or into creative to hand them flight. Doing that
 * through {@code run_console_command} means requesting arbitrary-console permission for what is a
 * narrow, reversible action — and the response reports the previous mode so the agent can put it
 * back.
 */
public class SetGamemodeTool implements Tool {

    private final MinecraftServer mc;

    public SetGamemodeTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "set_gamemode";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        try {
            ServerPlayer player = AgentLinkApi.players().online(mc, ToolArgs.requireString(args, "name"));
            AgentPlayerApi.GameModeChange change = AgentLinkApi.players().setGameMode(
                    player, ToolArgs.requireString(args, "gamemode"));

            JsonObject r = new JsonObject();
            r.addProperty("name", player.getGameProfile().getName());
            r.addProperty("uuid", player.getUUID().toString());
            r.addProperty("previous_gamemode", change.previous().getName());
            r.addProperty("gamemode", change.current().getName());
            r.addProperty("changed", change.changed());
            return r;
        } catch (AgentApiException ex) {
            throw new ToolException(ex.code(), ex.getMessage());
        }
    }
}

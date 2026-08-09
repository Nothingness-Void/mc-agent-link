package world.agentlink.dispatch.tools;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import world.agentlink.api.AgentApiException;
import world.agentlink.api.AgentLinkApi;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolArgs;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

/** Explicit server lifecycle and runtime tuning controls. */
public final class ServerControlTool implements Tool {

    private final MinecraftServer mc;

    public ServerControlTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "server_control";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        String action = ToolArgs.requireString(args, "action").trim().toLowerCase();
        try {
            return switch (action) {
                case "save" -> save(args);
                case "reload_resources" -> reloadResources();
                case "stop" -> stop(args);
                case "set_view_distance" -> setViewDistance(args);
                case "set_simulation_distance" -> setSimulationDistance(args);
                case "set_allow_cheats" -> setAllowCheats(args);
                default -> throw new ToolException("INVALID_ARGS", "Unknown server action: " + action);
            };
        } catch (AgentApiException ex) {
            throw new ToolException(ex.code(), ex.getMessage());
        }
    }

    private JsonObject save(JsonObject args) throws AgentApiException {
        boolean flush = ToolArgs.optBool(args, "flush", true);
        var saved = AgentLinkApi.serverControl().save(mc, flush);
        JsonObject result = base("save");
        result.addProperty("saved", saved.saved());
        result.addProperty("flushed", saved.flushed());
        result.addProperty("duration_ms", saved.durationMs());
        return result;
    }

    private JsonObject reloadResources() throws AgentApiException {
        AgentLinkApi.serverControl().reloadResources(mc);
        JsonObject result = base("reload_resources");
        result.addProperty("started", true);
        return result;
    }

    private JsonObject stop(JsonObject args) throws AgentApiException {
        AgentLinkApi.serverControl().stop(mc, ToolArgs.optBool(args, "confirmed", false));
        JsonObject result = base("stop");
        result.addProperty("stopping", true);
        return result;
    }

    private JsonObject setViewDistance(JsonObject args) throws AgentApiException, ToolException {
        int chunks = ToolArgs.requireInt(args, "chunks");
        AgentLinkApi.serverControl().setViewDistance(mc, chunks);
        JsonObject result = base("set_view_distance");
        result.addProperty("chunks", chunks);
        return result;
    }

    private JsonObject setSimulationDistance(JsonObject args) throws AgentApiException, ToolException {
        int chunks = ToolArgs.requireInt(args, "chunks");
        AgentLinkApi.serverControl().setSimulationDistance(mc, chunks);
        JsonObject result = base("set_simulation_distance");
        result.addProperty("chunks", chunks);
        return result;
    }

    private JsonObject setAllowCheats(JsonObject args) throws AgentApiException {
        boolean enabled = ToolArgs.optBool(args, "enabled", false);
        AgentLinkApi.serverControl().setAllowCheatsForAllPlayers(mc, enabled);
        JsonObject result = base("set_allow_cheats");
        result.addProperty("enabled", enabled);
        return result;
    }

    private static JsonObject base(String action) {
        JsonObject result = new JsonObject();
        result.addProperty("action", action);
        return result;
    }
}

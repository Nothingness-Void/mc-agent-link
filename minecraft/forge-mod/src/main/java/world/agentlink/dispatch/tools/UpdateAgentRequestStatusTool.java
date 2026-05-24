package world.agentlink.dispatch.tools;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import world.agentlink.agent.AgentRequestBuffer;
import world.agentlink.dispatch.RequestDispatcher;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

public class UpdateAgentRequestStatusTool implements Tool {

    private final MinecraftServer mc;

    public UpdateAgentRequestStatusTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "update_agent_request_status";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        String id = RequestDispatcher.requireString(args, "id");
        String rawStatus = args.has("status") && !args.get("status").isJsonNull()
                ? args.get("status").getAsString()
                : "working";
        String message = args.has("message") && !args.get("message").isJsonNull()
                ? args.get("message").getAsString()
                : rawStatus;
        boolean notifyPlayer = !args.has("notify_player") || args.get("notify_player").getAsBoolean();

        AgentRequestBuffer.Status status;
        try {
            status = AgentRequestBuffer.parseStatus(rawStatus);
        } catch (IllegalArgumentException e) {
            throw new ToolException("INVALID_ARGS", "Unknown status: " + rawStatus);
        }

        AgentRequestBuffer.Entry entry = AgentRequestBuffer.get().updateStatus(id, status, message);
        if (entry == null) throw new ToolException("INVALID_ARGS", "Unknown agent request id: " + id);
        if (notifyPlayer && message != null && !message.isBlank()) {
            AgentRequestBuffer.sendStatusToPlayer(mc, entry);
        }

        JsonObject r = new JsonObject();
        r.addProperty("updated", true);
        r.add("request", AgentRequestBuffer.get().toJson(entry));
        return r;
    }
}

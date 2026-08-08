package world.agentlink.dispatch.tools;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import world.agentlink.api.AgentLinkApi;
import world.agentlink.api.AgentRequestApi;
import world.agentlink.dispatch.RequestDispatcher;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

import java.util.Locale;

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

        AgentRequestApi.Status status;
        try {
            status = AgentRequestApi.Status.valueOf(rawStatus.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ToolException("INVALID_ARGS", "Unknown status: " + rawStatus);
        }

        AgentRequestApi requests = AgentLinkApi.requests();
        AgentRequestApi.Mutation mutation = requests.updateStatusDetailed(id, status, message);
        if (mutation.outcome() != AgentRequestApi.MutationOutcome.UPDATED) {
            throw mutationError(id, mutation.outcome());
        }
        AgentRequestApi.Request entry = mutation.request();
        if (notifyPlayer && message != null && !message.isBlank()) {
            requests.sendStatusToPlayer(mc, entry);
        }

        JsonObject r = new JsonObject();
        r.addProperty("updated", true);
        r.add("request", requests.toJson(entry));
        return r;
    }

    private static ToolException mutationError(String id, AgentRequestApi.MutationOutcome outcome) {
        return switch (outcome) {
            case NOT_FOUND -> new ToolException("INVALID_ARGS", "Unknown agent request id: " + id);
            case LEASED -> new ToolException("REQUEST_BUSY", "Agent request is owned by another worker: " + id);
            case ALREADY_TERMINAL -> new ToolException("REQUEST_TERMINAL", "Agent request is already terminal: " + id);
            case INVALID_TRANSITION -> new ToolException("INVALID_STATE", "Invalid request status transition: " + id);
            default -> new ToolException("INVALID_STATE", "Agent request was not updated: " + id);
        };
    }
}

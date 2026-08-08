package world.agentlink.dispatch.tools;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import world.agentlink.api.AgentLinkApi;
import world.agentlink.api.AgentRequestApi;
import world.agentlink.dispatch.RequestDispatcher;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

public class ReplyAgentRequestTool implements Tool {

    private final MinecraftServer mc;

    public ReplyAgentRequestTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "reply_agent_request";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        String id = RequestDispatcher.requireString(args, "id");
        String message = RequestDispatcher.requireString(args, "message");
        boolean markDone = !args.has("mark_done") || args.get("mark_done").getAsBoolean();
        boolean notifyPlayer = !args.has("notify_player") || args.get("notify_player").getAsBoolean();

        AgentRequestApi requests = AgentLinkApi.requests();
        AgentRequestApi.Mutation mutation = requests.replyDetailed(id, message, markDone);
        if (mutation.outcome() != AgentRequestApi.MutationOutcome.UPDATED) {
            throw mutationError(id, mutation.outcome());
        }
        AgentRequestApi.Request entry = mutation.request();

        int messagesSent = 0;
        if (notifyPlayer) {
            messagesSent = requests.sendReplyToPlayer(mc, entry);
        }

        JsonObject r = new JsonObject();
        r.addProperty("replied", true);
        r.addProperty("player_notified", messagesSent > 0);
        r.addProperty("messages_sent", messagesSent);
        r.add("request", requests.toJson(entry));
        return r;
    }

    private static ToolException mutationError(String id, AgentRequestApi.MutationOutcome outcome) {
        return switch (outcome) {
            case NOT_FOUND -> new ToolException("INVALID_ARGS", "Unknown agent request id: " + id);
            case LEASED -> new ToolException("REQUEST_BUSY", "Agent request is owned by another worker: " + id);
            case ALREADY_TERMINAL -> new ToolException("REQUEST_TERMINAL", "Agent request is already terminal: " + id);
            default -> new ToolException("INVALID_STATE", "Agent request cannot be replied to: " + id);
        };
    }
}

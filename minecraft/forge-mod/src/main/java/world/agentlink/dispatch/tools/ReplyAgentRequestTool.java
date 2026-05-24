package world.agentlink.dispatch.tools;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import world.agentlink.agent.AgentRequestBuffer;
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

        AgentRequestBuffer.Entry entry = AgentRequestBuffer.get().reply(id, message, markDone);
        if (entry == null) throw new ToolException("INVALID_ARGS", "Unknown agent request id: " + id);

        int messagesSent = 0;
        if (notifyPlayer) {
            messagesSent = AgentRequestBuffer.sendReplyToPlayer(mc, entry);
        }

        JsonObject r = new JsonObject();
        r.addProperty("replied", true);
        r.addProperty("player_notified", messagesSent > 0);
        r.addProperty("messages_sent", messagesSent);
        r.add("request", AgentRequestBuffer.get().toJson(entry));
        return r;
    }
}

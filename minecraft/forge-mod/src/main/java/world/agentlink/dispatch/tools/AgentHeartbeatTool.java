package world.agentlink.dispatch.tools;

import com.google.gson.JsonObject;
import world.agentlink.agent.AgentRequestBuffer;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

public class AgentHeartbeatTool implements Tool {

    @Override
    public String name() {
        return "agent_heartbeat";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        String action = args.has("action") && !args.get("action").isJsonNull()
                ? args.get("action").getAsString()
                : "heartbeat";
        AgentRequestBuffer buf = AgentRequestBuffer.get();
        buf.markAgentSeen(action);

        JsonObject r = new JsonObject();
        r.addProperty("ok", true);
        r.addProperty("last_agent_seen_at", buf.lastAgentSeenAt());
        r.addProperty("last_agent_action", buf.lastAgentAction());
        return r;
    }
}

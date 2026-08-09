package world.agentlink.dispatch.tools;

import com.google.gson.JsonObject;
import world.agentlink.api.AgentLinkApi;
import world.agentlink.api.AgentRequestApi;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

import java.util.List;

public class GetAgentRequestsTool implements Tool {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    @Override
    public String name() {
        return "get_agent_requests";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        long sinceSeq = args.has("since_seq") ? args.get("since_seq").getAsLong() : 0L;
        int limit = args.has("limit") ? args.get("limit").getAsInt() : DEFAULT_LIMIT;
        if (limit <= 0 || limit > MAX_LIMIT) limit = DEFAULT_LIMIT;
        boolean includeDone = args.has("include_done") && args.get("include_done").getAsBoolean();

        AgentRequestApi buf = AgentLinkApi.requests();
        buf.markAgentSeen("get_agent_requests");
        long head = buf.head();
        long effectiveSince = sinceSeq <= 0 ? Math.max(0, head - limit) : sinceSeq;
        List<AgentRequestApi.Request> entries = buf.since(effectiveSince, limit, includeDone);

        JsonObject r = new JsonObject();
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (AgentRequestApi.Request entry : entries) arr.add(buf.toJson(entry));
        r.add("requests", arr);
        r.addProperty("returned", entries.size());
        r.addProperty("head_seq", head);
        r.addProperty("oldest_seq", buf.oldestSeq());
        r.addProperty("buffer_capacity", buf.capacity());
        r.addProperty("truncated", !entries.isEmpty() && entries.get(entries.size() - 1).seq() < head);
        return r;
    }
}

package world.agentlink.dispatch.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import world.agentlink.agent.AgentRequestBuffer;
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

        AgentRequestBuffer buf = AgentRequestBuffer.get();
        long effectiveSince = sinceSeq <= 0 ? Math.max(0, buf.head() - limit) : sinceSeq;
        List<AgentRequestBuffer.Entry> entries = buf.since(effectiveSince, limit, includeDone);
        JsonArray arr = buf.toJsonArray(entries);

        JsonObject r = new JsonObject();
        r.add("requests", arr);
        r.addProperty("returned", arr.size());
        r.addProperty("head_seq", buf.head());
        r.addProperty("oldest_seq", buf.oldestSeq());
        r.addProperty("buffer_capacity", buf.capacity());
        r.addProperty("truncated", !entries.isEmpty()
                && entries.get(entries.size() - 1).seq() < buf.head());
        return r;
    }
}

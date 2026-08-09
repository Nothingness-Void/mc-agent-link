package world.agentlink.dispatch.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.events.EventBuffer;
import world.agentlink.events.EventTopics;
import world.agentlink.transport.ClientSession;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class GetRecentEventsTool implements Tool {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    @Override
    public String name() {
        return "get_recent_events";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        long sinceSeq = args.has("since_seq") ? args.get("since_seq").getAsLong() : 0L;
        int limit = args.has("limit") ? args.get("limit").getAsInt() : DEFAULT_LIMIT;
        if (limit <= 0 || limit > MAX_LIMIT) limit = DEFAULT_LIMIT;

        Set<String> filter = null;
        if (args.has("topics") && args.get("topics").isJsonArray()) {
            filter = new HashSet<>();
            for (JsonElement el : args.getAsJsonArray("topics")) {
                String t = el.getAsString();
                if (!EventTopics.KNOWN.contains(t)) {
                    throw new ToolException("INVALID_ARGS", "Unknown topic: " + t);
                }
                filter.add(t);
            }
        }

        EventBuffer buf = EventBuffer.get();
        // since_seq=0 means "give me a tail of recent events" — start from oldest retained
        long effectiveSince = sinceSeq <= 0 ? Math.max(0, buf.head() - limit) : sinceSeq;
        List<EventBuffer.Entry> entries = buf.since(effectiveSince, limit, filter);

        JsonArray arr = new JsonArray();
        for (EventBuffer.Entry e : entries) {
            JsonObject o = new JsonObject();
            o.addProperty("seq", e.seq());
            o.addProperty("ts", e.ts());
            o.addProperty("topic", e.topic());
            o.add("data", e.data());
            arr.add(o);
        }

        JsonObject r = new JsonObject();
        r.add("events", arr);
        r.addProperty("returned", arr.size());
        r.addProperty("head_seq", buf.head());
        r.addProperty("oldest_seq", buf.oldestSeq());
        r.addProperty("buffer_capacity", buf.capacity());
        // truncated: caller asked for [since_seq+1, head] but got cut off by limit
        r.addProperty("truncated", !entries.isEmpty()
                && entries.get(entries.size() - 1).seq() < buf.head());
        return r;
    }
}

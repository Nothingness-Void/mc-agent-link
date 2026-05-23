package world.agentlink.dispatch.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.logs.LogBuffer;
import world.agentlink.transport.ClientSession;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class GetRecentLogsTool implements Tool {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    @Override
    public String name() {
        return "get_recent_logs";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        long sinceSeq = args.has("since_seq") ? args.get("since_seq").getAsLong() : 0L;
        int limit = args.has("limit") ? args.get("limit").getAsInt() : DEFAULT_LIMIT;
        if (limit <= 0 || limit > MAX_LIMIT) limit = DEFAULT_LIMIT;

        Set<String> levels = null;
        if (args.has("levels") && args.get("levels").isJsonArray()) {
            levels = new HashSet<>();
            for (JsonElement el : args.getAsJsonArray("levels")) {
                levels.add(el.getAsString().toUpperCase());
            }
        }

        String contains = args.has("contains") && !args.get("contains").isJsonNull()
                ? args.get("contains").getAsString()
                : null;

        LogBuffer buf = LogBuffer.get();
        long effectiveSince = sinceSeq <= 0 ? Math.max(0, buf.head() - limit) : sinceSeq;
        List<LogBuffer.Entry> entries = buf.since(effectiveSince, limit, levels, contains);

        JsonArray arr = new JsonArray();
        for (LogBuffer.Entry e : entries) {
            JsonObject o = new JsonObject();
            o.addProperty("seq", e.seq());
            o.addProperty("ts", e.ts());
            o.addProperty("level", e.level());
            o.addProperty("logger", e.logger());
            o.addProperty("message", e.message());
            arr.add(o);
        }

        JsonObject r = new JsonObject();
        r.add("logs", arr);
        r.addProperty("returned", arr.size());
        r.addProperty("head_seq", buf.head());
        r.addProperty("oldest_seq", buf.oldestSeq());
        r.addProperty("buffer_capacity", buf.capacity());
        r.addProperty("truncated", !entries.isEmpty()
                && entries.get(entries.size() - 1).seq() < buf.head());
        return r;
    }
}

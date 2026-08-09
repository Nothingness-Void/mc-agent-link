package world.agentlink.dispatch.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

public class UnsubscribeEventsTool implements Tool {
    @Override
    public String name() {
        return "unsubscribe_events";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        if (!args.has("topics") || !args.get("topics").isJsonArray()) {
            throw new ToolException("INVALID_ARGS", "topics must be an array");
        }
        JsonArray topics = args.getAsJsonArray("topics");
        JsonArray gone = new JsonArray();
        for (JsonElement el : topics) {
            String topic = el.getAsString();
            if (session.subscriptions().remove(topic)) gone.add(topic);
        }
        JsonObject r = new JsonObject();
        r.add("unsubscribed", gone);
        return r;
    }
}

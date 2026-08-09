package world.agentlink.dispatch.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.events.EventTopics;
import world.agentlink.transport.ClientSession;

public class SubscribeEventsTool implements Tool {
    @Override
    public String name() {
        return "subscribe_events";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        if (!args.has("topics") || !args.get("topics").isJsonArray()) {
            throw new ToolException("INVALID_ARGS", "topics must be an array");
        }
        JsonArray topics = args.getAsJsonArray("topics");
        JsonArray subscribed = new JsonArray();
        for (JsonElement el : topics) {
            String topic = el.getAsString();
            if (!EventTopics.KNOWN.contains(topic)) {
                throw new ToolException("INVALID_ARGS", "Unknown topic: " + topic);
            }
            session.subscriptions().add(topic);
            subscribed.add(topic);
        }
        JsonObject r = new JsonObject();
        r.add("subscribed", subscribed);
        return r;
    }
}

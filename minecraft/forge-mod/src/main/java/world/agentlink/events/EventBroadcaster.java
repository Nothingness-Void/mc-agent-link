package world.agentlink.events;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import world.agentlink.transport.ClientSession;

import java.util.Collection;

public final class EventBroadcaster {
    private static final Gson GSON = new Gson();

    private EventBroadcaster() {}

    public static void emit(Collection<ClientSession> sessions, String topic, JsonObject data) {
        if (sessions.isEmpty()) return;

        JsonObject frame = new JsonObject();
        frame.addProperty("v", 0);
        frame.addProperty("type", "event");
        frame.addProperty("topic", topic);
        frame.addProperty("ts", System.currentTimeMillis());
        frame.add("data", data);
        String wire = GSON.toJson(frame);

        for (ClientSession s : sessions) {
            if (s.isAuthenticated() && s.subscriptions().contains(topic)) {
                s.send(wire);
            }
        }
    }
}

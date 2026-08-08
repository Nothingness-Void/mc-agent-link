package world.agentlink.api;

import com.google.gson.JsonObject;
import world.agentlink.events.EventBuffer;
import world.agentlink.events.ForgeEventBridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Stable publish and pull API for agent-link server events. */
public final class AgentEventApi {

    public record Event(long seq, long timestamp, String topic, JsonObject data) {
        public Event {
            topic = topic == null ? "" : topic;
            data = data == null ? new JsonObject() : data.deepCopy();
        }
    }

    AgentEventApi() {}

    /** Append an event and fan it out to authenticated subscribers. */
    public Event publish(String topic, JsonObject data) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic is required");
        }
        return fromEntry(ForgeEventBridge.postAndReturn(topic, data));
    }

    public List<Event> since(long sinceSeq, int limit, Set<String> topics) {
        int safeLimit = limit <= 0 ? 100 : Math.min(limit, 4096);
        List<Event> out = new ArrayList<>();
        for (EventBuffer.Entry entry : EventBuffer.get().since(sinceSeq, safeLimit, topics)) {
            out.add(fromEntry(entry));
        }
        return List.copyOf(out);
    }

    public long head() {
        return EventBuffer.get().head();
    }

    public long oldestSeq() {
        return EventBuffer.get().oldestSeq();
    }

    public int capacity() {
        return EventBuffer.get().capacity();
    }

    private static Event fromEntry(EventBuffer.Entry entry) {
        return new Event(entry.seq(), entry.ts(), entry.topic(), entry.data());
    }
}

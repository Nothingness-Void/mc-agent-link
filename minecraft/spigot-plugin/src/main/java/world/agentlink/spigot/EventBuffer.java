package world.agentlink.spigot;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Pull-mode event ring buffer with optional WebSocket push subscriptions. */
public final class EventBuffer {
    public record Event(long seq, long ts, String topic, JsonObject data) {}

    private static final int CAPACITY = 4096;
    private static final Gson GSON = new Gson();

    private final AtomicLong sequence = new AtomicLong();
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private final Set<ClientSession> sessions = ConcurrentHashMap.newKeySet();

    public synchronized Event publish(String topic, JsonObject data) {
        Event event = new Event(sequence.incrementAndGet(), System.currentTimeMillis(), topic,
                data == null ? new JsonObject() : data.deepCopy());
        if (events.size() >= CAPACITY) events.removeFirst();
        events.addLast(event);
        push(event);
        return event;
    }

    public synchronized JsonObject read(long sinceSeq, int limit, List<String> topics) {
        int boundedLimit = Math.max(1, Math.min(500, limit));
        Set<String> filter = topics == null ? Set.of() : topics.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::toLowerCase)
                .collect(java.util.stream.Collectors.toSet());
        long oldest = events.isEmpty() ? sequence.get() + 1 : events.getFirst().seq();
        JsonArray array = new JsonArray();
        for (Event event : events) {
            if (event.seq() <= sinceSeq) continue;
            if (!filter.isEmpty() && !filter.contains(event.topic().toLowerCase())) continue;
            JsonObject object = new JsonObject();
            object.addProperty("seq", event.seq());
            object.addProperty("ts", event.ts());
            object.addProperty("topic", event.topic());
            object.add("data", event.data().deepCopy());
            array.add(object);
            if (array.size() >= boundedLimit) break;
        }
        JsonObject result = new JsonObject();
        result.add("events", array);
        result.addProperty("returned", array.size());
        result.addProperty("head_seq", sequence.get());
        result.addProperty("oldest_seq", oldest);
        result.addProperty("buffer_capacity", CAPACITY);
        result.addProperty("truncated", sinceSeq + 1 < oldest);
        return result;
    }

    public void subscribe(ClientSession session, List<String> topics) {
        if (session == null) return;
        session.subscriptions().clear();
        if (topics != null) {
            topics.stream().filter(value -> value != null && !value.isBlank())
                    .map(String::toLowerCase).forEach(session.subscriptions()::add);
        }
        sessions.add(session);
    }

    public void unsubscribe(ClientSession session, List<String> topics) {
        if (session == null) return;
        if (topics == null || topics.isEmpty()) {
            session.subscriptions().clear();
            sessions.remove(session);
            return;
        }
        topics.stream().filter(value -> value != null).map(String::toLowerCase)
                .forEach(session.subscriptions()::remove);
        if (session.subscriptions().isEmpty()) sessions.remove(session);
    }

    public void remove(ClientSession session) {
        if (session != null) sessions.remove(session);
    }

    private void push(Event event) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("v", 0);
        envelope.addProperty("type", "event");
        envelope.addProperty("topic", event.topic());
        envelope.add("data", event.data().deepCopy());
        envelope.addProperty("ts", event.ts());
        String json = GSON.toJson(envelope);
        for (ClientSession session : sessions) {
            if (session.subscriptions().contains(event.topic().toLowerCase())) session.send(json);
        }
    }
}

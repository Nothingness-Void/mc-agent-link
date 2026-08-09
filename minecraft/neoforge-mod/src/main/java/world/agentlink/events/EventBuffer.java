package world.agentlink.events;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ring buffer of recent events with a monotonic sequence number.
 *
 * <p>Designed for pull-mode: agents call {@code get_recent_events(since_seq, limit, topics)}
 * to retrieve only what they need. This means the LLM pays tokens only for events the
 * agent explicitly asks for, instead of every event the server emits.
 *
 * <p>Push subscriptions remain available for advanced use cases (an agent that wants to
 * react to chat in real time), but they are off by default — see {@link ForgeEventBridge}
 * for the dual write path.
 */
public final class EventBuffer {

    public record Entry(long seq, long ts, String topic, JsonObject data) {}

    /**
     * 0.5.0 raised this from 1024 because the topic set roughly quadrupled (commands, container
     * access, explosions, hurt events). A busy server was wrapping the old buffer in well under a
     * minute, which defeats the "ask what happened a few minutes ago" use case the pull model exists
     * for. Entries are small JSON objects, so 4096 is a few MB at worst.
     */
    private static final EventBuffer INSTANCE = new EventBuffer(4096);

    public static EventBuffer get() { return INSTANCE; }

    private final int capacity;
    private final Entry[] ring;
    private final AtomicLong nextSeq = new AtomicLong(1);

    private EventBuffer(int capacity) {
        this.capacity = capacity;
        this.ring = new Entry[capacity];
    }

    /** Append synchronously. Cheap (O(1)). Safe to call from server thread. */
    public synchronized Entry append(String topic, JsonObject data) {
        long seq = nextSeq.getAndIncrement();
        Entry e = new Entry(seq, System.currentTimeMillis(), topic, data);
        ring[(int) ((seq - 1) % capacity)] = e;
        return e;
    }

    public long head() {
        return nextSeq.get() - 1;
    }

    /** Oldest seq still retained (or 1 if buffer hasn't wrapped yet). */
    public long oldestSeq() {
        long head = head();
        return Math.max(1, head - capacity + 1);
    }

    /**
     * Read entries with seq strictly greater than {@code sinceSeq}, optionally filtered
     * by topic. Returned in seq order. {@code limit} caps the number returned.
     */
    public synchronized List<Entry> since(long sinceSeq, int limit, java.util.Set<String> topicFilter) {
        long head = head();
        long start = Math.max(sinceSeq + 1, oldestSeq());
        List<Entry> out = new ArrayList<>(Math.min(limit, (int) Math.max(0, head - start + 1)));
        for (long s = start; s <= head && out.size() < limit; s++) {
            Entry e = ring[(int) ((s - 1) % capacity)];
            if (e == null) continue;
            if (topicFilter != null && !topicFilter.isEmpty() && !topicFilter.contains(e.topic())) continue;
            out.add(e);
        }
        return out;
    }

    public int capacity() {
        return capacity;
    }
}

package world.agentlink.logs;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Ring buffer of recent server log lines, parallel to {@code EventBuffer}. */
public final class LogBuffer {

    public record Entry(long seq, long ts, String level, String logger, String message) {}

    private static final LogBuffer INSTANCE = new LogBuffer(2048);

    public static LogBuffer get() { return INSTANCE; }

    private final int capacity;
    private final Entry[] ring;
    private final AtomicLong nextSeq = new AtomicLong(1);

    private LogBuffer(int capacity) {
        this.capacity = capacity;
        this.ring = new Entry[capacity];
    }

    public synchronized Entry append(String level, String logger, String message) {
        long seq = nextSeq.getAndIncrement();
        Entry e = new Entry(seq, System.currentTimeMillis(), level, logger, message);
        ring[(int) ((seq - 1) % capacity)] = e;
        return e;
    }

    public long head() {
        return nextSeq.get() - 1;
    }

    public long oldestSeq() {
        long head = head();
        return Math.max(1, head - capacity + 1);
    }

    public synchronized List<Entry> since(long sinceSeq, int limit, Set<String> levelFilter, String contains) {
        long head = head();
        long start = Math.max(sinceSeq + 1, oldestSeq());
        List<Entry> out = new ArrayList<>();
        for (long s = start; s <= head && out.size() < limit; s++) {
            Entry e = ring[(int) ((s - 1) % capacity)];
            if (e == null) continue;
            if (levelFilter != null && !levelFilter.isEmpty() && !levelFilter.contains(e.level())) continue;
            if (contains != null && !contains.isEmpty() && !e.message().contains(contains)) continue;
            out.add(e);
        }
        return out;
    }

    public int capacity() {
        return capacity;
    }
}

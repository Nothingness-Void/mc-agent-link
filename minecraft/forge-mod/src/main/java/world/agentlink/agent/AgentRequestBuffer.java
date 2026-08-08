package world.agentlink.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import world.agentlink.i18n.AgentLinkLang;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public final class AgentRequestBuffer {

    /** Raised instead of evicting a pending/working request when the bounded ring is saturated. */
    public static final class QueueFullException extends IllegalStateException {
        public QueueFullException(int capacity) {
            super("request queue is full (" + capacity + " non-terminal entries)");
        }
    }

    public enum Status {
        PENDING,
        WORKING,
        DONE,
        FAILED,
        CANCELED;

        public boolean terminal() {
            return this == DONE || this == FAILED || this == CANCELED;
        }
    }

    public enum MutationOutcome {
        UPDATED,
        NOT_FOUND,
        ALREADY_TERMINAL,
        LEASED,
        INVALID_TRANSITION
    }

    public record Entry(
            long seq,
            String id,
            long createdAt,
            long updatedAt,
            String source,
            String playerName,
            UUID playerUuid,
            String message,
            Status status,
            String statusMessage,
            String reply
    ) {}

    public record QueueInfo(
            String id,
            Status status,
            int running,
            int pending,
            int ahead
    ) {}

    /** Opaque consumer lease. The generation prevents an old worker from reusing a later claim. */
    public record Lease(Entry entry, String owner, long generation) {}

    public record Mutation(Entry entry, MutationOutcome outcome) {}

    private static final AgentRequestBuffer INSTANCE = new AgentRequestBuffer(128);
    private static final int MAX_MESSAGE_CHARS = 500;
    private static final int MAX_STATUS_CHARS = 240;
    private static final int MAX_REPLY_CHARS = 1500;
    private static final int CHAT_SEGMENT_CHARS = 240;
    private static final int MAX_CHAT_SEGMENTS = 6;

    public static AgentRequestBuffer get() { return INSTANCE; }

    private final int capacity;
    private final Entry[] ring;
    private final Map<String, Lease> leases = new HashMap<>();
    private final AtomicLong nextSeq = new AtomicLong(1);
    private long nextLeaseGeneration = 1;
    private long lastAgentSeenAt;
    private String lastAgentAction = "";

    private AgentRequestBuffer(int capacity) {
        this.capacity = capacity;
        this.ring = new Entry[capacity];
    }

    public synchronized Entry createFromPlayer(ServerPlayer player, String message) {
        return createFromPlayer(player, "player", message);
    }

    public synchronized Entry createFromPlayer(ServerPlayer player, String source, String message) {
        return create(source, player.getGameProfile().getName(), player.getUUID(), message);
    }

    /**
     * Create a request from a non-Minecraft integration without exposing a player object.
     * Public addon code should use {@code AgentLinkApi.requests()} instead of this internal class.
     */
    public synchronized Entry create(String source, String playerName, UUID playerUuid, String message) {
        long next = nextSeq.get();
        int slot = (int) ((next - 1) % capacity);
        Entry replaced = ring[slot];
        if (replaced != null && !replaced.status().terminal()) {
            throw new QueueFullException(capacity);
        }
        String trimmed = trim(message, MAX_MESSAGE_CHARS);
        long seq = nextSeq.getAndIncrement();
        long now = System.currentTimeMillis();
        Entry e = new Entry(
                seq,
                "agent-" + seq,
                now,
                now,
                trim(source == null || source.isBlank() ? "player" : source, 32),
                trim(playerName == null || playerName.isBlank() ? "unknown" : playerName, 64),
                playerUuid,
                trimmed,
                Status.PENDING,
                "",
                ""
        );
        if (replaced != null) leases.remove(replaced.id());
        ring[slot] = e;
        return e;
    }

    public long head() {
        return nextSeq.get() - 1;
    }

    public long oldestSeq() {
        long head = head();
        return Math.max(1, head - capacity + 1);
    }

    public synchronized List<Entry> since(long sinceSeq, int limit, boolean includeDone) {
        long head = head();
        long start = Math.max(sinceSeq + 1, oldestSeq());
        List<Entry> out = new ArrayList<>();
        for (long s = start; s <= head && out.size() < limit; s++) {
            Entry e = ring[(int) ((s - 1) % capacity)];
            if (e == null) continue;
            if (!includeDone && (e.status() == Status.DONE || e.status() == Status.FAILED || e.status() == Status.CANCELED)) continue;
            out.add(e);
        }
        return out;
    }

    public synchronized Entry updateStatus(String id, Status status, String statusMessage) {
        return updateStatusDetailed(id, status, statusMessage).entry();
    }

    public synchronized Mutation updateStatusDetailed(String id, Status status, String statusMessage) {
        Entry e = findById(id);
        if (e == null) return new Mutation(null, MutationOutcome.NOT_FOUND);
        if (e.status().terminal()) return new Mutation(e, MutationOutcome.ALREADY_TERMINAL);
        if (status == null || !canTransition(e.status(), status)) {
            return new Mutation(e, MutationOutcome.INVALID_TRANSITION);
        }
        // Once a worker owns a request, an unqualified consumer may observe it but cannot rewrite it.
        if (leases.containsKey(id)) return new Mutation(e, MutationOutcome.LEASED);
        return new Mutation(updateStatusInternal(e, status, statusMessage), MutationOutcome.UPDATED);
    }

    private Entry updateStatusInternal(Entry e, Status status, String statusMessage) {
        Entry updated = new Entry(
                e.seq(),
                e.id(),
                e.createdAt(),
                System.currentTimeMillis(),
                e.source(),
                e.playerName(),
                e.playerUuid(),
                e.message(),
                status,
                trim(statusMessage == null ? "" : statusMessage, MAX_STATUS_CHARS),
                e.reply()
        );
        ring[(int) ((updated.seq() - 1) % capacity)] = updated;
        if (status.terminal()) leases.remove(updated.id());
        return updated;
    }

    /** Atomically move a pending request to WORKING for one named consumer. */
    public synchronized Entry claim(String id, String workerName) {
        String worker = trim(workerName == null || workerName.isBlank() ? "worker" : workerName, 64);
        return claim(id, workerName, "claimed by " + worker);
    }

    /** Atomically claim a request while allowing the consumer to provide its user-facing status. */
    public synchronized Entry claim(String id, String workerName, String statusMessage) {
        Entry e = findById(id);
        if (e == null || e.status() != Status.PENDING) return null;
        return updateStatusInternal(e, Status.WORKING, statusMessage);
    }

    /** Atomically claim a pending request and return the lease required for owned writes. */
    public synchronized Lease claimLease(String id, String workerName, String statusMessage) {
        Entry e = findById(id);
        if (e == null || e.status() != Status.PENDING) return null;
        String owner = trim(workerName == null || workerName.isBlank() ? "worker" : workerName, 64);
        Entry updated = updateStatusInternal(e, Status.WORKING, statusMessage);
        Lease lease = new Lease(updated, owner, nextLeaseGeneration++);
        leases.put(id, lease);
        return lease;
    }

    public synchronized Entry cancel(String id, String message) {
        Entry e = findById(id);
        if (e == null) return null;
        if (e.status() == Status.DONE || e.status() == Status.FAILED || e.status() == Status.CANCELED) return e;
        return updateStatusInternal(e, Status.CANCELED,
                message == null || message.isBlank() ? AgentLinkLang.tr("agentlink.request.canceled_by_operator") : message);
    }

    public synchronized Entry reply(String id, String reply, boolean markDone) {
        return replyDetailed(id, reply, markDone).entry();
    }

    public synchronized Mutation replyDetailed(String id, String reply, boolean markDone) {
        Entry e = findById(id);
        if (e == null) return new Mutation(null, MutationOutcome.NOT_FOUND);
        // A late worker may finish after an operator canceled its request. Never let that worker
        // resurrect the record or replace the cancellation state with a stale answer.
        if (e.status().terminal()) return new Mutation(e, MutationOutcome.ALREADY_TERMINAL);
        if (leases.containsKey(id)) return new Mutation(e, MutationOutcome.LEASED);
        return new Mutation(replyInternal(e, reply, markDone), MutationOutcome.UPDATED);
    }

    private Entry replyInternal(Entry e, String reply, boolean markDone) {
        Entry updated = new Entry(
                e.seq(),
                e.id(),
                e.createdAt(),
                System.currentTimeMillis(),
                e.source(),
                e.playerName(),
                e.playerUuid(),
                e.message(),
                markDone ? Status.DONE : e.status(),
                markDone ? "" : e.statusMessage(),
                trim(reply == null ? "" : reply, MAX_REPLY_CHARS)
        );
        ring[(int) ((updated.seq() - 1) % capacity)] = updated;
        if (markDone) leases.remove(updated.id());
        return updated;
    }

    /** Update a request only when the supplied owner and generation still hold its lease. */
    public synchronized Entry updateStatusLease(String id, String owner, long generation,
                                                 Status status, String statusMessage) {
        Entry e = findById(id);
        Lease lease = leases.get(id);
        if (e == null) return null;
        if (e.status().terminal()) return e;
        if (!matches(lease, owner, generation) || status == null || !canTransition(e.status(), status)) {
            return null;
        }
        return updateStatusInternal(e, status, statusMessage);
    }

    /** Complete a request only when the supplied owner and generation still hold its lease. */
    public synchronized Entry replyLease(String id, String owner, long generation,
                                          String reply, boolean markDone) {
        Entry e = findById(id);
        Lease lease = leases.get(id);
        if (e == null) return null;
        if (e.status().terminal()) return e;
        if (!matches(lease, owner, generation)) return null;
        return replyInternal(e, reply, markDone);
    }

    /** Complete a leased request as FAILED while preserving the diagnostic reply. */
    public synchronized Entry failLease(String id, String owner, long generation,
                                         String reply, String statusMessage) {
        Entry e = findById(id);
        Lease lease = leases.get(id);
        if (e == null) return null;
        if (e.status().terminal()) return e;
        if (!matches(lease, owner, generation)) return null;
        Entry withReply = replyInternal(e, reply, false);
        return updateStatusInternal(withReply, Status.FAILED, statusMessage);
    }

    private static boolean matches(Lease lease, String owner, long generation) {
        return lease != null
                && lease.generation() == generation
                && lease.owner().equals(owner == null ? "" : owner);
    }

    private static boolean canTransition(Status from, Status to) {
        if (from == null || to == null) return false;
        if (from == to) return !from.equals(Status.DONE)
                && !from.equals(Status.FAILED)
                && !from.equals(Status.CANCELED);
        if (from == Status.PENDING) return to == Status.WORKING || to == Status.CANCELED;
        if (from == Status.WORKING) return to == Status.DONE
                || to == Status.FAILED || to == Status.CANCELED;
        return false;
    }

    public synchronized Entry findById(String id) {
        if (id == null || id.isBlank()) return null;
        long head = head();
        long start = oldestSeq();
        for (long s = head; s >= start; s--) {
            Entry e = ring[(int) ((s - 1) % capacity)];
            if (e != null && e.id().equals(id)) return e;
        }
        return null;
    }

    public synchronized List<Entry> recent(int limit, boolean includeDone) {
        long head = head();
        long start = oldestSeq();
        List<Entry> out = new ArrayList<>();
        for (long s = head; s >= start && out.size() < limit; s--) {
            Entry e = ring[(int) ((s - 1) % capacity)];
            if (e == null) continue;
            if (!includeDone && (e.status() == Status.DONE || e.status() == Status.FAILED || e.status() == Status.CANCELED)) continue;
            out.add(e);
        }
        return out;
    }

    public synchronized int count(Status status) {
        long head = head();
        long start = oldestSeq();
        int total = 0;
        for (long s = start; s <= head; s++) {
            Entry e = ring[(int) ((s - 1) % capacity)];
            if (e != null && e.status() == status) total++;
        }
        return total;
    }

    public synchronized QueueInfo queueInfo(String id) {
        Entry target = findById(id);
        if (target == null) return null;
        long head = head();
        long start = oldestSeq();
        int running = 0;
        int pending = 0;
        int ahead = 0;
        for (long s = start; s <= head; s++) {
            Entry e = ring[(int) ((s - 1) % capacity)];
            if (e == null) continue;
            if (e.status() == Status.WORKING) {
                running++;
                if (s < target.seq()) ahead++;
            } else if (e.status() == Status.PENDING) {
                pending++;
                if (s < target.seq()) ahead++;
            }
        }
        return new QueueInfo(target.id(), target.status(), running, pending, ahead);
    }

    public synchronized void markAgentSeen(String action) {
        lastAgentSeenAt = System.currentTimeMillis();
        lastAgentAction = trim(action == null ? "" : action, MAX_STATUS_CHARS);
    }

    public synchronized long lastAgentSeenAt() {
        return lastAgentSeenAt;
    }

    public synchronized String lastAgentAction() {
        return lastAgentAction;
    }

    public JsonObject toJson(Entry e) {
        JsonObject o = new JsonObject();
        o.addProperty("seq", e.seq());
        o.addProperty("id", e.id());
        o.addProperty("created_at", e.createdAt());
        o.addProperty("updated_at", e.updatedAt());
        o.addProperty("source", e.source());
        o.addProperty("player", e.playerName());
        o.addProperty("player_uuid", e.playerUuid() == null ? "" : e.playerUuid().toString());
        o.addProperty("message", e.message());
        o.addProperty("status", e.status().name().toLowerCase(Locale.ROOT));
        o.addProperty("status_message", e.statusMessage());
        o.addProperty("reply", e.reply());
        return o;
    }

    public JsonArray toJsonArray(List<Entry> entries) {
        JsonArray arr = new JsonArray();
        for (Entry e : entries) arr.add(toJson(e));
        return arr;
    }

    public int capacity() {
        return capacity;
    }

    public static Status parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return Status.WORKING;
        return Status.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }

    public static void sendStatusToPlayer(MinecraftServer mc, Entry entry) {
        if (entry.playerUuid() == null) return;
        ServerPlayer player = mc.getPlayerList().getPlayer(entry.playerUuid());
        if (player == null) return;
        player.sendSystemMessage(Component.literal("[Agent] " + entry.statusMessage()).withStyle(ChatFormatting.AQUA));
    }

    public static int sendReplyToPlayer(MinecraftServer mc, Entry entry) {
        if (entry.playerUuid() == null) return 0;
        ServerPlayer player = mc.getPlayerList().getPlayer(entry.playerUuid());
        if (player == null) return 0;
        String reply = entry.reply();
        if (reply == null || reply.isBlank()) return 0;
        player.sendSystemMessage(Component.literal(AgentLinkLang.tr(player, "agentlink.agent.reply_header", entry.id())).withStyle(ChatFormatting.AQUA));
        List<String> parts = splitForChat(reply);
        for (String part : parts) {
            player.sendSystemMessage(Component.literal(part).withStyle(ChatFormatting.GRAY));
        }
        return parts.size();
    }

    private static List<String> splitForChat(String text) {
        List<String> parts = new ArrayList<>();
        String remaining = text.trim();
        while (!remaining.isEmpty() && parts.size() < MAX_CHAT_SEGMENTS) {
            int end = Math.min(CHAT_SEGMENT_CHARS, remaining.length());
            int newline = remaining.lastIndexOf('\n', end - 1);
            if (newline > 40) end = newline + 1;
            parts.add(remaining.substring(0, end).trim());
            remaining = remaining.substring(end).trim();
        }
        if (!remaining.isEmpty() && !parts.isEmpty()) {
            int last = parts.size() - 1;
            parts.set(last, parts.get(last) + " ...");
        }
        return parts;
    }

    private static String trim(String value, int max) {
        String v = value == null ? "" : value.trim();
        if (v.length() <= max) return v;
        return v.substring(0, max);
    }
}

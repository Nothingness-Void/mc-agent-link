package world.agentlink.api;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import world.agentlink.agent.AgentRequestBuffer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Stable facade for the shared in-game request queue.
 *
 * <p>Addons should depend on this class instead of {@code AgentRequestBuffer}. The implementation
 * remains replaceable while request records and queue semantics stay source-compatible.</p>
 */
public final class AgentRequestApi {

    public enum Status {
        PENDING,
        WORKING,
        DONE,
        FAILED,
        CANCELED;

        public boolean terminal() {
            return this == DONE || this == FAILED || this == CANCELED;
        }

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public record Request(
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
    ) {
        public boolean terminal() {
            return status != null && status.terminal();
        }
    }

    public record QueueInfo(
            String id,
            Status status,
            int running,
            int pending,
            int ahead
    ) {}

    public record QueueSummary(int running, int pending) {}

    /** A request plus the opaque lease required to update it after claiming. */
    public record Claim(Request request, String owner, long generation) {}

    public enum MutationOutcome {
        UPDATED,
        NOT_FOUND,
        ALREADY_TERMINAL,
        LEASED,
        INVALID_TRANSITION
    }

    public record Mutation(Request request, MutationOutcome outcome) {}

    private final AgentRequestBuffer buffer;

    AgentRequestApi() {
        this(AgentRequestBuffer.get());
    }

    AgentRequestApi(AgentRequestBuffer buffer) {
        this.buffer = buffer;
    }

    /** Create a new request without exposing a Minecraft player object to addon code. */
    public Request submit(String source, String playerName, UUID playerUuid, String message) {
        try {
            return fromEntry(buffer.create(source, playerName, playerUuid, message));
        } catch (AgentRequestBuffer.QueueFullException full) {
            return null;
        }
    }

    public Request submit(String playerName, UUID playerUuid, String message) {
        return submit("player", playerName, playerUuid, message);
    }

    public Request find(String id) {
        return fromEntry(buffer.findById(id));
    }

    public List<Request> since(long sinceSeq, int limit, boolean includeDone) {
        int safeLimit = limit <= 0 ? 100 : Math.min(limit, 1000);
        List<Request> out = new ArrayList<>();
        for (AgentRequestBuffer.Entry entry : buffer.since(sinceSeq, safeLimit, includeDone)) {
            out.add(fromEntry(entry));
        }
        return List.copyOf(out);
    }

    public List<Request> recent(int limit, boolean includeDone) {
        int safeLimit = limit <= 0 ? 100 : Math.min(limit, 1000);
        List<Request> out = new ArrayList<>();
        for (AgentRequestBuffer.Entry entry : buffer.recent(safeLimit, includeDone)) {
            out.add(fromEntry(entry));
        }
        return List.copyOf(out);
    }

    public Request updateStatus(String id, Status status, String statusMessage) {
        if (status == null) return null;
        return fromEntry(buffer.updateStatus(id, toInternal(status), statusMessage));
    }

    /** Detailed unowned mutation result for tools that need to distinguish a lease conflict. */
    public Mutation updateStatusDetailed(String id, Status status, String statusMessage) {
        if (status == null) return new Mutation(find(id), MutationOutcome.INVALID_TRANSITION);
        AgentRequestBuffer.Mutation mutation = buffer.updateStatusDetailed(
                id, toInternal(status), statusMessage);
        return new Mutation(fromEntry(mutation.entry()), fromInternal(mutation.outcome()));
    }

    /** Atomically claim a pending request for one addon worker. */
    public Request claim(String id, String workerName) {
        return fromEntry(buffer.claim(id, workerName));
    }

    /** Atomically claim a request with a localized or otherwise custom status message. */
    public Request claim(String id, String workerName, String statusMessage) {
        return fromEntry(buffer.claim(id, workerName, statusMessage));
    }

    /** Atomically claim a request and return the ownership token for the consumer. */
    public Claim claimOwned(String id, String workerName, String statusMessage) {
        AgentRequestBuffer.Lease lease = buffer.claimLease(id, workerName, statusMessage);
        return lease == null ? null
                : new Claim(fromEntry(lease.entry()), lease.owner(), lease.generation());
    }

    /** Update a request through its claim lease. Returns null when the lease is stale or foreign. */
    public Request updateStatusOwned(Claim claim, Status status, String statusMessage) {
        if (claim == null || claim.request() == null || status == null) return null;
        return fromEntry(buffer.updateStatusLease(claim.request().id(), claim.owner(), claim.generation(),
                toInternal(status), statusMessage));
    }

    public Request reply(String id, String reply) {
        return reply(id, reply, true);
    }

    public Request reply(String id, String reply, boolean markDone) {
        return fromEntry(buffer.reply(id, reply, markDone));
    }

    /** Detailed unowned reply result for tools that need to distinguish a lease conflict. */
    public Mutation replyDetailed(String id, String reply, boolean markDone) {
        AgentRequestBuffer.Mutation mutation = buffer.replyDetailed(id, reply, markDone);
        return new Mutation(fromEntry(mutation.entry()), fromInternal(mutation.outcome()));
    }

    /** Complete a request through its claim lease. A canceled request cannot be resurrected. */
    public Request replyOwned(Claim claim, String reply, boolean markDone) {
        if (claim == null || claim.request() == null) return null;
        return fromEntry(buffer.replyLease(claim.request().id(), claim.owner(), claim.generation(),
                reply, markDone));
    }

    /** Complete a request as FAILED through its claim lease, preserving the diagnostic reply. */
    public Request failOwned(Claim claim, String reply, String statusMessage) {
        if (claim == null || claim.request() == null) return null;
        return fromEntry(buffer.failLease(claim.request().id(), claim.owner(), claim.generation(),
                reply, statusMessage));
    }

    public Request cancel(String id, String reason) {
        return fromEntry(buffer.cancel(id, reason));
    }

    public int count(Status status) {
        return status == null ? 0 : buffer.count(toInternal(status));
    }

    public QueueInfo queueInfo(String id) {
        AgentRequestBuffer.QueueInfo info = buffer.queueInfo(id);
        if (info == null) return null;
        return new QueueInfo(info.id(), fromInternal(info.status()), info.running(), info.pending(), info.ahead());
    }

    public QueueSummary summary() {
        return new QueueSummary(count(Status.WORKING), count(Status.PENDING));
    }

    public long head() {
        return buffer.head();
    }

    public long oldestSeq() {
        return buffer.oldestSeq();
    }

    public int capacity() {
        return buffer.capacity();
    }

    public void markAgentSeen(String action) {
        buffer.markAgentSeen(action);
    }

    public long lastAgentSeenAt() {
        return buffer.lastAgentSeenAt();
    }

    public String lastAgentAction() {
        return buffer.lastAgentAction();
    }

    /** Send the standard localized status message for a request owner, if they are online. */
    public void sendStatusToPlayer(MinecraftServer server, Request request) {
        AgentRequestBuffer.Entry entry = currentEntry(request);
        if (entry != null) AgentRequestBuffer.sendStatusToPlayer(server, entry);
    }

    /** Send the standard localized, chat-sized reply for a request owner. */
    public int sendReplyToPlayer(MinecraftServer server, Request request) {
        AgentRequestBuffer.Entry entry = currentEntry(request);
        return entry == null ? 0 : AgentRequestBuffer.sendReplyToPlayer(server, entry);
    }

    /** JSON representation matching the existing request MCP tools. */
    public JsonObject toJson(Request request) {
        if (request == null) return new JsonObject();
        JsonObject o = new JsonObject();
        o.addProperty("seq", request.seq());
        o.addProperty("id", request.id());
        o.addProperty("created_at", request.createdAt());
        o.addProperty("updated_at", request.updatedAt());
        o.addProperty("source", request.source());
        o.addProperty("player", request.playerName());
        o.addProperty("player_uuid", request.playerUuid() == null ? "" : request.playerUuid().toString());
        o.addProperty("message", request.message());
        o.addProperty("status", request.status() == null ? "" : request.status().wire());
        o.addProperty("status_message", request.statusMessage());
        o.addProperty("reply", request.reply());
        return o;
    }

    private AgentRequestBuffer.Entry currentEntry(Request request) {
        return request == null ? null : buffer.findById(request.id());
    }

    private static Request fromEntry(AgentRequestBuffer.Entry entry) {
        if (entry == null) return null;
        return new Request(
                entry.seq(),
                entry.id(),
                entry.createdAt(),
                entry.updatedAt(),
                entry.source(),
                entry.playerName(),
                entry.playerUuid(),
                entry.message(),
                fromInternal(entry.status()),
                entry.statusMessage(),
                entry.reply()
        );
    }

    private static AgentRequestBuffer.Status toInternal(Status status) {
        return AgentRequestBuffer.Status.valueOf(status.name());
    }

    private static Status fromInternal(AgentRequestBuffer.Status status) {
        return status == null ? null : Status.valueOf(status.name());
    }

    private static MutationOutcome fromInternal(AgentRequestBuffer.MutationOutcome outcome) {
        return outcome == null ? null : MutationOutcome.valueOf(outcome.name());
    }
}

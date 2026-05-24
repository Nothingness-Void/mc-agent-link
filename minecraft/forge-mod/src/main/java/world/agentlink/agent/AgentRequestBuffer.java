package world.agentlink.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public final class AgentRequestBuffer {

    public enum Status {
        PENDING,
        WORKING,
        DONE,
        FAILED,
        CANCELED
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

    private static final AgentRequestBuffer INSTANCE = new AgentRequestBuffer(128);
    private static final int MAX_MESSAGE_CHARS = 500;
    private static final int MAX_STATUS_CHARS = 240;
    private static final int MAX_REPLY_CHARS = 1500;
    private static final int CHAT_SEGMENT_CHARS = 240;
    private static final int MAX_CHAT_SEGMENTS = 6;

    public static AgentRequestBuffer get() { return INSTANCE; }

    private final int capacity;
    private final Entry[] ring;
    private final AtomicLong nextSeq = new AtomicLong(1);

    private AgentRequestBuffer(int capacity) {
        this.capacity = capacity;
        this.ring = new Entry[capacity];
    }

    public synchronized Entry createFromPlayer(ServerPlayer player, String message) {
        String trimmed = trim(message, MAX_MESSAGE_CHARS);
        long seq = nextSeq.getAndIncrement();
        long now = System.currentTimeMillis();
        Entry e = new Entry(
                seq,
                "agent-" + seq,
                now,
                now,
                "player",
                player.getGameProfile().getName(),
                player.getUUID(),
                trimmed,
                Status.PENDING,
                "",
                ""
        );
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
        Entry e = findById(id);
        if (e == null) return null;
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
        return updated;
    }

    public synchronized Entry reply(String id, String reply, boolean markDone) {
        Entry e = findById(id);
        if (e == null) return null;
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
                markDone ? "done" : e.statusMessage(),
                trim(reply == null ? "" : reply, MAX_REPLY_CHARS)
        );
        ring[(int) ((updated.seq() - 1) % capacity)] = updated;
        return updated;
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
        player.sendSystemMessage(Component.literal("[Agent] Reply for " + entry.id() + ":").withStyle(ChatFormatting.AQUA));
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

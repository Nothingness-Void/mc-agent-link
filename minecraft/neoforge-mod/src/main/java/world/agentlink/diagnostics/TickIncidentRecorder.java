package world.agentlink.diagnostics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Keeps a bounded, timing-only record of recent slow ticks for post-incident diagnosis.
 *
 * <p>The normal tick path stores only primitive timing state. Slow ticks are coalesced to at most
 * one incident per second, and no stack, JSON, file I/O, or network work happens on the server
 * thread. This is deliberately not a causal profiler; use spark for attribution.</p>
 */
public final class TickIncidentRecorder {

    public static final long SLOW_TICK_THRESHOLD_MS = 50L;
    private static final long INCIDENT_COOLDOWN_MS = 1_000L;
    private static final long RECENT_WINDOW_MS = 10_000L;
    private static final int MAX_INCIDENTS = 32;
    private static final Object LOCK = new Object();
    private static final Incident[] RING = new Incident[MAX_INCIDENTS];

    private static volatile MinecraftServer activeServer;
    private static long tickStartedAtNs;
    private static boolean tickOpen;
    private static long nextSequence = 1L;
    private static int nextSlot;
    private static long lastIncidentAtMs;
    private static long coalescedTicks;
    private static double coalescedPeakMs;

    private TickIncidentRecorder() {}

    /** Called at the start of a server tick on the server thread. */
    public static void onTickStart(MinecraftServer server) {
        if (server == null) return;
        if (activeServer != server) {
            synchronized (LOCK) {
                java.util.Arrays.fill(RING, null);
                nextSlot = 0;
                nextSequence = 1L;
                lastIncidentAtMs = 0L;
                coalescedTicks = 0L;
                coalescedPeakMs = 0.0;
            }
        }
        activeServer = server;
        tickStartedAtNs = System.nanoTime();
        tickOpen = true;
    }

    /** Called at the end of a server tick on the server thread. */
    public static void onTickEnd(MinecraftServer server) {
        if (server == null || server != activeServer || !tickOpen) return;
        long elapsedNs = System.nanoTime() - tickStartedAtNs;
        tickOpen = false;
        double elapsedMs = elapsedNs / 1_000_000.0;
        if (elapsedMs < SLOW_TICK_THRESHOLD_MS) return;

        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            if (lastIncidentAtMs > 0 && now - lastIncidentAtMs < INCIDENT_COOLDOWN_MS) {
                coalescedTicks++;
                coalescedPeakMs = Math.max(coalescedPeakMs, elapsedMs);
                return;
            }

            long samples = coalescedTicks + 1L;
            double peakMs = Math.max(coalescedPeakMs, elapsedMs);
            RING[nextSlot] = new Incident(
                    nextSequence++, now, elapsedMs, peakMs, samples,
                    Thread.currentThread().getName());
            nextSlot = (nextSlot + 1) % MAX_INCIDENTS;
            lastIncidentAtMs = now;
            coalescedTicks = 0L;
            coalescedPeakMs = 0.0;
        }
    }

    /** Clear the active tick boundary and history when the server is stopping. */
    public static void stop(MinecraftServer server) {
        if (server != null && server == activeServer) {
            tickOpen = false;
            activeServer = null;
            synchronized (LOCK) {
                java.util.Arrays.fill(RING, null);
                nextSlot = 0;
                nextSequence = 1L;
                lastIncidentAtMs = 0L;
                coalescedTicks = 0L;
                coalescedPeakMs = 0.0;
            }
        }
    }

    /** Return a bounded, self-describing snapshot suitable for MCP and addon APIs. */
    public static JsonObject snapshot() {
        return snapshot(MAX_INCIDENTS);
    }

    /** Return only the newest {@code limit} records while preserving total-count metadata. */
    public static JsonObject snapshot(int limit) {
        int safeLimit = Math.max(1, Math.min(MAX_INCIDENTS, limit));
        List<Incident> incidents = new ArrayList<>();
        long pendingCoalesced;
        double pendingPeak;
        synchronized (LOCK) {
            for (Incident incident : RING) {
                if (incident != null) incidents.add(incident);
            }
            pendingCoalesced = coalescedTicks;
            pendingPeak = coalescedPeakMs;
        }
        incidents.sort(Comparator.comparingLong(Incident::sequence));
        int totalCount = incidents.size();
        long now = System.currentTimeMillis();
        long recentSamples = 0L;
        long latestAgeMs = -1L;
        for (Incident incident : incidents) {
            long age = Math.max(0L, now - incident.capturedAtMs());
            latestAgeMs = Math.min(latestAgeMs < 0 ? age : latestAgeMs, age);
            if (age <= RECENT_WINDOW_MS) recentSamples += incident.sampleTicks();
        }
        if (incidents.size() > safeLimit) {
            incidents = new ArrayList<>(incidents.subList(incidents.size() - safeLimit, incidents.size()));
        }

        JsonObject result = new JsonObject();
        result.addProperty("threshold_ms", SLOW_TICK_THRESHOLD_MS);
        result.addProperty("cooldown_ms", INCIDENT_COOLDOWN_MS);
        result.addProperty("recent_window_ms", RECENT_WINDOW_MS);
        result.addProperty("count", incidents.size());
        result.addProperty("total_count", totalCount);
        result.addProperty("limit", safeLimit);
        result.addProperty("recent_samples", recentSamples);
        if (latestAgeMs >= 0) result.addProperty("latest_age_ms", latestAgeMs);
        result.addProperty("capacity", MAX_INCIDENTS);
        result.addProperty("attribution", "timing_only; use spark for causal profiling");
        result.addProperty("coalesced_since_last", pendingCoalesced);
        if (pendingCoalesced > 0) result.addProperty("coalesced_peak_ms", round2(pendingPeak));

        JsonArray values = new JsonArray();
        for (Incident incident : incidents) {
            JsonObject value = new JsonObject();
            value.addProperty("sequence", incident.sequence());
            value.addProperty("captured_at", Instant.ofEpochMilli(incident.capturedAtMs()).toString());
            value.addProperty("captured_at_ms", incident.capturedAtMs());
            value.addProperty("duration_ms", round2(incident.durationMs()));
            value.addProperty("peak_ms", round2(incident.peakMs()));
            value.addProperty("sample_ticks", incident.sampleTicks());
            value.addProperty("thread", incident.threadName());
            values.add(value);
        }
        result.add("incidents", values);
        return result;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record Incident(long sequence, long capturedAtMs, double durationMs,
                            double peakMs, long sampleTicks, String threadName) {}
}

package world.agentlink.dispatch.tools;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import world.agentlink.dispatch.Tool;
import world.agentlink.transport.ClientSession;

import java.util.Arrays;

/**
 * Tick-time distribution from the engine's rolling 100-tick window
 * ({@code MinecraftServer.tickTimes}).
 *
 * <p>{@link world.agentlink.dispatch.tools.GetServerStatsTool} returns just the
 * average. This tool exposes the full distribution so an agent can tell whether
 * a 19.5 TPS reading is steady-state or hides a 250 ms spike.
 */
public class TickProfileTool implements Tool {

    private final MinecraftServer mc;

    public TickProfileTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "tick_profile";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) {
        long[] raw = mc.tickTimes;
        long[] sorted = Arrays.copyOf(raw, raw.length);
        int sampleCount = 0;
        for (long t : raw) {
            if (t > 0) sorted[sampleCount++] = t;
        }
        sorted = Arrays.copyOf(sorted, sampleCount);
        Arrays.sort(sorted);

        double avgNanos = 0;
        long maxNanos = 0;
        for (long t : sorted) {
            avgNanos += t;
            if (t > maxNanos) maxNanos = t;
        }
        avgNanos /= Math.max(1, sorted.length);

        double avgMs = avgNanos / 1_000_000.0;
        double tps = Math.min(1000.0 / Math.max(avgMs, 50.0), 20.0);

        JsonObject r = new JsonObject();
        r.addProperty("samples", sorted.length);
        r.addProperty("window_capacity", raw.length);
        r.addProperty("avg_mspt", round2(avgMs));
        r.addProperty("max_mspt", round2(maxNanos / 1_000_000.0));
        r.addProperty("p50_mspt", round2(percentileMs(sorted, 0.50)));
        r.addProperty("p95_mspt", round2(percentileMs(sorted, 0.95)));
        r.addProperty("p99_mspt", round2(percentileMs(sorted, 0.99)));
        r.addProperty("tps", round2(tps));
        r.addProperty("window_ticks", sorted.length);
        return r;
    }

    private static double percentileMs(long[] sortedNanos, double pct) {
        if (sortedNanos.length == 0) return 0;
        int idx = (int) Math.ceil(pct * sortedNanos.length) - 1;
        if (idx < 0) idx = 0;
        if (idx >= sortedNanos.length) idx = sortedNanos.length - 1;
        return sortedNanos[idx] / 1_000_000.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}

package world.agentlink.dispatch.tools;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.spark.SparkBridge;
import world.agentlink.transport.ClientSession;

/**
 * Starts a spark profiler sample. Non-blocking — returns immediately after
 * spark accepts the command. The agent should then either wait for the user
 * to ask for results, or call {@link SparkProfilerStopTool} after letting the
 * sample run for a while (typical: 30-60s).
 *
 * <p>Args (all optional, see https://spark.lucko.me/docs/Command-Usage):
 * <ul>
 *   <li>{@code timeout} — auto-stop after N seconds. Recommended for hands-off operation.</li>
 *   <li>{@code interval_ms} — sample interval. Default spark setting (~4ms) is fine for most uses.</li>
 *   <li>{@code only_ticks_over_ms} — only record ticks longer than this. Useful for spike hunting.</li>
 *   <li>{@code thread_all} — sample every thread, not just the server thread.</li>
 *   <li>{@code alloc} — allocation profile instead of CPU sampling. Heavier.</li>
 * </ul>
 */
public class SparkProfilerStartTool implements Tool {

    private final MinecraftServer mc;

    public SparkProfilerStartTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "spark_profiler_start";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        if (!SparkBridge.isAvailable(mc)) {
            throw new ToolException("SPARK_UNAVAILABLE",
                    "spark mod not installed. Install it from https://spark.lucko.me to use this tool.");
        }

        StringBuilder cmd = new StringBuilder("profiler start");
        if (args.has("timeout")) {
            int t = args.get("timeout").getAsInt();
            if (t <= 0 || t > 3600) {
                throw new ToolException("INVALID_ARGS", "timeout must be 1-3600 seconds");
            }
            cmd.append(" --timeout ").append(t);
        }
        if (args.has("interval_ms")) {
            double i = args.get("interval_ms").getAsDouble();
            if (i <= 0 || i > 1000) throw new ToolException("INVALID_ARGS", "interval_ms must be > 0 and <= 1000");
            cmd.append(" --interval ").append(i);
        }
        if (args.has("only_ticks_over_ms")) {
            int v = args.get("only_ticks_over_ms").getAsInt();
            if (v < 0) throw new ToolException("INVALID_ARGS", "only_ticks_over_ms must be >= 0");
            cmd.append(" --only-ticks-over ").append(v);
        }
        if (args.has("thread_all") && args.get("thread_all").getAsBoolean()) {
            cmd.append(" --thread *");
        }
        if (args.has("alloc") && args.get("alloc").getAsBoolean()) {
            cmd.append(" --alloc");
        }

        SparkBridge.CommandResult cr = SparkBridge.runSpark(mc, cmd.toString(), 0);

        JsonObject r = new JsonObject();
        r.addProperty("started", cr.returnValue() >= 0);
        r.addProperty("command", "/spark " + cmd);
        r.addProperty("output", cr.output());
        return r;
    }
}

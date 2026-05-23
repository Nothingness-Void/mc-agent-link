package world.agentlink.dispatch.tools;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.spark.SparkBridge;
import world.agentlink.transport.ClientSession;

/**
 * Stops the active spark profiler. spark uploads the sample on a background
 * thread; we wait up to {@code wait_url_ms} (default 15s, max 60s) for the
 * resulting viewer URL to land in command output.
 */
public class SparkProfilerStopTool implements Tool {

    private static final long DEFAULT_WAIT = 15_000;
    private static final long MAX_WAIT = 60_000;

    private final MinecraftServer mc;

    public SparkProfilerStopTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "spark_profiler_stop";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        if (!SparkBridge.isAvailable(mc)) {
            throw new ToolException("SPARK_UNAVAILABLE", "spark mod not installed");
        }

        long wait = args.has("wait_url_ms") ? args.get("wait_url_ms").getAsLong() : DEFAULT_WAIT;
        if (wait < 0) wait = 0;
        if (wait > MAX_WAIT) wait = MAX_WAIT;

        StringBuilder cmd = new StringBuilder("profiler stop");
        if (args.has("comment") && !args.get("comment").isJsonNull()) {
            String comment = args.get("comment").getAsString();
            // No spaces allowed in --comment via brigadier; quote it.
            cmd.append(" --comment \"").append(comment.replace('"', '\'')).append('"');
        }
        if (args.has("save_to_file") && args.get("save_to_file").getAsBoolean()) {
            cmd.append(" --save-to-file");
        }

        SparkBridge.CommandResult cr = SparkBridge.runSpark(mc, cmd.toString(), wait);

        JsonObject r = new JsonObject();
        r.addProperty("command", "/spark " + cmd);
        r.addProperty("output", cr.output());
        if (cr.url() != null) r.addProperty("url", cr.url());
        r.addProperty("url_present", cr.url() != null);
        if (cr.url() == null) {
            r.addProperty("hint",
                    "spark may still be uploading; the URL appears asynchronously. " +
                    "Try again with a larger wait_url_ms, or check `/spark activity` via run_console_command.");
        }
        return r;
    }
}

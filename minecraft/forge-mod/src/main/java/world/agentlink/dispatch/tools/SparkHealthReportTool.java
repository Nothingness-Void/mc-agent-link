package world.agentlink.dispatch.tools;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.spark.SparkBridge;
import world.agentlink.transport.ClientSession;

/**
 * /spark health --upload — TPS, CPU, memory, disk in one shareable report.
 * Returns the captured chat output and the viewer URL when available.
 */
public class SparkHealthReportTool implements Tool {

    private static final long DEFAULT_WAIT = 10_000;
    private static final long MAX_WAIT = 60_000;

    private final MinecraftServer mc;

    public SparkHealthReportTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "spark_health_report";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        if (!SparkBridge.isAvailable(mc)) {
            throw new ToolException("SPARK_UNAVAILABLE", "spark mod not installed");
        }

        long wait = args.has("wait_url_ms") ? args.get("wait_url_ms").getAsLong() : DEFAULT_WAIT;
        if (wait < 0) wait = 0;
        if (wait > MAX_WAIT) wait = MAX_WAIT;

        StringBuilder cmd = new StringBuilder("health --upload");
        if (args.has("memory") && args.get("memory").getAsBoolean()) cmd.append(" --memory");
        if (args.has("network") && args.get("network").getAsBoolean()) cmd.append(" --network");

        SparkBridge.CommandResult cr = SparkBridge.runSpark(mc, cmd.toString(), wait);

        JsonObject r = new JsonObject();
        r.addProperty("command", "/spark " + cmd);
        r.addProperty("output", cr.output());
        if (cr.url() != null) r.addProperty("url", cr.url());
        r.addProperty("url_present", cr.url() != null);
        return r;
    }
}

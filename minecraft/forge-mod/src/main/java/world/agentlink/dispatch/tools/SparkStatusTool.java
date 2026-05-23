package world.agentlink.dispatch.tools;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import world.agentlink.dispatch.Tool;
import world.agentlink.spark.SparkBridge;
import world.agentlink.transport.ClientSession;

/**
 * Reports whether spark is installed and what surface area we have access to.
 * Always succeeds — never returns SPARK_NOT_INSTALLED. Other spark_* tools
 * call this implicitly (via SparkBridge.isAvailable) and refuse if false.
 */
public class SparkStatusTool implements Tool {

    private final MinecraftServer mc;

    public SparkStatusTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "spark_status";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) {
        boolean cmdAvailable = SparkBridge.isAvailable(mc);
        boolean apiAvailable = SparkBridge.apiAvailable();

        JsonObject r = new JsonObject();
        r.addProperty("installed", cmdAvailable || apiAvailable);
        r.addProperty("command_available", cmdAvailable);
        r.addProperty("api_available", apiAvailable);

        if (cmdAvailable) {
            // Probe profiler info — surfaces "not running" or current sample state.
            SparkBridge.CommandResult info = SparkBridge.runSpark(mc, "profiler info", 0);
            r.addProperty("profiler_info", info.output());
        }

        if (!cmdAvailable && !apiAvailable) {
            r.addProperty("hint", "Install the spark mod (https://spark.lucko.me) for advanced profiling. Without spark, fall back on tick_profile and thread_dump.");
        }

        return r;
    }
}

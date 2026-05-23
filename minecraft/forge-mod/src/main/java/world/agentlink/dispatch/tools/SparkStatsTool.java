package world.agentlink.dispatch.tools;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.spark.SparkBridge;
import world.agentlink.transport.ClientSession;

/**
 * Wraps the spark Java API. Returns multi-window TPS / MSPT / CPU / GC stats —
 * more accurate than {@link TickProfileTool} (which only sees the engine's
 * 100-tick rolling average). Read-only.
 */
public class SparkStatsTool implements Tool {

    @Override
    public String name() {
        return "spark_stats";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        if (!SparkBridge.apiAvailable()) {
            throw new ToolException("SPARK_UNAVAILABLE",
                    "spark API not loaded — install spark and ensure it initialized at server start");
        }
        return SparkBridge.statsSnapshot();
    }
}

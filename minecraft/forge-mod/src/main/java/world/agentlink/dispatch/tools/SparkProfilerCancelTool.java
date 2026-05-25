package world.agentlink.dispatch.tools;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.i18n.AgentLinkLang;
import world.agentlink.spark.SparkBridge;
import world.agentlink.transport.ClientSession;

/** Cancels the active spark profiler without uploading. Use to abort. */
public class SparkProfilerCancelTool implements Tool {

    private static final long OUTPUT_WAIT_MS = 2_000;

    private final MinecraftServer mc;

    public SparkProfilerCancelTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "spark_profiler_cancel";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        if (!SparkBridge.isAvailable(mc)) {
            throw new ToolException("SPARK_UNAVAILABLE", AgentLinkLang.tr("agentlink.spark.error.not_installed"));
        }
        SparkBridge.CommandResult cr = SparkBridge.runSpark(mc, "profiler cancel", OUTPUT_WAIT_MS);
        JsonObject r = new JsonObject();
        r.addProperty("output", SparkBridge.localizeOutput(cr.output()));
        r.addProperty("cancelled", cr.returnValue() >= 0);
        return r;
    }
}

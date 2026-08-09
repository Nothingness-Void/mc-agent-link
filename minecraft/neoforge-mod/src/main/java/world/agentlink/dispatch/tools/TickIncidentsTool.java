package world.agentlink.dispatch.tools;

import com.google.gson.JsonObject;
import world.agentlink.diagnostics.TickIncidentRecorder;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolArgs;
import world.agentlink.transport.ClientSession;

/** Read-only timing history of recent slow server ticks. */
public final class TickIncidentsTool implements Tool {

    @Override
    public String name() {
        return "tick_incidents";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) {
        int limit = ToolArgs.optIntClamped(args, "limit", 16, 1, 32);
        return TickIncidentRecorder.snapshot(limit);
    }
}

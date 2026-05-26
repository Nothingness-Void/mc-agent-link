package world.agentlink.dispatch.tools;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;
import world.agentlink.we.WorldEditBridge;

/**
 * Pop the most recent N edits off the agent-shared undo stack and reverse each via
 * {@code EditSession.undo(EditSession)}. Tier-4 admin-only.
 *
 * <p>Args: {@code steps?} (default 1, max 10), {@code dim?} (used for the new EditSession context)
 */
public class WeUndoTool implements Tool {

    private static final int MAX_STEPS = 10;

    private final MinecraftServer mc;

    public WeUndoTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "we_undo";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        ServerLevel level = GetBlockTool.resolveDimension(mc, args);
        int steps = 1;
        if (args.has("steps") && !args.get("steps").isJsonNull()) {
            steps = args.get("steps").getAsInt();
        }
        if (steps <= 0 || steps > MAX_STEPS) {
            throw new ToolException("INVALID_ARGS", "steps must be in [1, " + MAX_STEPS + "]");
        }
        WorldEditBridge.UndoResult res = WorldEditBridge.performUndo(level, steps);
        JsonObject r = new JsonObject();
        r.addProperty("undone", res.undone());
        r.addProperty("changed", res.changed());
        r.addProperty("remaining_undo_depth", res.remainingDepth());
        return r;
    }
}

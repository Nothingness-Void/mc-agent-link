package world.agentlink.dispatch.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolArgs;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;
import world.agentlink.world.BlockWriter;

import java.time.Instant;
import java.util.List;

/**
 * Reverse the most recent native block writes.
 *
 * <p>Counterpart to {@code we_undo}, for the {@link BlockWriter} stack rather than WorldEdit's. The
 * two stacks are separate on purpose: they hold different kinds of record (ours is an explicit
 * prior-state snapshot, WE's is an {@code EditSession}), and merging them would make "undo the last
 * thing" ambiguous about which subsystem it belonged to.
 *
 * <p>{@code mode:"list"} inspects the stack without touching the world — useful before undoing, so
 * the agent can confirm it's about to reverse what it thinks it is.
 */
public class UndoBlocksTool implements Tool {

    private static final int MAX_STEPS = 32;

    private final MinecraftServer mc;

    public UndoBlocksTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "undo_blocks";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        String mode = ToolArgs.optEnum(args, "mode", "undo");
        if ("list".equals(mode)) return listStack();
        if (!"undo".equals(mode)) {
            throw new ToolException("INVALID_ARGS", "mode must be \"undo\" or \"list\"");
        }

        String operationId = ToolArgs.optString(args, "operation_id", "").trim();
        if (!operationId.isEmpty()) {
            BlockWriter.OperationUndoResult result = BlockWriter.undoOperation(operationId);
            JsonObject r = new JsonObject();
            r.addProperty("operation_id", result.operationId());
            r.addProperty("label", result.label());
            r.addProperty("blocks_restored", result.blocksRestored());
            r.addProperty("remaining_depth", result.remainingDepth());
            return r;
        }

        int steps = ToolArgs.optIntClamped(args, "steps", 1, 1, MAX_STEPS);
        if (BlockWriter.undoDepth() == 0) {
            throw new ToolException("NOTHING_TO_UNDO",
                    "The native block-write undo stack is empty. Note it does not survive a server"
                            + " restart, and WorldEdit edits live on a separate stack (use we_undo).");
        }

        BlockWriter.UndoResult result = BlockWriter.undo(steps);

        JsonObject r = new JsonObject();
        r.addProperty("requested_steps", steps);
        r.addProperty("operations_undone", result.operationsUndone());
        r.addProperty("blocks_restored", result.blocksRestored());
        r.addProperty("remaining_depth", result.remainingDepth());
        JsonArray labels = new JsonArray();
        for (String l : result.labels()) labels.add(l);
        r.add("undone", labels);
        return r;
    }

    private JsonObject listStack() {
        List<BlockWriter.Change> changes = BlockWriter.peekAll();
        JsonArray arr = new JsonArray();
        int i = 0;
        for (BlockWriter.Change c : changes) {
            JsonObject o = new JsonObject();
            o.addProperty("index", i++);
            o.addProperty("operation_id", c.operationId());
            o.addProperty("label", c.label());
            o.addProperty("dim", c.dimension());
            o.addProperty("blocks_recorded", c.size());
            o.addProperty("created_at_ms", c.createdAtMs());
            o.addProperty("created_at_iso", Instant.ofEpochMilli(c.createdAtMs()).toString());
            if (c.truncated()) {
                o.addProperty("truncated", true);
                o.addProperty("note", "operation exceeded the undo capture cap; only the first "
                        + BlockWriter.UNDO_CAPTURE_MAX + " blocks can be restored");
            }
            arr.add(o);
        }
        JsonObject r = new JsonObject();
        r.addProperty("depth", changes.size());
        r.add("stack", arr);
        r.addProperty("note", "index 0 is the most recent; use operation_id for an exact newest edit");
        return r;
    }
}

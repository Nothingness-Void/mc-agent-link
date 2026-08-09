package world.agentlink.dispatch.tools;

import com.google.gson.JsonObject;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolArgs;
import world.agentlink.dispatch.ToolException;
import world.agentlink.task.TaskManager;
import world.agentlink.transport.ClientSession;

/**
 * Ask a running task to stop.
 *
 * <p>Cooperative: a sliceable task stops at its next slice boundary (well under a second), and
 * whatever it already wrote stays written. That's deliberate — a half-applied edit is recoverable
 * via {@code undo_blocks}, whereas killing a thread mid-write could leave a chunk inconsistent.
 * Non-sliceable tasks can't be interrupted at all; the flag is recorded and their result is
 * discarded when it lands.
 */
public class CancelTaskTool implements Tool {

    @Override
    public String name() {
        return "cancel_task";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        TaskManager tasks = TaskManager.current();
        if (tasks == null) throw new ToolException("UNAVAILABLE", "The task system is not running");

        String id = ToolArgs.requireString(args, "task_id");
        TaskManager.TaskRecord record = tasks.get(id);
        if (record == null) throw new ToolException("NOT_FOUND", "No task " + id);

        if (record.status().terminal()) {
            JsonObject r = record.toJson(false);
            r.addProperty("cancelled", false);
            r.addProperty("note", "Task already finished as " + record.status().wire()
                    + "; nothing to cancel.");
            return r;
        }

        boolean ok = tasks.cancel(id);
        JsonObject r = record.toJson(false);
        r.addProperty("cancelled", ok);
        r.addProperty("note", "Cancellation requested. The task stops at its next slice boundary;"
                + " blocks already written remain in the world — use undo_blocks to reverse them."
                + " Poll get_task to confirm it reached the cancelled state.");
        return r;
    }
}

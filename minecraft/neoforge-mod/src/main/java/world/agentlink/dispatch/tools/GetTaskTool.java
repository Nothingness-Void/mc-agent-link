package world.agentlink.dispatch.tools;

import com.google.gson.JsonObject;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolArgs;
import world.agentlink.dispatch.ToolException;
import world.agentlink.task.TaskManager;
import world.agentlink.transport.ClientSession;

/**
 * Poll one async task.
 *
 * <p>{@code wait_ms} lets the agent block briefly instead of spinning: for a task that finishes in
 * two seconds, one call with {@code wait_ms:3000} beats six polls at 500 ms — fewer round trips and
 * fewer tokens. It returns as soon as the task reaches a terminal state, or when the wait expires,
 * whichever comes first. Capped so a stuck task can't hold an HTTP connection open indefinitely.
 */
public class GetTaskTool implements Tool {

    private static final long MAX_WAIT_MS = 30_000;
    private static final long POLL_INTERVAL_MS = 100;

    @Override
    public String name() {
        return "get_task";
    }

    /** Sleeps while waiting; must not be on the server thread. */
    @Override
    public boolean offThread() {
        return true;
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        TaskManager tasks = TaskManager.current();
        if (tasks == null) throw new ToolException("UNAVAILABLE", "The task system is not running");

        String id = ToolArgs.requireString(args, "task_id");
        TaskManager.TaskRecord record = tasks.get(id);
        if (record == null) {
            throw new ToolException("NOT_FOUND",
                    "No task " + id + " in memory. Completed tasks age out of the in-memory list but"
                            + " their JSON records stay under config/agent-link/tasks/;"
                            + " call list_tasks for what is still tracked.");
        }

        long waitMs = Math.min(MAX_WAIT_MS, Math.max(0, ToolArgs.optLong(args, "wait_ms", 0)));
        if (waitMs > 0 && !record.status().terminal()) {
            long deadline = System.currentTimeMillis() + waitMs;
            while (System.currentTimeMillis() < deadline && !record.status().terminal()) {
                try {
                    Thread.sleep(POLL_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        JsonObject r = record.toJson(true);
        r.addProperty("terminal", record.status().terminal());
        if (!record.status().terminal()) {
            r.addProperty("hint", "Still running. Poll again, optionally with wait_ms up to "
                    + MAX_WAIT_MS + " to block server-side instead of spinning.");
        }
        return r;
    }
}

package world.agentlink.dispatch.tools;

import com.google.gson.JsonObject;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolArgs;
import world.agentlink.dispatch.ToolException;
import world.agentlink.task.TaskManager;
import world.agentlink.transport.ClientSession;

import java.util.List;
import java.util.Locale;

/**
 * List tracked async tasks, newest first.
 *
 * <p>Results are omitted from the listing on purpose — a finished 500k-block fill's report is large,
 * and repeating it for every row would make this call cost more than the work it describes. Fetch
 * the one you care about with {@code get_task}.
 */
public class ListTasksTool implements Tool {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 200;

    @Override
    public String name() {
        return "list_tasks";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        TaskManager tasks = TaskManager.current();
        if (tasks == null) throw new ToolException("UNAVAILABLE", "The task system is not running");

        String statusRaw = ToolArgs.optString(args, "status", null);
        TaskManager.Status filter = null;
        if (statusRaw != null && !statusRaw.isBlank()) {
            try {
                filter = TaskManager.Status.valueOf(statusRaw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw new ToolException("INVALID_ARGS",
                        "status must be one of pending, running, succeeded, failed, cancelled");
            }
        }
        int limit = ToolArgs.optIntClamped(args, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);

        List<TaskManager.TaskRecord> records = tasks.list(filter, limit);
        JsonObject r = new JsonObject();
        r.add("tasks", StartTaskTool.toArray(records, false));
        r.addProperty("returned", records.size());
        r.addProperty("running", tasks.runningCount());
        if (filter != null) r.addProperty("status_filter", filter.wire());
        r.addProperty("note", "Results are omitted here to keep the listing small — call"
                + " get_task {task_id} for a task's full result.");
        return r;
    }
}

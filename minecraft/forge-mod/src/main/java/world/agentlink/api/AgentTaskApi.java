package world.agentlink.api;

import com.google.gson.JsonObject;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.task.TaskManager;
import world.agentlink.transport.ClientSession;

import java.util.ArrayList;
import java.util.List;

/** Stable query and cancellation API for tasks created through {@code start_task}. */
public final class AgentTaskApi {

    public enum Status {
        PENDING,
        RUNNING,
        SUCCEEDED,
        FAILED,
        CANCELLED;

        public boolean terminal() {
            return this == SUCCEEDED || this == FAILED || this == CANCELLED;
        }
    }

    public record TaskInfo(
            String id,
            String tool,
            Status status,
            long createdAtMs,
            long startedAtMs,
            long finishedAtMs,
            long durationMs,
            long runningForMs,
            boolean cancelRequested,
            JsonObject progress,
            JsonObject partial,
            JsonObject result,
            String errorCode,
            String errorMessage
    ) {
        public TaskInfo {
            progress = copy(progress);
            partial = copy(partial);
            result = copy(result);
            errorCode = errorCode == null ? "" : errorCode;
            errorMessage = errorMessage == null ? "" : errorMessage;
        }

        private static JsonObject copy(JsonObject value) {
            return value == null ? null : value.deepCopy();
        }
    }

    /** Immediate result returned when an addon submits a tool to the shared task pool. */
    public record Submission(String id, String tool, Status status, boolean sliceable,
                             long estimatedUnits) {}

    AgentTaskApi() {}

    /**
     * Submit an already-approved addon operation to the shared task pool. Addon tools should call
     * this from their quick synchronous entry point and implement {@code TaskContext.Sliceable} for
     * work that must yield between server-thread slices.
     */
    public Submission submit(Tool tool, JsonObject args, ClientSession session)
            throws AgentApiException {
        if (tool == null) throw new AgentApiException("INVALID_ARGS", "tool is required");
        TaskManager manager = TaskManager.current();
        if (manager == null) throw new AgentApiException("UNAVAILABLE", "the task system is not running");
        try {
            TaskManager.TaskRecord record = manager.submit(tool, args, session);
            boolean sliceable = tool instanceof world.agentlink.task.TaskContext.Sliceable;
            long estimated = sliceable
                    ? ((world.agentlink.task.TaskContext.Sliceable) tool).estimateUnits(
                            args == null ? new JsonObject() : args)
                    : -1;
            return new Submission(record.id(), tool.name(), Status.valueOf(record.status().name()),
                    sliceable, estimated);
        } catch (ToolException ex) {
            throw new AgentApiException(ex.code(), ex.getMessage(), ex);
        }
    }

    public boolean available() {
        return TaskManager.current() != null;
    }

    public TaskInfo get(String id) {
        TaskManager manager = TaskManager.current();
        return manager == null ? null : fromRecord(manager.get(id));
    }

    public List<TaskInfo> list(Status filter, int limit) {
        TaskManager manager = TaskManager.current();
        if (manager == null) return List.of();
        TaskManager.Status internalFilter = filter == null ? null : TaskManager.Status.valueOf(filter.name());
        int safeLimit = limit <= 0 ? 100 : Math.min(limit, 200);
        List<TaskInfo> out = new ArrayList<>();
        for (TaskManager.TaskRecord record : manager.list(internalFilter, safeLimit)) {
            TaskInfo info = fromRecord(record);
            if (info != null) out.add(info);
        }
        return List.copyOf(out);
    }

    public int runningCount() {
        TaskManager manager = TaskManager.current();
        return manager == null ? 0 : manager.runningCount();
    }

    public boolean cancel(String id) {
        TaskManager manager = TaskManager.current();
        return manager != null && manager.cancel(id);
    }

    private static TaskInfo fromRecord(TaskManager.TaskRecord record) {
        if (record == null) return null;
        JsonObject json = record.toJson(true);
        JsonObject progress = object(json, "progress");
        JsonObject error = object(json, "error");
        return new TaskInfo(
                string(json, "task_id"),
                string(json, "tool"),
                parseStatus(string(json, "status")),
                number(json, "created_at_ms", -1),
                number(json, "started_at_ms", -1),
                number(json, "finished_at_ms", -1),
                number(json, "duration_ms", -1),
                number(json, "running_for_ms", -1),
                bool(json, "cancel_requested"),
                progress,
                object(json, "partial"),
                object(json, "result"),
                string(error, "code"),
                string(error, "message")
        );
    }

    private static Status parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Status.valueOf(raw.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static JsonObject object(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonObject()) return null;
        return parent.getAsJsonObject(key);
    }

    private static String string(JsonObject object, String key) {
        return object == null || !object.has(key) ? "" : object.get(key).getAsString();
    }

    private static long number(JsonObject object, String key, long fallback) {
        try {
            return object == null || !object.has(key) ? fallback : object.get(key).getAsLong();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static boolean bool(JsonObject object, String key) {
        try {
            return object != null && object.has(key) && object.get(key).getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}

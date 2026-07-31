package world.agentlink.dispatch.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import world.agentlink.approval.AgentToolApproval;
import world.agentlink.dispatch.RequestDispatcher;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolArgs;
import world.agentlink.dispatch.ToolException;
import world.agentlink.task.TaskContext;
import world.agentlink.task.TaskManager;
import world.agentlink.transport.ClientSession;

/**
 * Run another tool asynchronously and return a task id immediately.
 *
 * <h2>The problem</h2>
 * Every layer between the agent and the server has a timeout — the MCP host, the HTTP client, an
 * in-game bridge. A three-minute build trips one of them, and when the client gives up the work
 * keeps running with nobody reading the result. That is how a long edit turns into a lost session.
 *
 * <p>{@code start_task} inverts the flow: submit, get an id in milliseconds, poll {@code get_task}
 * on your own schedule. For tools that implement {@link TaskContext.Sliceable} the work is also
 * spread across ticks, so a 500k-block fill no longer freezes the server for its duration.
 *
 * <h2>Approval is not laundered</h2>
 * The wrapped tool goes through the ordinary approval pipeline <em>before</em> it is queued: we ask
 * {@link AgentToolApproval#request} for the inner tool name and args, and refuse to submit if the
 * answer is no. Otherwise {@code start_task} would be a universal bypass — wrap
 * {@code run_console_command} and skip the prompt. Because approval happens up front, the returned
 * task id means "approved and queued", and a denial surfaces synchronously where the agent can
 * react to it.
 */
public class StartTaskTool implements Tool {

    /** Tools that must never be wrapped — nothing is gained and the semantics get confusing. */
    private static final java.util.Set<String> NOT_TASKABLE = java.util.Set.of(
            "start_task", "get_task", "cancel_task", "list_tasks",
            // Session-scoped and instantaneous; wrapping them is meaningless.
            "subscribe_events", "unsubscribe_events", "ping", "agent_heartbeat");

    private final MinecraftServer mc;

    public StartTaskTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "start_task";
    }

    /** Waits on the approval future, so it must not sit on the server thread. */
    @Override
    public boolean offThread() {
        return true;
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        TaskManager tasks = TaskManager.current();
        if (tasks == null) {
            throw new ToolException("UNAVAILABLE", "The task system is not running");
        }
        String toolName = ToolArgs.requireString(args, "tool").trim();
        if (NOT_TASKABLE.contains(toolName.toLowerCase(java.util.Locale.ROOT))) {
            throw new ToolException("INVALID_ARGS",
                    "Tool \"" + toolName + "\" cannot be wrapped in a task — call it directly");
        }
        JsonObject inner = args.has("args") && args.get("args").isJsonObject()
                ? args.getAsJsonObject("args").deepCopy()
                : new JsonObject();

        RequestDispatcher dispatcher = RequestDispatcher.current();
        if (dispatcher == null) throw new ToolException("UNAVAILABLE", "Dispatcher is not running");
        Tool target = dispatcher.tool(toolName);
        if (target == null) {
            throw new ToolException("UNKNOWN_TOOL",
                    "No such tool: " + toolName + " — check tools/list for exact names");
        }

        // Give the inner tool a chance to declare its footprint, then run the real approval check.
        // Doing this here rather than inside the task means a denial is reported synchronously.
        try {
            target.declareScope(inner);
        } catch (Throwable ignored) {
        }
        AgentToolApproval approval = AgentToolApproval.current();
        AgentToolApproval.Decision decision = null;
        if (approval != null) {
            try {
                decision = approval.request(toolName, inner, session).get();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new ToolException("INTERNAL_ERROR", "Interrupted while awaiting approval");
            } catch (Exception e) {
                throw new ToolException("INTERNAL_ERROR", "Approval failed: " + e.getMessage());
            }
            if (!decision.approved()) {
                throw new ToolException("APPROVAL_DENIED", decision.reason());
            }
        }

        TaskManager.TaskRecord record = tasks.submit(target, inner, session);

        JsonObject r = new JsonObject();
        r.addProperty("task_id", record.id());
        r.addProperty("tool", toolName);
        r.addProperty("status", record.status().wire());
        r.addProperty("sliceable", target instanceof TaskContext.Sliceable);
        if (target instanceof TaskContext.Sliceable s) {
            long est = s.estimateUnits(inner);
            if (est > 0) r.addProperty("estimated_units", est);
        } else {
            r.addProperty("note", "This tool is not sliceable: it runs as one server-thread unit."
                    + " Your MCP call returns immediately, but the tick loop still blocks for the"
                    + " duration of the operation.");
        }
        if (decision != null && decision.outcome() != null) {
            r.addProperty("approval_outcome", decision.outcome().name().toLowerCase(java.util.Locale.ROOT));
        }
        r.addProperty("poll_with", "get_task {task_id:\"" + record.id() + "\"}");
        return r;
    }

    /** Shared listing helper used by {@code list_tasks}. */
    static JsonArray toArray(java.util.List<TaskManager.TaskRecord> records, boolean includeResult) {
        JsonArray arr = new JsonArray();
        for (TaskManager.TaskRecord r : records) arr.add(r.toJson(includeResult));
        return arr;
    }
}

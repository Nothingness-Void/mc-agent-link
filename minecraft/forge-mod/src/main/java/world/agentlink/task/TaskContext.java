package world.agentlink.task;

import com.google.gson.JsonObject;
import world.agentlink.dispatch.ToolException;

/**
 * Handle a long-running tool receives when it is executed under {@code start_task}.
 *
 * <p>Two responsibilities:
 * <ul>
 *   <li><b>Progress</b> — {@link #progress} publishes a fraction and a human sentence that
 *       {@code get_task} returns. Without this an agent polling a 3-minute edit has no way to
 *       distinguish "working" from "wedged".</li>
 *   <li><b>Cancellation</b> — {@link #checkCancelled} throws when the operator or the agent asked
 *       to stop. Cooperative rather than {@code Thread.interrupt}, because a half-interrupted
 *       world write is worse than one that finishes its current slice.</li>
 * </ul>
 *
 * <p>A tool that never sees a TaskContext (the ordinary synchronous path) behaves exactly as
 * before — {@link Sliceable} is the only opt-in.
 */
public final class TaskContext {

    private final TaskManager.TaskRecord record;

    TaskContext(TaskManager.TaskRecord record) {
        this.record = record;
    }

    public String taskId() {
        return record.id();
    }

    /**
     * Publish progress. {@code done} / {@code total} may both be 0 when the total isn't known yet;
     * in that case only {@code message} is reported.
     */
    public void progress(long done, long total, String message) {
        record.updateProgress(done, total, message);
    }

    public void progress(String message) {
        record.updateProgress(-1, -1, message);
    }

    /** True once cancellation has been requested. Prefer {@link #checkCancelled} in loops. */
    public boolean isCancelled() {
        return record.cancelRequested();
    }

    /**
     * Throw {@code TASK_CANCELLED} if cancellation was requested. Call this between slices — the
     * task is marked cancelled and whatever was already written stays written (an undo entry
     * exists for block edits, so the operator can reverse it deliberately).
     */
    public void checkCancelled() throws ToolException {
        if (record.cancelRequested()) {
            throw new ToolException("TASK_CANCELLED", "Task " + record.id() + " was cancelled");
        }
    }

    /** Attach structured partial output, visible via {@code get_task} while still running. */
    public void partial(JsonObject partial) {
        record.updatePartial(partial);
    }

    /**
     * A tool that can run under {@code start_task} with progress and cancellation.
     *
     * <p>Implementations are invoked <em>off</em> the server thread by the task executor, so they
     * must hop on-thread for world access — typically per slice via
     * {@link world.agentlink.dispatch.ServerThread#call}. That is what keeps a 500k-block fill from
     * blocking the tick loop for its whole duration.
     *
     * <p>Tools that don't implement this still work with {@code start_task}; they just run as one
     * indivisible unit with no progress reporting.
     */
    public interface Sliceable {
        JsonObject invokeSliced(JsonObject args, TaskContext ctx) throws ToolException;

        /**
         * Rough unit count for the args, used to pre-populate progress totals and to decide whether
         * a synchronous call should be refused with "use start_task". Return -1 when unknown.
         */
        default long estimateUnits(JsonObject args) {
            return -1;
        }
    }
}

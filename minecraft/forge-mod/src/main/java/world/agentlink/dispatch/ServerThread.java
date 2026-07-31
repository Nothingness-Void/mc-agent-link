package world.agentlink.dispatch;

import net.minecraft.server.MinecraftServer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Bridge for code running off the server thread that needs to touch world state.
 *
 * <p>Most tools are invoked by {@link RequestDispatcher} on the server thread already and don't
 * need this. Two callers do:
 * <ul>
 *   <li>tools that declare {@link Tool#offThread()} — they run on a worker so a slow tool can't
 *       stall ticks, then hop back here for the parts that must be on-thread;</li>
 *   <li>the async task executor ({@code start_task}), which slices a long edit across many ticks.</li>
 * </ul>
 *
 * <p>Every method rejects rather than deadlocks when called from the server thread itself with a
 * blocking wait — running {@code call} on-thread executes the body inline instead of scheduling it,
 * so a helper that's shared between on- and off-thread paths stays correct either way.
 */
public final class ServerThread {

    /** Default ceiling for a single hop. Generous: a tick backlog under load can be seconds. */
    public static final long DEFAULT_TIMEOUT_MS = 30_000L;

    private ServerThread() {}

    @FunctionalInterface
    public interface Body<T> {
        T run() throws ToolException;
    }

    @FunctionalInterface
    public interface VoidBody {
        void run() throws ToolException;
    }

    public static boolean isServerThread(MinecraftServer mc) {
        return mc != null && mc.isSameThread();
    }

    /**
     * Run {@code body} on the server thread and wait for its result.
     *
     * <p>Inline when already on the server thread. {@link ToolException} thrown inside propagates
     * unchanged; anything else becomes {@code INTERNAL_ERROR}. A timeout becomes
     * {@code SERVER_BUSY} — the caller should surface that rather than retrying blindly, since a
     * server that missed a 30 s window is in trouble on its own.
     */
    public static <T> T call(MinecraftServer mc, Body<T> body) throws ToolException {
        return call(mc, DEFAULT_TIMEOUT_MS, body);
    }

    public static <T> T call(MinecraftServer mc, long timeoutMs, Body<T> body) throws ToolException {
        if (mc == null) throw new ToolException("INTERNAL_ERROR", "Server is not available");
        if (mc.isSameThread()) return body.run();

        CompletableFuture<T> fut = new CompletableFuture<>();
        try {
            mc.execute(() -> {
                try {
                    fut.complete(body.run());
                } catch (Throwable t) {
                    fut.completeExceptionally(t);
                }
            });
        } catch (Throwable t) {
            throw new ToolException("INTERNAL_ERROR", "Cannot schedule onto the server thread: " + t);
        }

        try {
            return fut.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            throw new ToolException("SERVER_BUSY",
                    "Server thread did not run the request within " + timeoutMs + "ms");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ToolException("INTERNAL_ERROR", "Interrupted while waiting for the server thread");
        } catch (ExecutionException ee) {
            throw unwrap(ee.getCause());
        }
    }

    public static void run(MinecraftServer mc, VoidBody body) throws ToolException {
        call(mc, DEFAULT_TIMEOUT_MS, () -> {
            body.run();
            return null;
        });
    }

    public static void run(MinecraftServer mc, long timeoutMs, VoidBody body) throws ToolException {
        call(mc, timeoutMs, () -> {
            body.run();
            return null;
        });
    }

    /** Fire-and-forget schedule. Exceptions are swallowed into the log by the caller's try/catch. */
    public static void submit(MinecraftServer mc, Runnable body) {
        if (mc == null) return;
        if (mc.isSameThread()) {
            body.run();
            return;
        }
        mc.execute(body);
    }

    static ToolException unwrap(Throwable cause) {
        if (cause instanceof ToolException te) return te;
        if (cause == null) return new ToolException("INTERNAL_ERROR", "Unknown failure on the server thread");
        String msg = cause.getMessage();
        return new ToolException("INTERNAL_ERROR",
                cause.getClass().getSimpleName() + (msg == null ? "" : ": " + msg));
    }
}

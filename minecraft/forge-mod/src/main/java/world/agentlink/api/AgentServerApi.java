package world.agentlink.api;

import net.minecraft.server.MinecraftServer;
import world.agentlink.AgentLinkMod;
import world.agentlink.dispatch.ServerThread;
import world.agentlink.dispatch.ToolException;

/** Main-thread and server-lifecycle helpers for addon code. */
public final class AgentServerApi {

    @FunctionalInterface
    public interface Operation<T> {
        T run() throws Exception;
    }

    @FunctionalInterface
    public interface VoidOperation {
        void run() throws Exception;
    }

    AgentServerApi() {}

    public MinecraftServer current() {
        return AgentLinkMod.currentServer();
    }

    public boolean isRunning() {
        return current() != null;
    }

    public boolean isServerThread(MinecraftServer server) {
        return ServerThread.isServerThread(server);
    }

    public <T> T call(MinecraftServer server, Operation<T> operation) throws AgentApiException {
        return call(server, ServerThread.DEFAULT_TIMEOUT_MS, operation);
    }

    public <T> T call(MinecraftServer server, long timeoutMs, Operation<T> operation)
            throws AgentApiException {
        if (operation == null) throw new AgentApiException("INVALID_ARGS", "operation is required");
        try {
            return ServerThread.call(server, timeoutMs, () -> {
                try {
                    return operation.run();
                } catch (ToolException te) {
                    throw te;
                } catch (Throwable t) {
                    throw new ToolException("INTERNAL_ERROR", message(t));
                }
            });
        } catch (ToolException te) {
            throw new AgentApiException(te.code(), te.getMessage(), te);
        }
    }

    public void run(MinecraftServer server, VoidOperation operation) throws AgentApiException {
        run(server, ServerThread.DEFAULT_TIMEOUT_MS, operation);
    }

    public void run(MinecraftServer server, long timeoutMs, VoidOperation operation)
            throws AgentApiException {
        call(server, timeoutMs, () -> {
            operation.run();
            return null;
        });
    }

    public void submit(MinecraftServer server, Runnable operation) {
        ServerThread.submit(server, operation);
    }

    private static String message(Throwable throwable) {
        String detail = throwable == null ? "" : throwable.getMessage();
        return throwable == null ? "Unknown failure" : throwable.getClass().getSimpleName()
                + (detail == null || detail.isBlank() ? "" : ": " + detail);
    }
}

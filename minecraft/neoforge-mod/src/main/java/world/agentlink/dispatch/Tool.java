package world.agentlink.dispatch;

import com.google.gson.JsonObject;
import world.agentlink.transport.ClientSession;

public interface Tool {
    String name();

    /**
     * Called on the main server thread unless {@link #offThread()} is true. Return the JSON
     * `result` payload, or throw {@link ToolException} for a structured error.
     */
    JsonObject invoke(JsonObject args, ClientSession session) throws ToolException;

    /**
     * When true, the dispatcher runs this tool on a worker thread instead of the server thread.
     *
     * <p>Opt in only for tools that either don't touch world state at all (file I/O, JVM
     * introspection) or that hop back on-thread themselves via
     * {@link ServerThread#call}. Anything reading blocks or entities directly must stay
     * on-thread — the default — because {@code ServerLevel} is not thread-safe.
     *
     * <p>The motivation is tick time: a tool that blocks for seconds (a large snapshot write, a
     * multi-tick region edit) would otherwise freeze the server for its whole duration.
     */
    default boolean offThread() {
        return false;
    }

    /**
     * Declare the region this call will affect, before the approval decision is made.
     *
     * <p>Called by the dispatcher on the transport thread, ahead of
     * {@code AgentToolApproval.request}, so implementations must not touch world state — parse the
     * coordinate args and call {@link world.agentlink.sandbox.BuildZones#declareScope} only.
     *
     * <p>Purpose: the approval layer can then exempt the call when its whole footprint sits inside
     * an operator-declared build zone. Tools that don't override this are never exempted, which is
     * the safe default — an undeclared footprint is treated as unbounded.
     *
     * <p>Malformed args should be swallowed here; the tool body will produce the real validation
     * error when it runs.
     */
    default void declareScope(JsonObject args) {
    }

    /**
     * Human-readable description shown to MCP hosts. Built-in tools return ""
     * and rely on agent-link/mcp-tools.json instead; addon tools must override.
     */
    default String description() {
        return "";
    }

    /**
     * JSON Schema for the tool's input arguments. Returning null means
     * "no schema published" — the MCP host may still call with any arguments.
     * Built-in tools return null and rely on agent-link/mcp-tools.json.
     */
    default JsonObject inputSchema() {
        return null;
    }
}

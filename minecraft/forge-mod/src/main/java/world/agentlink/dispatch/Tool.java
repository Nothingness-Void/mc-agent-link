package world.agentlink.dispatch;

import com.google.gson.JsonObject;
import world.agentlink.transport.ClientSession;

public interface Tool {
    String name();

    /**
     * Called on the main server thread. Return the JSON `result` payload, or
     * throw {@link ToolException} for a structured error.
     */
    JsonObject invoke(JsonObject args, ClientSession session) throws ToolException;

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

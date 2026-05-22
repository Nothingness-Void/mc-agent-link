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
}

package world.agentlink.api;

import com.google.gson.JsonObject;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

/**
 * Convenience base class for addon tools. Override {@link #invoke(JsonObject)} for the common
 * case where the WebSocket-style {@link ClientSession} isn't needed; if you need session state
 * (subscriptions, peer info), override {@link #invoke(JsonObject, ClientSession)} instead.
 */
public abstract class BaseAddonTool implements Tool {

    private final String name;
    private final String description;
    private final JsonObject inputSchema;

    protected BaseAddonTool(String name, String description, JsonObject inputSchema) {
        this.name = name;
        this.description = description == null ? "" : description;
        this.inputSchema = inputSchema;
    }

    @Override
    public final String name() {
        return name;
    }

    @Override
    public final String description() {
        return description;
    }

    @Override
    public final JsonObject inputSchema() {
        return inputSchema;
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        return invoke(args);
    }

    /** Override this when your tool doesn't care about the (possibly null) {@link ClientSession}. */
    protected JsonObject invoke(JsonObject args) throws ToolException {
        throw new ToolException("NOT_IMPLEMENTED", "BaseAddonTool subclass must override invoke()");
    }
}

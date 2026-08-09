package world.agentlink.spigot;

import com.google.gson.JsonObject;

/** A transport-neutral Agent Link tool implemented against the Bukkit API. */
public interface Tool {
    String name();

    JsonObject invoke(JsonObject args, ClientSession session) throws ToolException;

    default String description() {
        return "";
    }

    default JsonObject inputSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    /** Mutating tools require guest-token approval unless explicitly trusted. */
    default boolean mutating() {
        return false;
    }

    /** Long-running tools may use the worker pool. Bukkit calls still hop to the main thread. */
    default boolean offThread() {
        return false;
    }
}

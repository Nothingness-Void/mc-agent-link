package world.agentlink.spigot;

import com.google.gson.JsonObject;

/** Small adapter used for tools whose behavior fits a single handler. */
public final class SimpleTool implements Tool {
    @FunctionalInterface
    public interface Handler {
        JsonObject apply(JsonObject args, ClientSession session) throws ToolException;
    }

    private final String name;
    private final String description;
    private final JsonObject schema;
    private final boolean mutating;
    private final boolean offThread;
    private final Handler handler;

    public SimpleTool(String name, String description, JsonObject schema, boolean mutating,
                      boolean offThread, Handler handler) {
        this.name = name;
        this.description = description;
        this.schema = schema;
        this.mutating = mutating;
        this.offThread = offThread;
        this.handler = handler;
    }

    @Override public String name() { return name; }
    @Override public String description() { return description; }
    @Override public JsonObject inputSchema() { return schema.deepCopy(); }
    @Override public boolean mutating() { return mutating; }
    @Override public boolean offThread() { return offThread; }
    @Override public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        return handler.apply(args, session);
    }
}

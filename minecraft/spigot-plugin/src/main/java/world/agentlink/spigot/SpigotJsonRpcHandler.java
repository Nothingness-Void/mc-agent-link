package world.agentlink.spigot;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.concurrent.CompletableFuture;

/** Minimal MCP Streamable HTTP JSON-RPC handler shared by the Spigot HTTP endpoint. */
public final class SpigotJsonRpcHandler {
    public static final String PROTOCOL_2025_06 = "2025-06-18";
    public static final String PROTOCOL_2025_03 = "2025-03-26";

    private static final Gson GSON = new Gson();
    private static final String INSTRUCTIONS = "You are connected to a modern Spigot/Paper server through agent-link. "
            + "This plugin exposes MCP and WebSocket connectivity only; it does not implement the in-game "
            + "agent request, queue, GUI, or steer system. It targets the Bukkit 1.20+ API without NMS. "
            + "Call whoami or get_server_capabilities before version-sensitive work. "
            + "Guest-token writes may require an online OP approval.";

    private final SpigotDispatcher dispatcher;
    private final String version;

    public SpigotJsonRpcHandler(SpigotDispatcher dispatcher, String version) {
        this.dispatcher = dispatcher;
        this.version = version;
    }

    public CompletableFuture<JsonObject> handle(JsonObject request, CallTier tier, ClientSession session) {
        JsonElement id = request.get("id");
        if (id == null || id.isJsonNull()) return CompletableFuture.completedFuture(null);
        String method = request.has("method") ? request.get("method").getAsString() : "";
        JsonObject params = request.has("params") && request.get("params").isJsonObject()
                ? request.getAsJsonObject("params") : new JsonObject();
        return switch (method) {
            case "initialize" -> CompletableFuture.completedFuture(ok(id, initialize(params)));
            case "tools/list" -> CompletableFuture.completedFuture(ok(id, listTools()));
            case "tools/call" -> callTool(params, tier, session).thenApply(result -> ok(id, result));
            case "ping" -> CompletableFuture.completedFuture(ok(id, new JsonObject()));
            default -> CompletableFuture.completedFuture(error(id, -32601, "Method not found: " + method));
        };
    }

    private JsonObject initialize(JsonObject params) {
        String requested = params.has("protocolVersion") ? params.get("protocolVersion").getAsString() : PROTOCOL_2025_06;
        String agreed = PROTOCOL_2025_03.equals(requested) ? PROTOCOL_2025_03 : PROTOCOL_2025_06;
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", agreed);
        result.add("capabilities", new JsonObject());
        result.getAsJsonObject("capabilities").add("tools", new JsonObject());
        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", "agent-link-spigot");
        serverInfo.addProperty("version", version);
        result.add("serverInfo", serverInfo);
        result.addProperty("instructions", INSTRUCTIONS);
        return result;
    }

    private JsonObject listTools() {
        JsonArray tools = new JsonArray();
        for (Tool tool : dispatcher.tools()) {
            JsonObject spec = new JsonObject();
            spec.addProperty("name", tool.name());
            spec.addProperty("description", tool.description());
            spec.add("inputSchema", tool.inputSchema());
            tools.add(spec);
        }
        JsonObject result = new JsonObject();
        result.add("tools", tools);
        return result;
    }

    private CompletableFuture<JsonObject> callTool(JsonObject params, CallTier tier,
                                                   ClientSession session) {
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        String name = params.has("name") ? params.get("name").getAsString() : "";
        JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments") : new JsonObject();
        if (name.isBlank()) {
            result.complete(toolError("Missing tool name"));
            return result;
        }
        dispatcher.invoke(name, args, session, tier, (value, error) -> {
            if (error != null) result.complete(toolError("[" + error.code() + "] " + error.getMessage()));
            else {
                JsonObject response = new JsonObject();
                JsonArray content = new JsonArray();
                JsonObject text = new JsonObject();
                text.addProperty("type", "text");
                text.addProperty("text", GSON.toJson(value == null ? new JsonObject() : value));
                content.add(text);
                response.add("content", content);
                response.addProperty("isError", false);
                result.complete(response);
            }
        });
        return result;
    }

    private static JsonObject toolError(String message) {
        JsonObject response = new JsonObject();
        JsonArray content = new JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", message == null ? "" : message);
        content.add(text);
        response.add("content", content);
        response.addProperty("isError", true);
        return response;
    }

    private static JsonObject ok(JsonElement id, JsonObject result) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id);
        response.add("result", result);
        return response;
    }

    public static JsonObject error(JsonElement id, int code, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id == null ? com.google.gson.JsonNull.INSTANCE : id);
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        response.add("error", error);
        return response;
    }
}

package world.agentlink.transport.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import world.agentlink.config.AgentLinkConfig;
import world.agentlink.dispatch.RequestDispatcher;

import java.util.concurrent.CompletableFuture;

/**
 * Translates a single JSON-RPC 2.0 request into an MCP response. Stateless — the only ambient state
 * is the dispatcher we delegate {@code tools/call} to.
 *
 * <p>We implement a minimal slice of MCP 2025-06-18: {@code initialize}, {@code tools/list},
 * {@code tools/call}, plus the {@code notifications/initialized} notification. Anything else is
 * answered with method-not-found (-32601). We never initiate server-side messages, so we don't need
 * SSE.
 */
public final class JsonRpcHandler {

    static final String SUPPORTED_PROTOCOL_VERSION = "2025-06-18";
    static final String FALLBACK_PROTOCOL_VERSION = "2025-03-26";

    private static final Gson GSON = new Gson();

    private final RequestDispatcher dispatcher;
    private final String version;

    public JsonRpcHandler(RequestDispatcher dispatcher, String version) {
        this.dispatcher = dispatcher;
        this.version = version;
    }

    /**
     * Handle a single JSON-RPC 2.0 message. Returns a future that completes with the response
     * object, or {@code null} for notifications (no response on the wire).
     */
    public CompletableFuture<JsonObject> handle(JsonObject req) {
        JsonElement idEl = req.get("id");
        boolean isNotification = idEl == null || idEl.isJsonNull();
        String method = req.has("method") ? req.get("method").getAsString() : "";

        if (isNotification) {
            // Spec: notifications must not be answered. We just acknowledge known ones.
            return CompletableFuture.completedFuture(null);
        }

        JsonObject params = req.has("params") && req.get("params").isJsonObject()
                ? req.getAsJsonObject("params")
                : new JsonObject();

        switch (method) {
            case "initialize":
                return CompletableFuture.completedFuture(ok(idEl, handleInitialize(params)));
            case "tools/list":
                return CompletableFuture.completedFuture(ok(idEl, handleListTools()));
            case "tools/call":
                return handleToolsCall(params).thenApply(result -> ok(idEl, result));
            case "ping":
                // Optional MCP convenience — answer with empty object.
                return CompletableFuture.completedFuture(ok(idEl, new JsonObject()));
            default:
                return CompletableFuture.completedFuture(error(idEl, -32601, "Method not found: " + method));
        }
    }

    private JsonObject handleInitialize(JsonObject params) {
        String requested = params.has("protocolVersion") && !params.get("protocolVersion").isJsonNull()
                ? params.get("protocolVersion").getAsString()
                : SUPPORTED_PROTOCOL_VERSION;

        // Either echo our version (newer/equal) or downgrade to a known older one if the client
        // explicitly asked for it.
        String agreed = SUPPORTED_PROTOCOL_VERSION.equals(requested)
                ? SUPPORTED_PROTOCOL_VERSION
                : FALLBACK_PROTOCOL_VERSION.equals(requested)
                ? FALLBACK_PROTOCOL_VERSION
                : SUPPORTED_PROTOCOL_VERSION;

        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", agreed);

        JsonObject capabilities = new JsonObject();
        capabilities.add("tools", new JsonObject());
        result.add("capabilities", capabilities);

        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", "agent-link");
        serverInfo.addProperty("version", version);
        result.add("serverInfo", serverInfo);

        result.addProperty("instructions", McpToolSpecs.instructions());
        return result;
    }

    private JsonObject handleListTools() {
        JsonObject result = new JsonObject();
        result.add("tools", McpToolSpecs.tools());
        return result;
    }

    private CompletableFuture<JsonObject> handleToolsCall(JsonObject params) {
        CompletableFuture<JsonObject> fut = new CompletableFuture<>();

        String name = params.has("name") && !params.get("name").isJsonNull()
                ? params.get("name").getAsString()
                : "";
        if (name.isEmpty()) {
            fut.complete(toolError("Missing tool name"));
            return fut;
        }
        JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments")
                : new JsonObject();

        // Stateless transport: pass null session. Tools that need session state (subscribe_events,
        // unsubscribe_events) will NPE on session.subscriptions(); the catch in invoke() converts
        // that to INTERNAL_ERROR. Pull tools (get_recent_events) work fine.
        dispatcher.invoke(name, args, null, (result, err) -> {
            if (err != null) {
                fut.complete(toolError("[" + err.code() + "] " + err.getMessage()));
            } else {
                JsonObject ok = new JsonObject();
                JsonArray content = new JsonArray();
                JsonObject text = new JsonObject();
                text.addProperty("type", "text");
                text.addProperty("text", GSON.toJson(result == null ? new JsonObject() : result));
                content.add(text);
                ok.add("content", content);
                ok.addProperty("isError", false);
                fut.complete(ok);
            }
        });

        return fut;
    }

    private JsonObject toolError(String message) {
        JsonObject obj = new JsonObject();
        JsonArray content = new JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", message);
        content.add(text);
        obj.add("content", content);
        obj.addProperty("isError", true);
        return obj;
    }

    private static JsonObject ok(JsonElement id, JsonObject result) {
        JsonObject resp = new JsonObject();
        resp.addProperty("jsonrpc", "2.0");
        resp.add("id", id);
        resp.add("result", result);
        return resp;
    }

    static JsonObject error(JsonElement id, int code, String message) {
        JsonObject resp = new JsonObject();
        resp.addProperty("jsonrpc", "2.0");
        resp.add("id", id == null ? com.google.gson.JsonNull.INSTANCE : id);
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message);
        resp.add("error", err);
        return resp;
    }
}

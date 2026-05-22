package world.agentlink.dispatch;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import world.agentlink.AgentLinkMod;
import world.agentlink.dispatch.tools.BroadcastTool;
import world.agentlink.dispatch.tools.GetPlayerInfoTool;
import world.agentlink.dispatch.tools.GetServerStatsTool;
import world.agentlink.dispatch.tools.ListOnlinePlayersTool;
import world.agentlink.dispatch.tools.PingTool;
import world.agentlink.dispatch.tools.RunConsoleCommandTool;
import world.agentlink.dispatch.tools.SubscribeEventsTool;
import world.agentlink.dispatch.tools.UnsubscribeEventsTool;
import world.agentlink.transport.ClientSession;

import java.util.HashMap;
import java.util.Map;

public class RequestDispatcher {

    private static final Gson GSON = new Gson();
    private final MinecraftServer mc;
    private final Map<String, Tool> tools = new HashMap<>();

    public RequestDispatcher(MinecraftServer mc) {
        this.mc = mc;
        register(new PingTool(mc));
        register(new RunConsoleCommandTool(mc));
        register(new ListOnlinePlayersTool(mc));
        register(new GetPlayerInfoTool(mc));
        register(new BroadcastTool(mc));
        register(new GetServerStatsTool(mc));
        register(new SubscribeEventsTool());
        register(new UnsubscribeEventsTool());
    }

    private void register(Tool tool) {
        tools.put(tool.name(), tool);
    }

    public void dispatch(ClientSession session, JsonObject frame) {
        String id = frame.has("id") ? frame.get("id").getAsString() : null;
        String toolName = frame.has("tool") ? frame.get("tool").getAsString() : "";
        JsonObject args = frame.has("args") && frame.get("args").isJsonObject()
                ? frame.getAsJsonObject("args")
                : new JsonObject();

        Tool tool = tools.get(toolName);
        if (tool == null) {
            sendError(session, id, "UNKNOWN_TOOL", "No such tool: " + toolName);
            return;
        }

        // Bounce to main thread for any world / player access.
        mc.execute(() -> {
            try {
                JsonObject result = tool.invoke(args, session);
                JsonObject resp = new JsonObject();
                resp.addProperty("v", 0);
                resp.addProperty("type", "response");
                if (id != null) resp.addProperty("id", id);
                resp.addProperty("ok", true);
                resp.add("result", result == null ? new JsonObject() : result);
                session.send(GSON.toJson(resp));
            } catch (ToolException te) {
                sendError(session, id, te.code(), te.getMessage());
            } catch (Throwable t) {
                AgentLinkMod.LOG.error("agent-link tool {} crashed", toolName, t);
                sendError(session, id, "INTERNAL_ERROR", t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        });
    }

    private void sendError(ClientSession session, String id, String code, String message) {
        JsonObject resp = new JsonObject();
        resp.addProperty("v", 0);
        resp.addProperty("type", "response");
        if (id != null) resp.addProperty("id", id);
        resp.addProperty("ok", false);
        JsonObject body = new JsonObject();
        body.addProperty("code", code);
        body.addProperty("message", message == null ? "" : message);
        resp.add("error", body);
        session.send(GSON.toJson(resp));
    }

    /** Helper for tools that need to read a string arg. */
    public static String requireString(JsonObject args, String key) throws ToolException {
        JsonElement el = args.get(key);
        if (el == null || el.isJsonNull() || !el.getAsJsonPrimitive().isString()) {
            throw new ToolException("INVALID_ARGS", "Missing string arg: " + key);
        }
        return el.getAsString();
    }
}

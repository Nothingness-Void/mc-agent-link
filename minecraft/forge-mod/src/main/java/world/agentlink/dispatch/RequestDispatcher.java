package world.agentlink.dispatch;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import world.agentlink.AgentLinkMod;
import world.agentlink.approval.AgentToolApproval;
import world.agentlink.audit.AuditLog;
import world.agentlink.dispatch.tools.AgentHeartbeatTool;
import world.agentlink.dispatch.tools.BroadcastTool;
import world.agentlink.dispatch.tools.CommandTool;
import world.agentlink.dispatch.tools.FindPlayersTool;
import world.agentlink.dispatch.tools.GetBiomeTool;
import world.agentlink.dispatch.tools.GetBlockTool;
import world.agentlink.dispatch.tools.GetBlocksRegionTool;
import world.agentlink.dispatch.tools.GetContainerTool;
import world.agentlink.dispatch.tools.GetItemInfoTool;
import world.agentlink.dispatch.tools.GetPlayerInfoTool;
import world.agentlink.dispatch.tools.GetPlayerInventoryTool;
import world.agentlink.dispatch.tools.GetAgentRequestsTool;
import world.agentlink.dispatch.tools.GetBlockDropsTool;
import world.agentlink.dispatch.tools.GetRecentEventsTool;
import world.agentlink.dispatch.tools.GetRecentLogsTool;
import world.agentlink.dispatch.tools.GetRecipesForTool;
import world.agentlink.dispatch.tools.GetScoreboardTool;
import world.agentlink.dispatch.tools.GetServerStatsTool;
import world.agentlink.dispatch.tools.GetWorldInfoTool;
import world.agentlink.dispatch.tools.ReadConfigTool;
import world.agentlink.dispatch.tools.SaveBlockSnapshotTool;
import world.agentlink.dispatch.tools.ListDimensionsTool;
import world.agentlink.dispatch.tools.ListDirTool;
import world.agentlink.dispatch.tools.ListEntitiesNearTool;
import world.agentlink.dispatch.tools.ListModsTool;
import world.agentlink.dispatch.tools.ListOnlinePlayersTool;
import world.agentlink.dispatch.tools.PingTool;
import world.agentlink.dispatch.tools.RaycastTool;
import world.agentlink.dispatch.tools.ReadServerFileTool;
import world.agentlink.dispatch.tools.RegistryListTool;
import world.agentlink.dispatch.tools.ReplyAgentRequestTool;
import world.agentlink.dispatch.tools.RunConsoleCommandTool;
import world.agentlink.dispatch.tools.SparkHealthReportTool;
import world.agentlink.dispatch.tools.SparkProfilerCancelTool;
import world.agentlink.dispatch.tools.SparkProfilerStartTool;
import world.agentlink.dispatch.tools.SparkProfilerStopTool;
import world.agentlink.dispatch.tools.SparkStatsTool;
import world.agentlink.dispatch.tools.SparkStatusTool;
import world.agentlink.dispatch.tools.SubscribeEventsTool;
import world.agentlink.dispatch.tools.ThreadDumpTool;
import world.agentlink.dispatch.tools.TickProfileTool;
import world.agentlink.dispatch.tools.UnsubscribeEventsTool;
import world.agentlink.dispatch.tools.UpdateAgentRequestStatusTool;
import world.agentlink.dispatch.tools.WeCylTool;
import world.agentlink.dispatch.tools.WeReplaceTool;
import world.agentlink.dispatch.tools.WeSetTool;
import world.agentlink.dispatch.tools.WeSphereTool;
import world.agentlink.dispatch.tools.WeStatusTool;
import world.agentlink.dispatch.tools.WeUndoTool;
import world.agentlink.dispatch.tools.WriteConfigFileTool;
import world.agentlink.transport.ClientSession;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class RequestDispatcher {

    private static final Gson GSON = new Gson();
    /** Pre-server tools queued via {@link world.agentlink.api.AgentLinkApi#registerTool}. Drained on construction. */
    private static final List<RegisteredEntry> PRE_REGISTERED = new ArrayList<>();
    private static final Object PRE_REGISTERED_LOCK = new Object();
    private static volatile RequestDispatcher CURRENT;

    private final MinecraftServer mc;
    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    /** Insertion-ordered registry of addon tools so MCP tools/list can advertise their schemas. */
    private final Map<String, RegisteredEntry> addonTools = new LinkedHashMap<>();

    public RequestDispatcher(MinecraftServer mc) {
        this.mc = mc;
        registerBuiltin(new PingTool(mc));
        registerBuiltin(new RunConsoleCommandTool(mc));
        registerBuiltin(new ListOnlinePlayersTool(mc));
        registerBuiltin(new GetPlayerInfoTool(mc));
        registerBuiltin(new BroadcastTool(mc));
        registerBuiltin(new GetServerStatsTool(mc));
        registerBuiltin(new AgentHeartbeatTool());
        registerBuiltin(new GetAgentRequestsTool());
        registerBuiltin(new UpdateAgentRequestStatusTool(mc));
        registerBuiltin(new ReplyAgentRequestTool(mc));
        registerBuiltin(new GetRecentEventsTool());
        registerBuiltin(new GetRecentLogsTool());
        registerBuiltin(new SubscribeEventsTool());
        registerBuiltin(new UnsubscribeEventsTool());
        registerBuiltin(new ListModsTool(mc));
        registerBuiltin(new ReadServerFileTool(mc));
        registerBuiltin(new ListDirTool(mc));
        registerBuiltin(new WriteConfigFileTool(mc));
        registerBuiltin(new TickProfileTool(mc));
        registerBuiltin(new ThreadDumpTool());
        registerBuiltin(new SparkStatusTool(mc));
        registerBuiltin(new SparkStatsTool());
        registerBuiltin(new SparkProfilerStartTool(mc));
        registerBuiltin(new SparkProfilerStopTool(mc));
        registerBuiltin(new SparkProfilerCancelTool(mc));
        registerBuiltin(new SparkHealthReportTool(mc));

        // World / player introspection (added in 0.2.3)
        registerBuiltin(new GetPlayerInventoryTool(mc));
        registerBuiltin(new GetWorldInfoTool(mc));
        registerBuiltin(new GetBlockTool(mc));
        registerBuiltin(new GetBlocksRegionTool(mc));
        registerBuiltin(new GetBiomeTool(mc));
        registerBuiltin(new RaycastTool(mc));
        registerBuiltin(new ListEntitiesNearTool(mc));
        registerBuiltin(new ListDimensionsTool(mc));
        registerBuiltin(new RegistryListTool(RegistryListTool.Kind.BLOCK));
        registerBuiltin(new RegistryListTool(RegistryListTool.Kind.ITEM));
        registerBuiltin(new RegistryListTool(RegistryListTool.Kind.ENTITY));
        registerBuiltin(new RegistryListTool(RegistryListTool.Kind.BIOME));

        // Tier-A tools (added in 0.2.4)
        registerBuiltin(new CommandTool(mc));
        registerBuiltin(new FindPlayersTool(mc));
        registerBuiltin(new GetItemInfoTool());
        registerBuiltin(new GetRecipesForTool(mc));
        registerBuiltin(new GetBlockDropsTool(mc));

        // Tier-C tools (added in 0.2.4)
        registerBuiltin(new GetContainerTool(mc));
        registerBuiltin(new ReadConfigTool(mc));
        registerBuiltin(new SaveBlockSnapshotTool(mc));
        registerBuiltin(new GetScoreboardTool(mc));

        // WorldEdit integration (added in 0.2.6)
        registerBuiltin(new WeStatusTool(mc));
        registerBuiltin(new WeSetTool(mc));
        registerBuiltin(new WeReplaceTool(mc));
        registerBuiltin(new WeSphereTool(mc));
        registerBuiltin(new WeCylTool(mc));
        registerBuiltin(new WeUndoTool(mc));

        synchronized (PRE_REGISTERED_LOCK) {
            for (RegisteredEntry entry : PRE_REGISTERED) {
                registerAddon(entry);
            }
            PRE_REGISTERED.clear();
        }
        CURRENT = this;
    }

    /** Marks the current dispatcher as gone so late {@code registerAddonTool} calls fall back to the queue. */
    public static void clearCurrent(RequestDispatcher expected) {
        if (CURRENT == expected) CURRENT = null;
    }

    public static RequestDispatcher current() {
        return CURRENT;
    }

    private void registerBuiltin(Tool tool) {
        tools.put(tool.name(), tool);
    }

    /**
     * Public registration entry — addon mods call this via {@link world.agentlink.api.AgentLinkApi}.
     * Tool name is auto-prefixed with {@code <modid>__} to avoid clashes with built-in tools and
     * with other addons. Returns the actual registered name (with prefix).
     */
    public static synchronized String registerAddonTool(String modId, Tool tool) {
        if (modId == null || modId.isBlank()) throw new IllegalArgumentException("modId is required");
        if (tool == null) throw new IllegalArgumentException("tool is required");
        String rawName = tool.name();
        if (rawName == null || rawName.isBlank()) throw new IllegalArgumentException("tool.name() is required");
        String prefix = modId.trim().toLowerCase(Locale.ROOT) + "__";
        String fullName = rawName.startsWith(prefix) ? rawName : prefix + rawName;
        RegisteredEntry entry = new RegisteredEntry(modId.trim().toLowerCase(Locale.ROOT), fullName, tool);
        RequestDispatcher cur = CURRENT;
        if (cur != null) {
            cur.registerAddon(entry);
        } else {
            synchronized (PRE_REGISTERED_LOCK) {
                PRE_REGISTERED.add(entry);
            }
        }
        return fullName;
    }

    private synchronized void registerAddon(RegisteredEntry entry) {
        if (tools.containsKey(entry.fullName())) {
            AgentLinkMod.LOG.warn("agent-link: addon tool {} clashes with an existing tool; skipping", entry.fullName());
            return;
        }
        // Re-key the tool under its prefixed name so dispatch/MCP look it up consistently.
        Tool delegate = entry.tool();
        Tool wrapped = new Tool() {
            @Override public String name() { return entry.fullName(); }
            @Override public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
                return delegate.invoke(args, session);
            }
            @Override public String description() { return delegate.description(); }
            @Override public JsonObject inputSchema() { return delegate.inputSchema(); }
        };
        tools.put(entry.fullName(), wrapped);
        addonTools.put(entry.fullName(), entry);
        AgentLinkMod.LOG.info("agent-link: registered addon tool {} (modId={})", entry.fullName(), entry.modId());
    }

    /** Snapshot of currently-registered addon tools (insertion order). Used by MCP tools/list. */
    public Collection<RegisteredEntry> addonTools() {
        synchronized (this) {
            return new ArrayList<>(addonTools.values());
        }
    }

    /** WebSocket frame entry point — keeps the v0 wire shape. */
    public void dispatch(ClientSession session, JsonObject frame) {
        String id = frame.has("id") ? frame.get("id").getAsString() : null;
        String toolName = frame.has("tool") ? frame.get("tool").getAsString() : "";
        JsonObject args = frame.has("args") && frame.get("args").isJsonObject()
                ? frame.getAsJsonObject("args")
                : new JsonObject();

        invoke(toolName, args, session, (result, err) -> {
            JsonObject resp = new JsonObject();
            resp.addProperty("v", 0);
            resp.addProperty("type", "response");
            if (id != null) resp.addProperty("id", id);
            if (err == null) {
                resp.addProperty("ok", true);
                resp.add("result", result == null ? new JsonObject() : result);
            } else {
                resp.addProperty("ok", false);
                JsonObject body = new JsonObject();
                body.addProperty("code", err.code());
                body.addProperty("message", err.getMessage() == null ? "" : err.getMessage());
                resp.add("error", body);
            }
            session.send(GSON.toJson(resp));
        });
    }

    /**
     * Transport-agnostic entry point. Bounces to the server thread, runs the tool, and hands the
     * result (or error) to {@code done}. {@code session} may be null for transports without a
     * session model (e.g. MCP HTTP) — tools that touch session state must check.
     */
    public void invoke(String toolName, JsonObject args, ClientSession session,
                       BiConsumer<JsonObject, ToolException> done) {
        Tool tool = tools.get(toolName);
        if (tool == null) {
            ToolException err = new ToolException("UNKNOWN_TOOL", "No such tool: " + toolName);
            AuditLog.record(toolName, args, null, false, err.code(), err.getMessage(), null);
            done.accept(null, err);
            return;
        }
        AgentToolApproval approval = AgentToolApproval.current();
        if (approval != null) {
            approval.request(toolName, args, session).thenAccept(decision -> {
                if (!decision.approved()) {
                    AuditLog.record(toolName, args, decision, false, "APPROVAL_DENIED", decision.reason(), null);
                    done.accept(null, new ToolException("APPROVAL_DENIED", decision.reason()));
                    return;
                }
                invokeApproved(toolName, args, session, done, tool, decision);
            });
            return;
        }
        invokeApproved(toolName, args, session, done, tool, null);
    }

    private void invokeApproved(String toolName, JsonObject args, ClientSession session,
                                BiConsumer<JsonObject, ToolException> done, Tool tool,
                                AgentToolApproval.Decision decision) {
        mc.execute(() -> {
            try {
                JsonObject result = tool.invoke(args, session);
                AuditLog.record(toolName, args, decision, true, null, null, result);
                done.accept(result, null);
            } catch (ToolException te) {
                AuditLog.record(toolName, args, decision, false, te.code(), te.getMessage(), null);
                done.accept(null, te);
            } catch (Throwable t) {
                AgentLinkMod.LOG.error("agent-link tool {} crashed", toolName, t);
                ToolException te = new ToolException("INTERNAL_ERROR",
                        t.getClass().getSimpleName() + ": " + t.getMessage());
                AuditLog.record(toolName, args, decision, false, te.code(), te.getMessage(), null);
                done.accept(null, te);
            }
        });
    }

    /** Helper for tools that need to read a string arg. */
    public static String requireString(JsonObject args, String key) throws ToolException {
        JsonElement el = args.get(key);
        if (el == null || el.isJsonNull() || !el.getAsJsonPrimitive().isString()) {
            throw new ToolException("INVALID_ARGS", "Missing string arg: " + key);
        }
        return el.getAsString();
    }

    public record RegisteredEntry(String modId, String fullName, Tool tool) {}
}

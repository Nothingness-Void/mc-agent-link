package world.agentlink.spigot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/** MCP and WebSocket dispatch boundary for Bukkit's single main thread. */
public final class SpigotDispatcher {
    private static final Gson GSON = new Gson();
    private final JavaPlugin plugin;
    private final SpigotConfig.Snapshot config;
    private final ApprovalService approvals;
    private final ExecutorService workers;
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private volatile boolean stopping;

    public SpigotDispatcher(JavaPlugin plugin, SpigotConfig.Snapshot config,
                            ApprovalService approvals, EventBuffer events, TickMonitor ticks,
                            ModernCompatibility.RuntimeInfo platform) {
        this.plugin = plugin;
        this.config = config;
        this.approvals = approvals;
        AtomicInteger sequence = new AtomicInteger(1);
        this.workers = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "agent-link-spigot-worker-" + sequence.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        });
        SpigotTools.registerAll(this, plugin, config, events, ticks, platform);
    }

    public void register(Tool tool) {
        if (tools.putIfAbsent(tool.name(), tool) != null) {
            throw new IllegalArgumentException("Duplicate Agent Link tool: " + tool.name());
        }
    }

    public Collection<Tool> tools() {
        return new ArrayList<>(tools.values());
    }

    public void dispatchWebSocket(ClientSession session, JsonObject frame) {
        String id = frame.has("id") ? frame.get("id").getAsString() : null;
        String tool = frame.has("tool") ? frame.get("tool").getAsString() : "";
        JsonObject args = frame.has("args") && frame.get("args").isJsonObject()
                ? frame.getAsJsonObject("args") : new JsonObject();
        invoke(tool, args, session, session.tier(), (result, error) -> {
            JsonObject response = new JsonObject();
            response.addProperty("v", 0);
            response.addProperty("type", "response");
            if (id != null) response.addProperty("id", id);
            if (error == null) {
                response.addProperty("ok", true);
                response.add("result", result == null ? new JsonObject() : result);
            } else {
                response.addProperty("ok", false);
                JsonObject body = new JsonObject();
                body.addProperty("code", error.code());
                body.addProperty("message", error.getMessage() == null ? "" : error.getMessage());
                response.add("error", body);
            }
            session.send(GSON.toJson(response));
        });
    }

    public void invoke(String toolName, JsonObject args, ClientSession session, CallTier tier,
                       BiConsumer<JsonObject, ToolException> done) {
        Tool tool = tools.get(toolName);
        if (tool == null) {
            done.accept(null, new ToolException("UNKNOWN_TOOL", "No such tool: " + toolName));
            return;
        }
        approvals.request(toolName, args, tier, tool.mutating()).thenAccept(decision -> {
            if (!decision.approved()) {
                done.accept(null, new ToolException("APPROVAL_DENIED", decision.reason()));
                return;
            }
            execute(toolName, tool, args, session, tier, done);
        });
    }

    private void execute(String toolName, Tool tool, JsonObject args, ClientSession session, CallTier tier,
                         BiConsumer<JsonObject, ToolException> done) {
        if (stopping) {
            done.accept(null, new ToolException("SERVER_STOPPING", "Agent Link is shutting down"));
            return;
        }
        Runnable work = () -> RequestContext.run(tier, () -> {
            try {
                JsonObject result = tool.invoke(args, session);
                done.accept(result, null);
            } catch (ToolException e) {
                done.accept(null, e);
            } catch (Throwable e) {
                plugin.getLogger().warning("Tool " + toolName + " crashed: " + e);
                done.accept(null, new ToolException("INTERNAL_ERROR",
                        e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage())));
            }
        });
        if (tool.offThread()) {
            try {
                workers.execute(work);
            } catch (RejectedExecutionException e) {
                done.accept(null, new ToolException("SERVER_STOPPING", "Agent Link is shutting down"));
            }
        } else {
            Bukkit.getScheduler().runTask(plugin, work);
        }
    }

    public void shutdown() {
        stopping = true;
        workers.shutdownNow();
    }
}

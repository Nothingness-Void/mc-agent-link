package world.agentlink.dispatch.tools;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import world.agentlink.api.AgentApiException;
import world.agentlink.api.AgentDiagnosticsApi;
import world.agentlink.api.AgentLinkApi;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolArgs;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

import java.util.concurrent.Semaphore;

/** One-shot, bounded health snapshot and conservative candidate diagnosis. */
public final class ServerDiagnoseTool implements Tool {
    /** Keep repeated diagnosis calls from occupying every off-thread dispatcher worker. */
    private static final Semaphore IN_FLIGHT = new Semaphore(2);
    private final MinecraftServer mc;

    public ServerDiagnoseTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "server_diagnose";
    }

    @Override
    public boolean offThread() {
        // File I/O, JVM inspection, mod enumeration, and Spark reflection should not occupy the
        // tick loop. ServerDiagnostics hops back only for the live world/tick probes.
        return true;
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        if (!IN_FLIGHT.tryAcquire()) {
            throw new ToolException("DIAGNOSTICS_BUSY", "Another server diagnosis is already running");
        }
        try {
            AgentDiagnosticsApi.Options defaults = AgentDiagnosticsApi.Options.defaults();
            AgentDiagnosticsApi.Options options = new AgentDiagnosticsApi.Options(
                    ToolArgs.optBool(args, "include_logs", defaults.includeLogs()),
                    ToolArgs.optBool(args, "include_crash_report", defaults.includeCrashReport()),
                    ToolArgs.optBool(args, "include_crash_content", defaults.includeCrashContent()),
                    ToolArgs.optBool(args, "include_thread_dump", defaults.includeThreadDump()),
                    ToolArgs.optBool(args, "include_mods", defaults.includeMods()),
                    ToolArgs.optBool(args, "include_spark", defaults.includeSpark()),
                    ToolArgs.optIntClamped(args, "log_limit", defaults.logLimit(), 1, 200),
                    ToolArgs.optIntClamped(args, "max_frames", defaults.maxFrames(), 1, 100),
                    ToolArgs.optIntClamped(args, "max_crash_bytes", defaults.maxCrashBytes(), 4 * 1024, 256 * 1024),
                    ToolArgs.optIntClamped(args, "timeout_ms", defaults.timeoutMs(), 1_000, 30_000));
            return AgentLinkApi.diagnostics().snapshot(mc, options);
        } catch (AgentApiException ex) {
            throw new ToolException(ex.code(), ex.getMessage());
        } finally {
            IN_FLIGHT.release();
        }
    }
}

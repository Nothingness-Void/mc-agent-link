package world.agentlink.api;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import world.agentlink.diagnostics.ServerDiagnostics;

/**
 * Read-only server health and fault-diagnosis API shared by the base mod and addon mods.
 *
 * <p>The returned JSON deliberately contains both raw evidence and conservative candidate
 * findings. Addons should show the evidence to an operator rather than treating a candidate as a
 * proof of root cause.
 */
public final class AgentDiagnosticsApi {

    /** Controls the size and cost of a diagnostics snapshot. */
    public record Options(
            boolean includeLogs,
            boolean includeCrashReport,
            boolean includeCrashContent,
            boolean includeThreadDump,
            boolean includeMods,
            boolean includeSpark,
            int logLimit,
            int maxFrames,
            int maxCrashBytes,
            int timeoutMs
    ) {
        /** Compatibility constructor for addons compiled against the v0.5.0-alpha shape. */
        public Options(boolean includeLogs, boolean includeCrashReport, boolean includeCrashContent,
                       boolean includeThreadDump, boolean includeMods, boolean includeSpark,
                       int logLimit, int maxFrames, int maxCrashBytes) {
            this(includeLogs, includeCrashReport, includeCrashContent, includeThreadDump, includeMods,
                    includeSpark, logLimit, maxFrames, maxCrashBytes, 12_000);
        }

        public static Options defaults() {
            return new Options(true, true, false, true, true, true, 80, 20, 64 * 1024, 12_000);
        }

        public Options normalized() {
            return new Options(
                    includeLogs,
                    includeCrashReport,
                    includeCrashContent,
                    includeThreadDump,
                    includeMods,
                    includeSpark,
                    clamp(logLimit, 1, 200),
                    clamp(maxFrames, 1, 100),
                    clamp(maxCrashBytes, 4 * 1024, 256 * 1024),
                    clamp(timeoutMs, 1_000, 30_000));
        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }
    }

    AgentDiagnosticsApi() {}

    /** Collect a bounded default snapshot; live world probes hop to the server thread when needed. */
    public JsonObject snapshot(MinecraftServer server) throws AgentApiException {
        return snapshot(server, Options.defaults());
    }

    /** Collect a bounded snapshot using the requested component selection and limits. */
    public JsonObject snapshot(MinecraftServer server, Options options) throws AgentApiException {
        if (server == null) {
            throw new AgentApiException("SERVER_UNAVAILABLE", "server is not running");
        }
        try {
            return ServerDiagnostics.collect(server,
                    (options == null ? Options.defaults() : options).normalized());
        } catch (Throwable t) {
            String detail = t.getMessage();
            throw new AgentApiException("INTERNAL_ERROR",
                    t.getClass().getSimpleName() + (detail == null ? "" : ": " + detail), t);
        }
    }
}

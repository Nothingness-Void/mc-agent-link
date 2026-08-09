package world.agentlink.diagnostics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import world.agentlink.api.AgentDiagnosticsApi;
import world.agentlink.dispatch.ToolException;
import world.agentlink.dispatch.ServerThread;
import world.agentlink.dispatch.tools.GetRecentLogsTool;
import world.agentlink.dispatch.tools.GetServerStatsTool;
import world.agentlink.dispatch.tools.GetWorldInfoTool;
import world.agentlink.dispatch.tools.ListModsTool;
import world.agentlink.dispatch.tools.ThreadDumpTool;
import world.agentlink.dispatch.tools.TickProfileTool;
import world.agentlink.diagnostics.TickIncidentRecorder;
import world.agentlink.sandbox.ServerPaths;
import world.agentlink.spark.SparkBridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Internal implementation of the shared bounded diagnostics snapshot. */
public final class ServerDiagnostics {

    private static final DateTimeFormatter UTC = DateTimeFormatter.ISO_INSTANT;
    private static final int MAX_FINDINGS = 24;
    private static final int MAX_CRASH_KEY_LINES = 48;

    private ServerDiagnostics() {}

    public static JsonObject collect(MinecraftServer mc, AgentDiagnosticsApi.Options options)
            throws ToolException {
        long started = System.nanoTime();
        Deadline deadline = new Deadline(started, options.timeoutMs());
        JsonObject result = new JsonObject();
        result.addProperty("schema_version", 2);
        result.addProperty("timeout_ms", options.timeoutMs());
        result.addProperty("generated_at", UTC.format(Instant.now()));
        result.addProperty("process_id", ProcessHandle.current().pid());
        long processStartedAt = ManagementFactory.getRuntimeMXBean().getStartTime();
        result.addProperty("process_started_at", UTC.format(Instant.ofEpochMilli(processStartedAt)));

        JsonObject components = new JsonObject();
        JsonArray componentErrors = new JsonArray();

        JsonObject stats = probe("server_stats", components, componentErrors,
                deadline, () -> onServerThread(mc, deadline,
                        () -> new GetServerStatsTool(mc).invoke(new JsonObject(), null)));
        JsonObject tick = probe("tick_profile", components, componentErrors,
                deadline, () -> onServerThread(mc, deadline,
                        () -> new TickProfileTool(mc).invoke(new JsonObject(), null)));
        JsonObject tickIncidents = probe("tick_incidents", components, componentErrors,
                deadline, TickIncidentRecorder::snapshot);
        JsonObject world = probe("world_info", components, componentErrors,
                deadline, () -> onServerThread(mc, deadline,
                        () -> new GetWorldInfoTool(mc).invoke(new JsonObject(), null)));

        result.add("server_stats", stats);
        result.add("tick_profile", tick);
        result.add("tick_incidents", tickIncidents);
        result.add("world", world);

        if (options.includeLogs()) {
            JsonObject logArgs = new JsonObject();
            logArgs.addProperty("limit", options.logLimit());
            JsonArray levels = new JsonArray();
            levels.add("WARN");
            levels.add("ERROR");
            levels.add("FATAL");
            logArgs.add("levels", levels);
            result.add("recent_logs", probe("recent_logs", components, componentErrors, deadline,
                    () -> new GetRecentLogsTool().invoke(logArgs, null)));
        } else {
            skipped("recent_logs", components);
        }

        if (options.includeThreadDump()) {
            JsonObject threadArgs = new JsonObject();
            threadArgs.addProperty("only_server", true);
            threadArgs.addProperty("max_frames", options.maxFrames());
            result.add("threads", probe("thread_dump", components, componentErrors, deadline,
                    () -> new ThreadDumpTool().invoke(threadArgs, null)));
        } else {
            skipped("thread_dump", components);
        }

        if (options.includeMods()) {
            result.add("mods", probe("mods", components, componentErrors, deadline,
                    () -> new ListModsTool(mc).invoke(new JsonObject(), null)));
        } else {
            skipped("mods", components);
        }

        if (options.includeCrashReport()) {
            result.add("crash_report", probe("crash_report", components, componentErrors, deadline,
                    () -> latestCrashReport(mc, options, processStartedAt)));
        } else {
            skipped("crash_report", components);
        }

        if (options.includeSpark()) {
            result.add("spark", probe("spark", components, componentErrors, deadline,
                    () -> sparkSnapshot(mc)));
        } else {
            skipped("spark", components);
        }

        result.add("incident_ledger", probe("incident_ledger", components, componentErrors, deadline,
                () -> incidentLedger(mc)));

        JsonObject diagnosis = diagnose(stats, tick, world, tickIncidents,
                result.getAsJsonObject("recent_logs"),
                result.getAsJsonObject("threads"),
                result.getAsJsonObject("crash_report"),
                result.getAsJsonObject("incident_ledger"));
        result.add("diagnosis", diagnosis);
        result.add("components", components);
        result.addProperty("complete", !hasFailure(components));
        if (componentErrors.size() > 0) result.add("component_errors", componentErrors);
        result.addProperty("collection_duration_ms", elapsedMs(started));
        return result;
    }

    @FunctionalInterface
    private interface Probe {
        JsonObject run() throws Exception;
    }

    @FunctionalInterface
    private interface ServerProbe {
        JsonObject run() throws ToolException;
    }

    private static JsonObject onServerThread(MinecraftServer mc, Deadline deadline, ServerProbe probe)
            throws ToolException {
        if (deadline.expired()) throw deadline.timeout();
        return ServerThread.isServerThread(mc)
                ? probe.run()
                : ServerThread.call(mc, deadline.remainingMs(), probe::run);
    }

    private static JsonObject probe(String name, JsonObject components, JsonArray errors,
                                    Deadline deadline, Probe probe) {
        long started = System.nanoTime();
        if (deadline.expired()) {
            addTimeout(name, components, errors, deadline);
            return new JsonObject();
        }
        try {
            JsonObject value = probe.run();
            JsonObject status = new JsonObject();
            status.addProperty("status", "ok");
            status.addProperty("duration_ms", elapsedMs(started));
            components.add(name, status);
            return value == null ? new JsonObject() : value;
        } catch (Throwable t) {
            JsonObject status = new JsonObject();
            boolean timeout = t instanceof ToolException te && "SERVER_BUSY".equals(te.code());
            status.addProperty("status", timeout ? "timeout" : "error");
            status.addProperty("duration_ms", elapsedMs(started));
            status.addProperty("error", message(t));
            components.add(name, status);

            JsonObject error = new JsonObject();
            error.addProperty("component", name);
            if (timeout) error.addProperty("code", "SERVER_BUSY");
            error.addProperty("message", message(t));
            errors.add(error);
            return new JsonObject();
        }
    }

    private static void addTimeout(String name, JsonObject components, JsonArray errors, Deadline deadline) {
        JsonObject status = new JsonObject();
        status.addProperty("status", "timeout");
        status.addProperty("duration_ms", deadline.elapsedMs());
        status.addProperty("error", "diagnostics deadline exceeded");
        components.add(name, status);
        JsonObject error = new JsonObject();
        error.addProperty("component", name);
        error.addProperty("code", "SERVER_BUSY");
        error.addProperty("message", "diagnostics deadline exceeded");
        errors.add(error);
    }

    private static final class Deadline {
        private final long started;
        private final long deadlineNanos;

        private Deadline(long started, long timeoutMs) {
            this.started = started;
            this.deadlineNanos = started + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        }

        private boolean expired() {
            return System.nanoTime() >= deadlineNanos;
        }

        private long remainingMs() {
            long remaining = deadlineNanos - System.nanoTime();
            return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining + 999_999L));
        }

        private long elapsedMs() {
            return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }

        private ToolException timeout() {
            return new ToolException("SERVER_BUSY", "server_diagnose deadline exceeded");
        }
    }

    private static void skipped(String name, JsonObject components) {
        JsonObject status = new JsonObject();
        status.addProperty("status", "skipped");
        components.add(name, status);
    }

    private static boolean hasFailure(JsonObject components) {
        for (JsonElement element : components.entrySet().stream()
                .map(java.util.Map.Entry::getValue).toList()) {
            if (element.isJsonObject()) {
                String status = string(element.getAsJsonObject(), "status");
                if ("timeout".equals(status) || "error".equals(status)) return true;
            }
        }
        return false;
    }

    private static JsonObject sparkSnapshot(MinecraftServer mc) {
        boolean commandAvailable = SparkBridge.isAvailable(mc);
        boolean apiAvailable = SparkBridge.apiAvailable();
        JsonObject out = new JsonObject();
        out.addProperty("installed", commandAvailable || apiAvailable);
        out.addProperty("command_available", commandAvailable);
        out.addProperty("api_available", apiAvailable);
        if (apiAvailable) out.add("stats", SparkBridge.statsSnapshot());
        if (!commandAvailable && !apiAvailable) {
            out.addProperty("hint", "Install spark for CPU, GC, and sampled profiling data.");
        } else if (commandAvailable) {
            out.addProperty("profiler_control", "available via spark_profiler_start/stop/cancel");
        }
        return out;
    }

    private static JsonObject latestCrashReport(MinecraftServer mc, AgentDiagnosticsApi.Options options,
                                                long processStartedAt)
            throws IOException, ToolException {
        Path root = ServerPaths.root(mc);
        Path dir = root.resolve("crash-reports");
        JsonObject out = new JsonObject();
        out.addProperty("available", false);
        out.addProperty("directory", ServerPaths.relativize(mc, dir));
        if (!Files.isDirectory(dir)) return out;

        Path latest;
        try (var stream = Files.list(dir)) {
            latest = stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".txt"))
                    .max(Comparator.comparingLong(ServerDiagnostics::lastModified))
                    .orElse(null);
        }
        if (latest == null) return out;

        long size = Files.size(latest);
        byte[] prefix = readPrefix(latest, options.maxCrashBytes());
        String content = new String(prefix, StandardCharsets.UTF_8);
        out.addProperty("available", true);
        out.addProperty("path", ServerPaths.relativize(mc, latest));
        out.addProperty("size_bytes", size);
        out.addProperty("modified_at", UTC.format(Instant.ofEpochMilli(lastModified(latest))));
        out.addProperty("stale", lastModified(latest) + 1000L < processStartedAt);
        out.addProperty("truncated", prefix.length < size);
        out.add("key_lines", crashKeyLines(content));
        String summary = crashSummary(content);
        if (!summary.isBlank()) out.addProperty("summary", summary);
        if (options.includeCrashContent()) {
            out.addProperty("encoding", "utf-8");
            out.addProperty("content", content);
        } else {
            out.addProperty("content_available", true);
            out.addProperty("content_hint", "Use read_server_file with the returned path for the full report.");
        }
        return out;
    }

    private static JsonObject incidentLedger(MinecraftServer mc) throws IOException, ToolException {
        Path file = ServerPaths.root(mc).resolve("diagnostics").resolve(IncidentLedger.FILE_NAME);
        JsonObject out = new JsonObject();
        out.addProperty("available", Files.isRegularFile(file));
        out.addProperty("path", ServerPaths.relativize(mc, file));
        if (!Files.isRegularFile(file)) return out;
        byte[] bytes = readPrefix(file, 32 * 1024);
        JsonElement parsed = com.google.gson.JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
        if (!parsed.isJsonObject()) throw new IOException("incident ledger is not an object");
        out.add("ledger", parsed.getAsJsonObject());
        return out;
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private static byte[] readPrefix(Path path, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 16 * 1024));
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int remaining = maxBytes;
            while (remaining > 0) {
                int read = in.read(buffer, 0, Math.min(buffer.length, remaining));
                if (read < 0) break;
                if (read == 0) continue;
                out.write(buffer, 0, read);
                remaining -= read;
            }
        }
        return out.toByteArray();
    }

    private static JsonArray crashKeyLines(String content) {
        JsonArray lines = new JsonArray();
        for (String raw : content.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            String lower = line.toLowerCase(Locale.ROOT);
            boolean useful = line.startsWith("Description:")
                    || line.startsWith("Caused by:")
                    || line.startsWith("Failure message:")
                    || line.startsWith("Mod File:")
                    || line.startsWith("Suspected Mods:")
                    || lower.contains("outofmemoryerror")
                    || lower.contains("serverhangwatchdog")
                    || lower.contains("ticking entity")
                    || lower.contains("ticking block entity")
                    || line.startsWith("-- Head --")
                    || line.startsWith("---- Minecraft Crash Report");
            if (useful && lines.size() < MAX_CRASH_KEY_LINES) lines.add(line);
        }
        return lines;
    }

    private static String crashSummary(String content) {
        for (String raw : content.split("\\R")) {
            String line = raw.strip();
            if (line.startsWith("Description:")) return line.substring("Description:".length()).trim();
        }
        for (String raw : content.split("\\R")) {
            String line = raw.strip();
            if (line.startsWith("Caused by:")) return line;
        }
        return "";
    }

    private static JsonObject diagnose(JsonObject stats, JsonObject tick, JsonObject world,
                                       JsonObject tickIncidents,
                                       JsonObject logs, JsonObject threads, JsonObject crash,
                                       JsonObject incident) {
        JsonArray findings = new JsonArray();
        JsonArray nextSteps = new JsonArray();
        int[] worst = {0};

        double avgMspt = number(tick, "avg_mspt", number(stats, "mspt", -1));
        double p95Mspt = number(tick, "p95_mspt", -1);
        double p99Mspt = number(tick, "p99_mspt", -1);
        double maxMspt = number(tick, "max_mspt", -1);
        double tps = number(tick, "tps", number(stats, "tps", -1));

        if (p95Mspt >= 200 || maxMspt >= 1000 || tps >= 0 && tps < 10) {
            finding(findings, worst, 2, "TICK_CRITICAL", "Server tick is severely delayed",
                    "p95_mspt=" + printable(p95Mspt) + ", max_mspt=" + printable(maxMspt)
                            + ", tps=" + printable(tps),
                    "Start a short spark profiler with only_ticks_over_ms=50, then inspect the server thread stack.");
        } else if (avgMspt >= 50 || p95Mspt >= 80 || tps >= 0 && tps < 18) {
            finding(findings, worst, 1, "TICK_SLOW", "Server tick time is above the 20 TPS budget",
                    "avg_mspt=" + printable(avgMspt) + ", p95_mspt=" + printable(p95Mspt)
                            + ", tps=" + printable(tps),
                    "Compare another snapshot after 10 seconds and use spark profiling if the spike persists.");
        }

        long recentSlowTicks = (long) number(tickIncidents, "recent_samples", 0L)
                + (long) number(tickIncidents, "coalesced_since_last", 0L);
        if (recentSlowTicks > 0) {
            finding(findings, worst, recentSlowTicks >= 8 ? 2 : 1, "RECENT_TICK_SPIKES",
                    "Recent server ticks crossed the slow-tick threshold",
                    "incidents=" + recentSlowTicks + ", threshold_ms="
                            + number(tickIncidents, "threshold_ms", 50L),
                    "Use tick_incidents for timing correlation, then use spark profiling to establish the causal workload.");
        }

        long used = number(stats, "mem_used_mb", -1L);
        long max = number(stats, "mem_max_mb", -1L);
        if (used > 0 && max > 0) {
            double ratio = (double) used / max;
            if (ratio >= 0.95) {
                finding(findings, worst, 2, "MEMORY_CRITICAL", "JVM heap is nearly exhausted",
                        used + " MiB used of " + max + " MiB (" + printable(ratio * 100) + "%)",
                        "Inspect spark GC data and the latest log for OutOfMemoryError before increasing heap blindly.");
            } else if (ratio >= 0.85) {
                finding(findings, worst, 1, "MEMORY_PRESSURE", "JVM heap usage is high",
                        used + " MiB used of " + max + " MiB (" + printable(ratio * 100) + "%)",
                        "Check GC frequency and identify the mod or workload retaining objects.");
            }
        }

        long totalEntities = 0L;
        long totalChunks = 0L;
        JsonArray dimensions = world == null ? null : array(world, "dimensions");
        if (dimensions != null) {
            for (JsonElement element : dimensions) {
                if (!element.isJsonObject()) continue;
                JsonObject dimension = element.getAsJsonObject();
                totalEntities += number(dimension, "entity_count", 0L);
                totalChunks += number(dimension, "loaded_chunks", 0L);
            }
        }
        if (totalEntities >= 5000) {
            finding(findings, worst, 2, "ENTITY_LOAD_CRITICAL", "Loaded entity count is very high",
                    "entity_count=" + totalEntities,
                    "Inspect entity-heavy dimensions and remove or limit the source of the entity buildup.");
        } else if (totalEntities >= 2000) {
            finding(findings, worst, 1, "ENTITY_LOAD_HIGH", "Loaded entity count may contribute to tick cost",
                    "entity_count=" + totalEntities,
                    "Inspect entity counts by dimension and use list_entities_near around suspected farms or machines.");
        }
        if (totalChunks >= 30000) {
            finding(findings, worst, 2, "CHUNK_LOAD_CRITICAL", "A large number of chunks are loaded",
                    "loaded_chunks=" + totalChunks,
                    "Check force-loaded chunks, player spread, view distance, and active chunk-loading mods.");
        } else if (totalChunks >= 10000) {
            finding(findings, worst, 1, "CHUNK_LOAD_HIGH", "Loaded chunk count is elevated",
                    "loaded_chunks=" + totalChunks,
                    "Check force-loaded chunks and view/simulation distance before changing JVM settings.");
        }

        int errorCount = 0;
        boolean fatalLog = false;
        JsonArray logEntries = logs == null ? null : array(logs, "logs");
        if (logEntries != null) {
            for (JsonElement element : logEntries) {
                if (!element.isJsonObject()) continue;
                JsonObject entry = element.getAsJsonObject();
                String level = string(entry, "level").toUpperCase(Locale.ROOT);
                String message = string(entry, "message");
                String lower = message.toLowerCase(Locale.ROOT);
                if ("ERROR".equals(level) || "FATAL".equals(level)) errorCount++;
                if ("FATAL".equals(level) || lower.contains("outofmemoryerror")
                        || lower.contains("serverhangwatchdog") || lower.contains("ticking entity")
                        || lower.contains("ticking block entity")) fatalLog = true;
            }
        }
        if (fatalLog) {
            finding(findings, worst, 2, "FATAL_LOG_SIGNAL", "Recent logs contain a crash or watchdog signature",
                    "error_or_fatal_entries=" + errorCount,
                    "Read the latest crash report and the surrounding latest.log lines; do not treat a single stack trace as the root cause without its Caused by chain.");
        } else if (errorCount > 0) {
            finding(findings, worst, 1, "ERROR_LOG_SIGNAL", "Recent logs contain error-level entries",
                    "error_or_fatal_entries=" + errorCount,
                    "Inspect the matching logger and stack trace in latest.log before changing configuration.");
        }

        if (threads != null) {
            JsonArray threadEntries = array(threads, "threads");
            int blocked = 0;
            if (threadEntries != null) {
                for (JsonElement element : threadEntries) {
                    if (!element.isJsonObject()) continue;
                    JsonObject thread = element.getAsJsonObject();
                    if ("BLOCKED".equalsIgnoreCase(string(thread, "state"))) blocked++;
                }
            }
            if (blocked > 0 && (avgMspt >= 50 || p95Mspt >= 80)) {
                finding(findings, worst, 2, "SERVER_THREAD_BLOCKED", "A server-related thread is blocked during tick pressure",
                        "blocked_server_threads=" + blocked,
                        "Inspect threads.threads[].stack and identify the lock owner or mod before restarting.");
            }
        }

        if (crash != null && bool(crash, "available") && !bool(crash, "stale")) {
            String path = string(crash, "path");
            finding(findings, worst, 1, "CRASH_REPORT_PRESENT", "A crash report is present on disk",
                    path + (string(crash, "summary").isBlank() ? "" : ": " + string(crash, "summary")),
                    "Read the full report at the returned path and follow the deepest Caused by entry.");
            nextSteps.add("read_server_file(" + path + ")");
        }

        JsonObject ledger = incident == null ? null : object(incident, "ledger");
        if (ledger != null && bool(ledger, "previous_unclean_shutdown")) {
            finding(findings, worst, 2, "PREVIOUS_UNCLEAN_SHUTDOWN",
                    "The previous server process did not record a clean shutdown",
                    "boot_id=" + string(ledger, "boot_id") + ", heartbeat_at=" + string(ledger, "heartbeat_at"),
                    "Inspect the watchdog incident bundle, latest.log, JVM crash artifacts, and the newest crash report before assuming the current state is healthy.");
        }

        if (findings.size() == 0) {
            nextSteps.add("If the issue is intermittent, collect another server_diagnose snapshot after 10 seconds.");
        }
        if (findings.size() < MAX_FINDINGS && (avgMspt >= 50 || p95Mspt >= 80)) {
            nextSteps.add("Use spark_profiler_start with timeout=30 and only_ticks_over_ms=50, then spark_profiler_stop.");
        }

        JsonObject out = new JsonObject();
        out.addProperty("status", worst[0] >= 2 ? "critical" : worst[0] == 1 ? "warning" : "ok");
        out.add("findings", findings);
        out.add("next_steps", nextSteps);
        out.addProperty("confidence", findings.size() == 0 ? "limited" : "candidate_signals");
        return out;
    }

    private static void finding(JsonArray findings, int[] worst, int severity, String code,
                                String title, String evidence, String recommendation) {
        if (findings.size() >= MAX_FINDINGS) return;
        worst[0] = Math.max(worst[0], severity);
        JsonObject item = new JsonObject();
        item.addProperty("severity", severity >= 2 ? "critical" : "warning");
        item.addProperty("code", code);
        item.addProperty("title", title);
        item.addProperty("evidence", evidence);
        item.addProperty("recommendation", recommendation);
        findings.add(item);
    }

    private static JsonArray array(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonArray()
                ? object.getAsJsonArray(key) : null;
    }

    private static JsonObject object(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonObject()
                ? object.getAsJsonObject(key) : null;
    }

    private static String string(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return "";
        try {
            return object.get(key).getAsString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static double number(JsonObject object, String key, double fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return fallback;
        try {
            return object.get(key).getAsDouble();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static long number(JsonObject object, String key, long fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return fallback;
        try {
            return object.get(key).getAsLong();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static boolean bool(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return false;
        try {
            return object.get(key).getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String printable(double value) {
        return value < 0 ? "unknown" : String.format(Locale.ROOT, "%.2f", value);
    }

    private static long elapsedMs(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    private static String message(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String detail = root.getMessage();
        return root.getClass().getSimpleName() + (detail == null || detail.isBlank() ? "" : ": " + detail);
    }
}

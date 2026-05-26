package world.agentlink.audit;

import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.Gson;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import world.agentlink.approval.AgentToolApproval;
import world.agentlink.approval.CallTier;
import world.agentlink.config.AgentLinkConfig;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One-line-per-call audit log.
 *
 * <p>Format: each line is a single JSON object. File: {@code logs/agentlink-audit.log}, append-only.
 * <p>A daemon writer thread drains a queue so tool invocations never block on disk I/O.
 * <p>Redaction of sensitive args is driven by {@code audit.redact_args}.
 */
public final class AuditLog {

    private static final Logger LOG = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static final String FILE_NAME = "agentlink-audit.log";
    private static volatile AuditLog CURRENT;

    private final Path logFile;
    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private final Thread writer;
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean stopping = false;

    private AuditLog(Path logFile) {
        this.logFile = logFile;
        this.writer = new Thread(this::drain, "agent-link-audit");
        this.writer.setDaemon(true);
    }

    public static synchronized void start(MinecraftServer mc) {
        if (CURRENT != null) return;
        Path logsDir;
        try {
            logsDir = mc.getServerDirectory().toPath().toRealPath().resolve("logs");
        } catch (IOException e) {
            LOG.warn("agent-link audit: cannot resolve server logs dir, audit disabled: {}", e.getMessage());
            return;
        }
        try {
            Files.createDirectories(logsDir);
        } catch (IOException e) {
            LOG.warn("agent-link audit: cannot create {}: {}", logsDir, e.getMessage());
            return;
        }
        Path file = logsDir.resolve(FILE_NAME);
        AuditLog instance = new AuditLog(file);
        instance.writer.start();
        CURRENT = instance;
        LOG.info("agent-link audit: writing to {}", file);
    }

    public static synchronized void stop() {
        AuditLog cur = CURRENT;
        CURRENT = null;
        if (cur == null) return;
        cur.stopping = true;
        cur.writer.interrupt();
        try {
            cur.writer.join(2000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    public static AuditLog current() {
        return CURRENT;
    }

    public Path logFile() {
        return logFile;
    }

    /**
     * Record a single tool invocation. Safe to call from any thread; non-blocking.
     */
    public static void record(String toolName,
                              JsonObject args,
                              AgentToolApproval.Decision decision,
                              boolean resultOk,
                              String errorCode,
                              String errorMessage,
                              JsonObject result) {
        AuditLog cur = CURRENT;
        if (cur == null) return;
        AgentLinkConfig.Snapshot cfg = AgentLinkConfig.get();
        if (cfg == null || !cfg.auditEnabled()) return;
        // Capture the tier on the originating thread before the dispatcher's mc.execute hop loses it.
        CallTier.Tier tier = CallTier.current();
        try {
            JsonObject line = buildLine(toolName, args, decision, resultOk, errorCode, errorMessage, result, cfg, tier);
            cur.queue.offer(GSON.toJson(line));
        } catch (Throwable t) {
            // Never let audit serialization throw into the dispatcher.
            LOG.debug("agent-link audit: failed to encode line for {}: {}", toolName, t.toString());
        }
    }

    private static JsonObject buildLine(String toolName,
                                        JsonObject args,
                                        AgentToolApproval.Decision decision,
                                        boolean resultOk,
                                        String errorCode,
                                        String errorMessage,
                                        JsonObject result,
                                        AgentLinkConfig.Snapshot cfg,
                                        CallTier.Tier tier) {
        JsonObject o = new JsonObject();
        o.addProperty("ts", Instant.now().toString());
        o.addProperty("tool", toolName);
        if (tier != null) o.addProperty("tier", tier.name().toLowerCase(Locale.ROOT));
        if (decision != null) {
            o.addProperty("outcome", decision.outcome() == null ? "UNKNOWN" : decision.outcome().name().toLowerCase(Locale.ROOT));
            if (decision.actor() != null) o.addProperty("actor", decision.actor());
            if (decision.actorUuid() != null) o.addProperty("actor_uuid", decision.actorUuid().toString());
            if (decision.trustRule() != null) o.addProperty("trust_rule", decision.trustRule());
            if (decision.reason() != null) o.addProperty("reason", decision.reason());
        }
        o.addProperty("result_ok", resultOk);
        if (!resultOk) {
            if (errorCode != null) o.addProperty("error_code", errorCode);
            if (errorMessage != null) o.addProperty("error_message", errorMessage);
        }
        Set<String> redactArgKeys = collectArgRedacts(toolName, cfg.auditRedactArgs());
        Set<String> redactResultKeys = collectResultRedacts(toolName, cfg.auditRedactArgs());
        JsonObject redactedArgs = redact(args, redactArgKeys);
        String argsJson = GSON.toJson(redactedArgs == null ? new JsonObject() : redactedArgs);
        int max = cfg.auditMaxArgChars();
        if (argsJson.length() > max) {
            o.addProperty("args_json", argsJson.substring(0, max));
            o.addProperty("args_truncated_at", max);
        } else {
            o.addProperty("args_json", argsJson);
        }
        if (resultOk && result != null && !redactResultKeys.isEmpty()) {
            JsonObject redactedResult = redact(result, redactResultKeys);
            o.add("result_redacted_keys", GSON.toJsonTree(redactResultKeys));
            // Only echo result keys that were redacted, not the whole result, to keep lines small.
            JsonObject summary = new JsonObject();
            for (String k : redactResultKeys) {
                if (redactedResult.has(k)) summary.add(k, redactedResult.get(k));
            }
            o.add("result_summary", summary);
        }
        return o;
    }

    private static Set<String> collectArgRedacts(String toolName, List<String> rules) {
        Set<String> out = new HashSet<>();
        if (rules == null) return out;
        for (String r : rules) {
            if (r == null) continue;
            int dot = r.indexOf('.');
            if (dot <= 0 || dot == r.length() - 1) continue;
            String tool = r.substring(0, dot);
            String key = r.substring(dot + 1);
            if (tool.equalsIgnoreCase(toolName)) out.add(key);
        }
        return out;
    }

    /** Result-side redact entries are conventionally namespaced as {@code <tool>.content / .body / ...}. */
    private static Set<String> collectResultRedacts(String toolName, List<String> rules) {
        // For now, treat the same {tool}.{key} entries as candidate result keys when key in known set.
        Set<String> out = new HashSet<>();
        if (rules == null) return out;
        Set<String> resultKeyHints = Set.of("content", "body", "output");
        for (String r : rules) {
            if (r == null) continue;
            int dot = r.indexOf('.');
            if (dot <= 0 || dot == r.length() - 1) continue;
            String tool = r.substring(0, dot);
            String key = r.substring(dot + 1);
            if (tool.equalsIgnoreCase(toolName) && resultKeyHints.contains(key)) out.add(key);
        }
        return out;
    }

    private static JsonObject redact(JsonObject src, Set<String> keys) {
        if (src == null) return new JsonObject();
        JsonObject out = src.deepCopy();
        if (keys == null || keys.isEmpty()) return out;
        for (String k : keys) {
            if (!out.has(k) || out.get(k).isJsonNull()) continue;
            JsonElement el = out.get(k);
            int len = -1;
            if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                len = el.getAsString().length();
            }
            JsonObject placeholder = new JsonObject();
            placeholder.addProperty("redacted", true);
            if (len >= 0) placeholder.addProperty("length", len);
            out.add(k, placeholder);
        }
        return out;
    }

    private void drain() {
        try (BufferedWriter out = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            while (!stopping || !queue.isEmpty()) {
                String line;
                try {
                    line = queue.poll(500, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ie) {
                    if (stopping) break;
                    continue;
                }
                if (line == null) continue;
                try {
                    out.write(line);
                    out.newLine();
                    out.flush();
                } catch (IOException ioe) {
                    long d = dropped.incrementAndGet();
                    if (d == 1 || d % 100 == 0) {
                        LOG.warn("agent-link audit: write failed (dropped={}): {}", d, ioe.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            LOG.warn("agent-link audit: cannot open {}: {}", logFile, e.getMessage());
        }
    }

    /**
     * Read the last {@code n} lines (after stop-of-write best-effort flush). Used by /agent audit tail.
     * Reads the file directly; safe to call any time, but lines that haven't yet been flushed by the
     * writer thread won't appear.
     */
    public static java.util.List<String> tail(int n) throws IOException {
        AuditLog cur = CURRENT;
        if (cur == null) return java.util.List.of();
        return tailFile(cur.logFile, n);
    }

    public static Path filePath() {
        AuditLog cur = CURRENT;
        return cur == null ? null : cur.logFile;
    }

    private static java.util.List<String> tailFile(Path file, int n) throws IOException {
        if (!Files.exists(file)) return java.util.List.of();
        // Simple implementation: read whole file (audit log expected modest); for very large logs
        // this is fine in practice — operators can rotate manually if needed.
        java.util.List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (all.size() <= n) return all;
        return all.subList(all.size() - n, all.size());
    }
}

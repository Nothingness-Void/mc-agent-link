package world.agentlink.task;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import world.agentlink.AgentLinkMod;
import world.agentlink.approval.CallTier;
import world.agentlink.config.AgentLinkConfig;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Async task registry: decouples a long tool call from a single MCP request/response.
 *
 * <h2>Why this exists</h2>
 * MCP is request/response, and every layer in between has a timeout — the MCP host, the HTTP
 * client, an in-game bridge. A 100k-block build that takes three minutes will trip one of them,
 * and when the client gives up mid-call the work keeps running with nobody listening. Wrapping the
 * call in a task inverts that: {@code start_task} returns an id in milliseconds, and the agent
 * polls {@code get_task} on its own schedule.
 *
 * <h2>Execution model</h2>
 * A bounded pool ({@code tasks.max_concurrent}, default 2) runs task bodies off the server thread.
 * Bodies that implement {@link TaskContext.Sliceable} hop on-thread per slice, yielding between
 * slices so ticks keep flowing; bodies that don't are run as one on-thread unit — still useful for
 * "don't block my RPC", though it won't spare TPS.
 *
 * <h2>Persistence</h2>
 * Each terminal state is mirrored to {@code config/agent-link/tasks/<id>.json} so an operator can
 * see after the fact what an agent ran. Tasks are deliberately <b>not</b> resumed on restart: a
 * half-finished edit replayed against a world that may have changed offline is more dangerous than
 * a lost task. Records from a previous boot load as read-only history.
 */
public final class TaskManager {

    /** Records kept in memory. Older completed ones age out; the on-disk copies stay. */
    private static final int MAX_RECORDS = 200;
    /** Ceiling for a non-sliceable body's single server-thread hop. See {@link #runBody}. */
    private static final long NON_SLICEABLE_TIMEOUT_MS = 15 * 60 * 1000L;
    private static final Gson GSON = new Gson();

    private static volatile TaskManager CURRENT;

    private final MinecraftServer mc;
    private final Path dir;
    private final ExecutorService pool;
    private final Map<String, TaskRecord> records = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(1);
    private final Object lifecycleLock = new Object();
    private volatile boolean stopping;

    private TaskManager(MinecraftServer mc, Path dir, int concurrency) {
        this.mc = mc;
        this.dir = dir;
        AtomicInteger n = new AtomicInteger(1);
        this.pool = Executors.newFixedThreadPool(concurrency, r -> {
            Thread t = new Thread(r, "agent-link-task-" + n.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
    }

    public static synchronized void start(MinecraftServer mc) {
        if (CURRENT != null) return;
        AgentLinkConfig.Snapshot cfg = AgentLinkConfig.get();
        int concurrency = cfg == null ? 2 : cfg.taskMaxConcurrent();
        Path dir = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get()
                .resolve("agent-link").resolve("tasks");
        TaskManager tm = new TaskManager(mc, dir, concurrency);
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            AgentLinkMod.LOG.warn("agent-link tasks: cannot create {}: {}", dir, e.getMessage());
        }
        tm.loadHistory();
        CURRENT = tm;
        AgentLinkMod.LOG.info("agent-link tasks: ready (max_concurrent={}, dir={})", concurrency, dir);
    }

    public static synchronized void stop() {
        TaskManager cur = CURRENT;
        CURRENT = null;
        if (cur == null) return;
        synchronized (cur.lifecycleLock) {
            cur.stopping = true;
            // Ask running tasks to stop cooperatively, then drop the pool. We don't wait long: the
            // server is going down and a slice boundary arrives within a tick or two.
            for (TaskRecord r : cur.records.values()) {
                if (r.markServerStopping()) {
                    cur.persist(r);
                }
            }
            cur.pool.shutdownNow();
        }
    }

    public static TaskManager current() {
        return CURRENT;
    }

    public enum Status {
        PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        public boolean terminal() {
            return this == SUCCEEDED || this == FAILED || this == CANCELLED;
        }
    }

    /**
     * Mutable state for one task. Field access is guarded by the record's own monitor for the
     * compound reads {@code get_task} does; single-field writes from the worker are volatile.
     */
    public static final class TaskRecord {
        private final String id;
        private final String toolName;
        private final JsonObject args;
        private final long createdAtMs;
        private final CallTier.Tier tier;
        private final boolean historical;

        private volatile Status status = Status.PENDING;
        private volatile boolean cancelRequested;
        private volatile long startedAtMs;
        private volatile long finishedAtMs;
        private volatile long progressDone = -1;
        private volatile long progressTotal = -1;
        private volatile String progressMessage = "";
        private volatile JsonObject partial;
        private volatile JsonObject result;
        private volatile String errorCode;
        private volatile String errorMessage;
        private volatile Future<?> future;

        TaskRecord(String id, String toolName, JsonObject args, CallTier.Tier tier) {
            this(id, toolName, args, tier, false, System.currentTimeMillis());
        }

        private TaskRecord(String id, String toolName, JsonObject args, CallTier.Tier tier,
                           boolean historical, long createdAtMs) {
            this.id = id;
            this.toolName = toolName;
            this.args = args;
            this.tier = tier;
            this.historical = historical;
            this.createdAtMs = createdAtMs;
        }

        public String id() { return id; }
        public String toolName() { return toolName; }
        public Status status() { return status; }
        public boolean cancelRequested() { return cancelRequested; }

        /** Claim the record for its worker. A canceled/terminal record must never start late. */
        synchronized boolean begin() {
            if (status.terminal() || cancelRequested) return false;
            startedAtMs = System.currentTimeMillis();
            status = Status.RUNNING;
            return true;
        }

        /** Attach a future after submit; close the submit/cancel race for a still-pending record. */
        synchronized void attachFuture(Future<?> next) {
            future = next;
            if (status == Status.CANCELLED) next.cancel(false);
        }

        /**
         * Request cancellation without interrupting a body that may currently be mutating the
         * world. A task which has not claimed its worker slot can be terminal immediately.
         */
        synchronized boolean requestCancel(String reason) {
            if (status.terminal()) return false;
            cancelRequested = true;
            if (status == Status.PENDING) {
                Future<?> f = future;
                finishLocked(Status.CANCELLED, null,
                        reason == null ? "cancelled before start" : reason);
                if (f != null) f.cancel(false);
            }
            return true;
        }

        /** Mark non-terminal work as interrupted by shutdown; never allow a late worker to revive it. */
        synchronized boolean markServerStopping() {
            if (status.terminal()) return false;
            boolean pending = status == Status.PENDING;
            cancelRequested = true;
            finishLocked(Status.CANCELLED, null, "server stopping");
            if (pending && future != null) future.cancel(false);
            return true;
        }

        void updateProgress(long done, long total, String message) {
            if (done >= 0) this.progressDone = done;
            if (total >= 0) this.progressTotal = total;
            if (message != null) this.progressMessage = message;
        }

        void updateProgress(String message) {
            updateProgress(-1, -1, message);
        }

        void updatePartial(JsonObject p) {
            this.partial = p == null ? null : p.deepCopy();
        }

        synchronized void finish(Status s, JsonObject result, String note) {
            if (this.status.terminal()) return;
            finishLocked(s, result, note);
        }

        private void finishLocked(Status s, JsonObject result, String note) {
            this.status = s;
            this.result = s == Status.CANCELLED ? null : result;
            this.finishedAtMs = System.currentTimeMillis();
            if (note != null && this.progressMessage.isEmpty()) this.progressMessage = note;
        }

        synchronized void fail(String code, String message) {
            if (this.status.terminal()) return;
            this.status = Status.FAILED;
            this.errorCode = code;
            this.errorMessage = message;
            this.finishedAtMs = System.currentTimeMillis();
        }

        /** Rehydrate a record for inspection only. Persisted non-terminal work is never resumed. */
        static TaskRecord fromPersisted(JsonObject o) {
            String id = TaskManager.string(o, "task_id", "");
            if (id.isEmpty()) return null;

            String rawTier = TaskManager.string(o, "tier", "guest");
            CallTier.Tier tier;
            try {
                tier = CallTier.Tier.valueOf(rawTier.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                tier = CallTier.Tier.GUEST;
            }

            TaskRecord record = new TaskRecord(
                    id,
                    TaskManager.string(o, "tool", "unknown"),
                    new JsonObject(),
                    tier,
                    true,
                    TaskManager.number(o, "created_at_ms", System.currentTimeMillis()));
            String rawStatus = TaskManager.string(o, "status", "failed");
            try {
                record.status = Status.valueOf(rawStatus.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                record.status = Status.FAILED;
            }
            record.cancelRequested = TaskManager.bool(o, "cancel_requested", false);
            record.startedAtMs = TaskManager.number(o, "started_at_ms", 0L);
            record.finishedAtMs = TaskManager.number(o, "finished_at_ms", 0L);

            JsonObject progress = TaskManager.object(o, "progress");
            if (progress != null) {
                record.progressDone = TaskManager.number(progress, "done", -1L);
                record.progressTotal = TaskManager.number(progress, "total", -1L);
                record.progressMessage = TaskManager.string(progress, "message", "");
            }
            record.partial = TaskManager.object(o, "partial");
            record.result = TaskManager.object(o, "result");
            JsonObject error = TaskManager.object(o, "error");
            if (error != null) {
                record.errorCode = TaskManager.string(error, "code", null);
                record.errorMessage = TaskManager.string(error, "message", null);
            }

            if (!record.status.terminal()) {
                record.cancelRequested = true;
                record.status = Status.FAILED;
                record.errorCode = "SERVER_RESTARTED";
                record.errorMessage = "Task was interrupted by a server restart and was not resumed.";
                record.finishedAtMs = System.currentTimeMillis();
            }
            return record;
        }

        /**
         * Wire form. {@code include_result} lets {@code list_tasks} stay compact while
         * {@code get_task} returns everything — a finished 50k-block report is not something to
         * repeat for every row of a listing.
         */
        public synchronized JsonObject toJson(boolean includeResult) {
            JsonObject o = new JsonObject();
            o.addProperty("task_id", id);
            o.addProperty("tool", toolName);
            o.addProperty("status", status.wire());
            o.addProperty("created_at_ms", createdAtMs);
            o.addProperty("created_at_iso", Instant.ofEpochMilli(createdAtMs).toString());
            if (startedAtMs > 0) o.addProperty("started_at_ms", startedAtMs);
            if (finishedAtMs > 0) {
                o.addProperty("finished_at_ms", finishedAtMs);
                o.addProperty("duration_ms", finishedAtMs - Math.max(startedAtMs, createdAtMs));
            } else if (startedAtMs > 0) {
                o.addProperty("running_for_ms", System.currentTimeMillis() - startedAtMs);
            }
            o.addProperty("cancel_requested", cancelRequested);
            if (historical) o.addProperty("historical", true);
            if (tier != null) o.addProperty("tier", tier.name().toLowerCase(Locale.ROOT));

            JsonObject progress = new JsonObject();
            if (progressDone >= 0) progress.addProperty("done", progressDone);
            if (progressTotal > 0) {
                progress.addProperty("total", progressTotal);
                double frac = Math.min(1.0, Math.max(0.0, (double) progressDone / progressTotal));
                progress.addProperty("fraction", Math.round(frac * 1000) / 1000.0);
                progress.addProperty("percent", Math.round(frac * 1000) / 10.0);
            }
            if (!progressMessage.isEmpty()) progress.addProperty("message", progressMessage);
            if (progress.size() > 0) o.add("progress", progress);

            if (partial != null) o.add("partial", partial);
            if (includeResult && result != null) o.add("result", result);
            if (errorCode != null) {
                JsonObject err = new JsonObject();
                err.addProperty("code", errorCode);
                err.addProperty("message", errorMessage == null ? "" : errorMessage);
                o.add("error", err);
            }
            return o;
        }
    }

    // ------------------------------------------------------------------ submission

    /**
     * Queue {@code tool} for async execution and return its record immediately.
     *
     * <p>The caller is responsible for having already passed the wrapped tool through the approval
     * pipeline — {@code StartTaskTool} does this so wrapping a call in a task never launders it past
     * a permission check.
     */
    public TaskRecord submit(Tool tool, JsonObject args, ClientSession session) throws ToolException {
        synchronized (lifecycleLock) {
            if (stopping) throw new ToolException("SERVER_STOPPING", "Task executor is shutting down");

            String id = "task-" + Long.toString(System.currentTimeMillis(), 36) + "-" + seq.getAndIncrement();
            TaskRecord record = new TaskRecord(id, tool.name(),
                    args == null ? new JsonObject() : args.deepCopy(), CallTier.current());
            prune();
            records.put(id, record);
            // Persist PENDING before handing it to the executor. A hard JVM exit can now be diagnosed
            // after restart instead of silently losing the task that was in flight.
            persist(record);

            CallTier.Tier tier = CallTier.current();
            try {
                Future<?> f = pool.submit(() -> CallTier.with(tier, () -> runBody(tool, record, session)));
                record.attachFuture(f);
            } catch (RejectedExecutionException rex) {
                record.fail("SERVER_STOPPING", "Task executor is shutting down");
                persist(record);
                throw new ToolException("SERVER_STOPPING", "Task executor is shutting down");
            }
            return record;
        }
    }

    private void runBody(Tool tool, TaskRecord record, ClientSession session) {
        if (!record.begin()) {
            persist(record);
            return;
        }
        persist(record);
        JsonObject args = record.args;
        try {
            JsonObject result;
            if (tool instanceof TaskContext.Sliceable sliceable) {
                TaskContext ctx = new TaskContext(record);
                long est = sliceable.estimateUnits(args);
                if (est > 0) record.updateProgress(0, est, "starting");
                result = sliceable.invokeSliced(args, ctx);
            } else {
                // Non-sliceable: run it as one unit on the server thread. This still frees the MCP
                // request, which is the main point, but it cannot protect tick time.
                //
                // The wait budget is deliberately far above ServerThread's 30s default: that default
                // guards an interactive call, whereas here the whole reason the caller reached for a
                // task is that the operation is slow. Timing out at 30s would report SERVER_BUSY
                // while the work kept running — the exact confusion tasks exist to remove.
                record.updateProgress("running (not sliceable — executes as one unit)");
                result = world.agentlink.dispatch.ServerThread.call(mc, NON_SLICEABLE_TIMEOUT_MS,
                        () -> tool.invoke(args, session));
            }
            if (record.cancelRequested) {
                record.finish(Status.CANCELLED, null, "cancelled after completion");
            } else {
                record.finish(Status.SUCCEEDED, result, null);
            }
        } catch (ToolException te) {
            if ("TASK_CANCELLED".equals(te.code())) {
                record.finish(Status.CANCELLED, null, te.getMessage());
            } else {
                record.fail(te.code(), te.getMessage());
            }
        } catch (Throwable t) {
            AgentLinkMod.LOG.error("agent-link task {} ({}) crashed", record.id(), record.toolName(), t);
            String msg = t.getMessage();
            record.fail("INTERNAL_ERROR", t.getClass().getSimpleName() + (msg == null ? "" : ": " + msg));
        } finally {
            persist(record);
        }
    }

    // ------------------------------------------------------------------ queries

    public TaskRecord get(String id) {
        return id == null ? null : records.get(id);
    }

    /** Newest first, optionally filtered to a status. */
    public List<TaskRecord> list(Status filter, int limit) {
        List<TaskRecord> out = new ArrayList<>(records.values());
        out.sort(Comparator.comparingLong((TaskRecord r) -> r.createdAtMs).reversed());
        if (filter != null) out.removeIf(r -> r.status != filter);
        if (limit > 0 && out.size() > limit) return out.subList(0, limit);
        return out;
    }

    public int runningCount() {
        int n = 0;
        for (TaskRecord r : records.values()) {
            if (r.status == Status.RUNNING || r.status == Status.PENDING) n++;
        }
        return n;
    }

    /**
     * Request cancellation. Returns false when the id is unknown or already terminal.
     *
     * <p>Sliceable bodies stop at their next {@link TaskContext#checkCancelled}. Non-sliceable ones
     * cannot be interrupted safely mid-write, so the flag is recorded and the result is discarded
     * when it lands — we do not call {@code Future.cancel(true)} on a thread that may be inside a
     * world mutation.
     */
    public boolean cancel(String id) {
        TaskRecord r = get(id);
        if (r == null || !r.requestCancel("cancelled before start")) return false;
        if (r.status() == Status.CANCELLED) {
            persist(r);
        }
        return true;
    }

    // ------------------------------------------------------------------ history

    /** Load prior records as read-only history; never resume a write operation after a restart. */
    private void loadHistory() {
        if (!Files.isDirectory(dir)) return;
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparingLong(TaskManager::lastModified).reversed())
                    .limit(MAX_RECORDS)
                    .forEach(path -> {
                        try {
                            JsonElement parsed = com.google.gson.JsonParser.parseString(
                                    Files.readString(path, StandardCharsets.UTF_8));
                            if (!parsed.isJsonObject()) return;
                            TaskRecord record = TaskRecord.fromPersisted(parsed.getAsJsonObject());
                            if (record != null) records.put(record.id(), record);
                        } catch (Exception e) {
                            AgentLinkMod.LOG.debug("agent-link tasks: ignoring history {}: {}",
                                    path.getFileName(), e.getMessage());
                        }
                    });
        } catch (Exception e) {
            AgentLinkMod.LOG.warn("agent-link tasks: cannot load history: {}", e.getMessage());
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    // ------------------------------------------------------------------ persistence

    private void persist(TaskRecord record) {
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(record.id() + ".json");
            Path tmp = dir.resolve(record.id() + ".json.tmp");
            JsonObject o = record.toJson(true);
            o.addProperty("v", 1);
            Files.writeString(tmp, GSON.toJson(o), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            AgentLinkMod.LOG.debug("agent-link tasks: persist failed for {}: {}", record.id(), e.getMessage());
        }
    }

    /** Drop the oldest terminal records once we're over the in-memory cap. Disk copies survive. */
    private void prune() {
        if (records.size() < MAX_RECORDS) return;
        List<TaskRecord> terminal = new ArrayList<>();
        for (TaskRecord r : records.values()) {
            if (r.status.terminal()) terminal.add(r);
        }
        terminal.sort(Comparator.comparingLong(r -> r.finishedAtMs));
        int toDrop = records.size() - MAX_RECORDS + 1;
        for (int i = 0; i < toDrop && i < terminal.size(); i++) {
            records.remove(terminal.get(i).id());
        }
    }

    private static String string(JsonObject object, String key, String fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return fallback;
        try { return object.get(key).getAsString(); } catch (RuntimeException ignored) { return fallback; }
    }

    private static long number(JsonObject object, String key, long fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return fallback;
        try { return object.get(key).getAsLong(); } catch (RuntimeException ignored) { return fallback; }
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return fallback;
        try { return object.get(key).getAsBoolean(); } catch (RuntimeException ignored) { return fallback; }
    }

    private static JsonObject object(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonObject()
                ? object.getAsJsonObject(key) : null;
    }
}

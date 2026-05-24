package world.agentlink.spark;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import world.agentlink.AgentLinkMod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bridge to spark, the de-facto Minecraft profiler (https://spark.lucko.me).
 *
 * <p>Two interaction surfaces, in order of preference:
 * <ol>
 *   <li>The spark Java API (statistics only — TPS, MSPT, CPU, GC). Loaded
 *       reflectively so we don't hard-fail if spark isn't installed.</li>
 *   <li>The /spark slash command, captured via a synthesized
 *       {@link CommandSourceStack}. This is the only way to reach profiler
 *       control / heap dumps / health reports because the public spark API
 *       does not expose them.</li>
 * </ol>
 *
 * <p>{@link #isAvailable()} probes {@code /spark} command registration; if it
 * isn't there, all spark_* tools should refuse early with a clear message.
 */
public final class SparkBridge {

    /** Pattern that catches spark viewer URLs in command output. */
    private static final Pattern URL_PATTERN = Pattern.compile(
            "https?://(?:spark\\.lucko\\.me|sparkprofiler\\.github\\.io|[a-zA-Z0-9.-]*lucko\\.me)/[a-zA-Z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]+");

    /** Spark's status icon prefixes its own messages — useful filter for the log tap. */
    private static final String SPARK_MARKER = "⚡"; // ⚡

    private static final AtomicLong TAP_COUNTER = new AtomicLong();

    private SparkBridge() {}

    public record CommandResult(int returnValue, String output, String url) {}

    public static boolean isAvailable(MinecraftServer mc) {
        return mc.getCommands().getDispatcher().getRoot().getChild("spark") != null;
    }

    /**
     * Run a {@code /spark <subcommand>} as op level 4 and capture every line spark
     * emits — both via {@link CommandSource} (synchronous replies) and via the
     * log4j root logger (async upload results, health reports, etc.).
     *
     * <p>spark routes most output, including the asynchronous viewer URL after a
     * profiler upload, through {@code Logger.info(...)} on its own worker
     * threads — never via {@code CommandSource.sendSystemMessage}. The previous
     * managedBlock-on-server-thread approach therefore never saw the URL. We
     * install a temporary log4j appender for the duration of the wait window
     * that picks up anything carrying spark's marker character or matching the
     * viewer URL pattern, and waits only for URL arrival or the requested timeout.
     */
    public static CommandResult runSpark(MinecraftServer mc, String subcommand, long waitMs) {
        List<String> captured = Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<String> urlFuture = new CompletableFuture<>();

        SparkLogTap tap = installLogTap(mc, captured, urlFuture);

        CommandSource sink = new CommandSource() {
            @Override
            public void sendSystemMessage(Component component) {
                String s = component.getString();
                addLine(captured, s);
                completeUrlIfPresent(mc, urlFuture, s);
            }

            @Override
            public boolean acceptsSuccess() { return true; }

            @Override
            public boolean acceptsFailure() { return true; }

            @Override
            public boolean shouldInformAdmins() { return false; }
        };

        CommandSourceStack stack = new CommandSourceStack(
                sink,
                Vec3.ZERO, Vec2.ZERO,
                mc.overworld(),
                4,
                "agent-link-spark",
                Component.literal("agent-link-spark"),
                mc,
                null
        );

        String full = subcommand.startsWith("spark") ? subcommand : "spark " + subcommand;
        if (full.startsWith("/")) full = full.substring(1);

        int rv;
        try {
            rv = mc.getCommands().getDispatcher().execute(full, stack);
        } catch (Exception e) {
            AgentLinkMod.LOG.warn("spark command failed: {}", full, e);
            rv = -1;
            addLine(captured, "[agent-link] command failed: " + e.getMessage());
        }

        if (waitMs > 0) {
            long deadline = System.currentTimeMillis() + waitMs;
            CompletableFuture.delayedExecutor(waitMs, TimeUnit.MILLISECONDS).execute(() -> wakeServer(mc));
            try {
                mc.managedBlock(() -> urlFuture.isDone() || System.currentTimeMillis() >= deadline);
            } catch (Throwable t) {
                AgentLinkMod.LOG.warn("spark wait error", t);
            }
        }

        uninstallLogTap(tap);

        String joined;
        synchronized (captured) {
            joined = String.join("\n", captured);
        }
        return new CommandResult(rv, joined, extractUrl(joined));
    }

    private static void completeUrlIfPresent(MinecraftServer mc, CompletableFuture<String> urlFuture, String text) {
        if (urlFuture.isDone()) return;
        Matcher m = URL_PATTERN.matcher(text);
        if (m.find()) {
            if (urlFuture.complete(m.group())) {
                wakeServer(mc);
            }
        }
    }

    private static void addLine(List<String> list, String msg) {
        if (msg == null || msg.isEmpty()) return;
        synchronized (list) {
            // Cheap dedup: spark often emits the same string via both the command source
            // and the log4j root, and tests don't care about the difference.
            if (list.isEmpty() || !list.get(list.size() - 1).equals(msg)) {
                list.add(msg);
            }
        }
    }

    public static String extractUrl(String text) {
        Matcher m = URL_PATTERN.matcher(text);
        return m.find() ? m.group() : null;
    }

    // ---- log4j tap ---------------------------------------------------------

    /** Anchor for the dynamically-added appender so we can yank it back out. */
    private record SparkLogTap(AbstractAppender appender, LoggerContext ctx) {}

    private static SparkLogTap installLogTap(MinecraftServer mc, List<String> captured, CompletableFuture<String> urlFuture) {
        try {
            LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            Configuration cfg = ctx.getConfiguration();
            String name = "agent-link-spark-tap-" + TAP_COUNTER.incrementAndGet();

            AbstractAppender appender = new AbstractAppender(name, null, null, true, null) {
                @Override
                public void append(LogEvent event) {
                    String msg;
                    try {
                        msg = event.getMessage().getFormattedMessage();
                    } catch (Throwable t) {
                        return;
                    }
                    if (msg == null || msg.isEmpty()) return;

                    boolean isSpark = msg.contains(SPARK_MARKER);
                    Matcher um = URL_PATTERN.matcher(msg);
                    boolean hasUrl = um.find();
                    if (!isSpark && !hasUrl) return;

                    addLine(captured, msg);
                    if (hasUrl) {
                        completeUrlIfPresent(mc, urlFuture, msg);
                    }
                }
            };
            appender.start();
            cfg.addAppender(appender);
            LoggerConfig root = cfg.getRootLogger();
            root.addAppender(appender, Level.INFO, null);
            ctx.updateLoggers();
            return new SparkLogTap(appender, ctx);
        } catch (Throwable t) {
            AgentLinkMod.LOG.warn("agent-link: failed to install spark log tap", t);
            return null;
        }
    }

    private static void wakeServer(MinecraftServer mc) {
        try {
            mc.execute(() -> {});
        } catch (Throwable ignored) {
        }
    }

    private static void uninstallLogTap(SparkLogTap tap) {
        if (tap == null) return;
        try {
            Configuration cfg = tap.ctx().getConfiguration();
            LoggerConfig root = cfg.getRootLogger();
            root.removeAppender(tap.appender().getName());
            tap.appender().stop();
            tap.ctx().updateLoggers();
        } catch (Throwable t) {
            AgentLinkMod.LOG.warn("agent-link: failed to uninstall spark log tap", t);
        }
    }

    // ---- Spark Java API (statistics-only) ----------------------------------
    // Loaded entirely via reflection so the mod still compiles and runs when
    // spark isn't on the classpath. The API artifact (me.lucko:spark-api) is
    // compileOnly in build.gradle for type checking when authoring; at
    // runtime we go through Class.forName.

    private static volatile Boolean apiPresent;
    private static volatile Object cachedApi;

    /** True if me.lucko.spark.api.Spark is on the classpath AND the singleton is initialized. */
    public static boolean apiAvailable() {
        if (Boolean.FALSE.equals(apiPresent)) return false;
        try {
            getApi();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object getApi() throws ReflectiveOperationException {
        if (cachedApi != null) return cachedApi;
        Class<?> provider = Class.forName("me.lucko.spark.api.SparkProvider");
        Object api = provider.getMethod("get").invoke(null);
        cachedApi = api;
        apiPresent = Boolean.TRUE;
        return api;
    }

    /**
     * Pull a snapshot of statistics from the spark API. Returns an empty
     * object if spark is not loaded; never throws.
     */
    public static JsonObject statsSnapshot() {
        JsonObject r = new JsonObject();
        Object api;
        try {
            api = getApi();
        } catch (Throwable t) {
            apiPresent = Boolean.FALSE;
            r.addProperty("api_available", false);
            return r;
        }
        r.addProperty("api_available", true);

        // tps() and mspt() can return null on platforms that don't supply tick info.
        try {
            Object tps = api.getClass().getMethod("tps").invoke(api);
            if (tps != null) r.add("tps", readDoubleStatistic(tps, "TicksPerSecond"));
        } catch (Throwable t) {
            recordReflectionError(r, "tps", t);
        }

        try {
            Object mspt = api.getClass().getMethod("mspt").invoke(api);
            if (mspt != null) r.add("mspt", readGenericStatistic(mspt, "MillisPerTick"));
        } catch (Throwable t) {
            recordReflectionError(r, "mspt", t);
        }

        try {
            Object cpu = api.getClass().getMethod("cpuProcess").invoke(api);
            r.add("cpu_process", readDoubleStatistic(cpu, "CpuUsage"));
        } catch (Throwable t) {
            recordReflectionError(r, "cpu_process", t);
        }

        try {
            Object cpu = api.getClass().getMethod("cpuSystem").invoke(api);
            r.add("cpu_system", readDoubleStatistic(cpu, "CpuUsage"));
        } catch (Throwable t) {
            recordReflectionError(r, "cpu_system", t);
        }

        try {
            Object gcMap = api.getClass().getMethod("gc").invoke(api);
            r.add("gc", readGcStats(gcMap));
        } catch (Throwable t) {
            recordReflectionError(r, "gc", t);
        }

        return r;
    }

    // Spark's API methods return objects whose runtime classes are package-private
    // anonymous inner classes (e.g. SparkApi$4 implementing DoubleStatistic<TicksPerSecond>).
    // Looking up methods on stat.getClass() finds the erased poll(TicksPerSecond) /
    // bridge poll(Object), neither of which matches by name+param.
    //
    // The interface is declared as DoubleStatistic<W extends Enum<W> & StatisticWindow>.
    // Across spark-api versions the erasure of W has flipped between StatisticWindow and
    // Enum (multi-bound types erase to the leftmost bound; older snapshots dropped the
    // Enum bound). Instead of guessing, we scan the interface's methods for the unique
    // poll(<single-arg, not Object>) — which is the real declared method, not the
    // synthetic bridge poll(Object).

    private static java.lang.reflect.Method findPollSingleArg(Class<?> iface) throws NoSuchMethodException {
        for (java.lang.reflect.Method m : iface.getMethods()) {
            if (!"poll".equals(m.getName()) || m.getParameterCount() != 1) continue;
            // Skip the synthetic bridge method (poll(Object)).
            if (m.getParameterTypes()[0] == Object.class) continue;
            return m;
        }
        throw new NoSuchMethodException("No poll(<window>) method on " + iface.getName());
    }

    /**
     * For a {@code DoubleStatistic<W>} where W is the StatisticWindow enum
     * named {@code windowEnumName}, returns a JSON object keyed by window name
     * with each window's poll() value. Window name is lowercased.
     */
    private static JsonObject readDoubleStatistic(Object stat, String windowEnumName) throws ReflectiveOperationException {
        JsonObject out = new JsonObject();
        ClassLoader cl = stat.getClass().getClassLoader();
        Class<?> windowEnumClass = Class.forName("me.lucko.spark.api.statistic.StatisticWindow$" + windowEnumName, true, cl);
        Class<?> doubleStatistic = Class.forName("me.lucko.spark.api.statistic.types.DoubleStatistic", true, cl);
        java.lang.reflect.Method pollMethod = findPollSingleArg(doubleStatistic);
        pollMethod.setAccessible(true);

        for (Object w : windowEnumClass.getEnumConstants()) {
            String name = ((Enum<?>) w).name().toLowerCase();
            try {
                Object value = pollMethod.invoke(stat, w);
                out.addProperty(name, ((Number) value).doubleValue());
            } catch (Throwable t) {
                out.addProperty(name + "_error", reflectionMessage(t));
            }
        }
        return out;
    }

    /** GenericStatistic is the same idea but each window returns a structured value (DoubleAverageInfo). */
    private static JsonObject readGenericStatistic(Object stat, String windowEnumName) throws ReflectiveOperationException {
        JsonObject out = new JsonObject();
        ClassLoader cl = stat.getClass().getClassLoader();
        Class<?> windowEnumClass = Class.forName("me.lucko.spark.api.statistic.StatisticWindow$" + windowEnumName, true, cl);
        Class<?> genericStatistic = Class.forName("me.lucko.spark.api.statistic.types.GenericStatistic", true, cl);
        java.lang.reflect.Method pollMethod = findPollSingleArg(genericStatistic);
        pollMethod.setAccessible(true);

        Class<?> averageInfo = Class.forName("me.lucko.spark.api.statistic.misc.DoubleAverageInfo", true, cl);
        java.lang.reflect.Method mean = averageInfo.getMethod("mean");
        java.lang.reflect.Method max = averageInfo.getMethod("max");
        java.lang.reflect.Method min = averageInfo.getMethod("min");
        java.lang.reflect.Method median = averageInfo.getMethod("median");
        java.lang.reflect.Method p95 = averageInfo.getMethod("percentile95th");
        for (java.lang.reflect.Method m : new java.lang.reflect.Method[]{mean, max, min, median, p95}) {
            m.setAccessible(true);
        }

        for (Object w : windowEnumClass.getEnumConstants()) {
            String name = ((Enum<?>) w).name().toLowerCase();
            try {
                Object info = pollMethod.invoke(stat, w);
                JsonObject obj = new JsonObject();
                obj.addProperty("mean", ((Number) mean.invoke(info)).doubleValue());
                obj.addProperty("max", ((Number) max.invoke(info)).doubleValue());
                obj.addProperty("min", ((Number) min.invoke(info)).doubleValue());
                obj.addProperty("median", ((Number) median.invoke(info)).doubleValue());
                obj.addProperty("p95", ((Number) p95.invoke(info)).doubleValue());
                out.add(name, obj);
            } catch (Throwable t) {
                JsonObject err = new JsonObject();
                err.addProperty("error", reflectionMessage(t));
                out.add(name, err);
            }
        }
        return out;
    }

    private static JsonObject readGcStats(Object gcMap) throws ReflectiveOperationException {
        JsonObject out = new JsonObject();
        if (!(gcMap instanceof java.util.Map<?, ?> map) || map.isEmpty()) return out;

        Object sample = map.values().iterator().next();
        ClassLoader cl = sample.getClass().getClassLoader();
        Class<?> gcIface = Class.forName("me.lucko.spark.api.gc.GarbageCollector", true, cl);
        java.lang.reflect.Method totalCollections = gcIface.getMethod("totalCollections");
        java.lang.reflect.Method totalTime = gcIface.getMethod("totalTime");
        java.lang.reflect.Method avgTime = gcIface.getMethod("avgTime");
        java.lang.reflect.Method avgFreq = gcIface.getMethod("avgFrequency");
        for (java.lang.reflect.Method m : new java.lang.reflect.Method[]{totalCollections, totalTime, avgTime, avgFreq}) {
            m.setAccessible(true);
        }

        for (java.util.Map.Entry<?, ?> e : map.entrySet()) {
            String name = String.valueOf(e.getKey());
            Object stat = e.getValue();
            JsonObject info = new JsonObject();
            try {
                info.addProperty("total_collections", ((Number) totalCollections.invoke(stat)).longValue());
                info.addProperty("total_time_ms", ((Number) totalTime.invoke(stat)).longValue());
                info.addProperty("avg_time_ms", ((Number) avgTime.invoke(stat)).doubleValue());
                info.addProperty("avg_freq_ms", ((Number) avgFreq.invoke(stat)).doubleValue());
            } catch (Throwable t) {
                info.addProperty("error", reflectionMessage(t));
            }
            out.add(name, info);
        }
        return out;
    }

    private static String reflectionMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String msg = root.getMessage();
        return root.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }

    private static void recordReflectionError(JsonObject r, String key, Throwable t) {
        JsonObject err = new JsonObject();
        err.addProperty("error", t.getClass().getSimpleName() + ": " + (t.getMessage() == null ? "" : t.getMessage()));
        r.add(key + "_error", err);
    }

    /** Convenience: render the captured command output as a JSON array of lines. */
    public static JsonArray linesArray(String output) {
        JsonArray a = new JsonArray();
        if (output == null || output.isEmpty()) return a;
        for (String s : output.split("\n")) a.add(s);
        return a;
    }
}

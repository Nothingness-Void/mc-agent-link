package world.agentlink.spark;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import world.agentlink.AgentLinkMod;

import java.util.ArrayList;
import java.util.List;
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

    private SparkBridge() {}

    public record CommandResult(int returnValue, String output, String url) {}

    public static boolean isAvailable(MinecraftServer mc) {
        return mc.getCommands().getDispatcher().getRoot().getChild("spark") != null;
    }

    /**
     * Run a {@code /spark <subcommand>} as op level 4 and capture every chat
     * line spark emits to the source. The captured text is concatenated with
     * newlines and any spark viewer URL is extracted.
     *
     * <p>Note: spark profiler stop is asynchronous — when it returns, the
     * sample upload is in flight on a background thread. The URL message
     * arrives later, also via the {@link CommandSource} we hand spark, so we
     * keep the source alive until we time out.
     */
    public static CommandResult runSpark(MinecraftServer mc, String subcommand, long waitMs) {
        List<String> captured = new ArrayList<>();
        Object lock = new Object();

        CommandSource sink = new CommandSource() {
            @Override
            public void sendSystemMessage(Component component) {
                String s = component.getString();
                synchronized (lock) {
                    captured.add(s);
                    if (URL_PATTERN.matcher(s).find()) {
                        lock.notifyAll();
                    }
                }
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
            synchronized (lock) {
                captured.add("[agent-link] command failed: " + e.getMessage());
            }
        }

        // Wait for an async URL line if requested.
        if (waitMs > 0) {
            long deadline = System.currentTimeMillis() + waitMs;
            synchronized (lock) {
                while (!hasUrl(captured) && System.currentTimeMillis() < deadline) {
                    try {
                        lock.wait(Math.max(1, deadline - System.currentTimeMillis()));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        String joined = String.join("\n", captured);
        return new CommandResult(rv, joined, extractUrl(joined));
    }

    private static boolean hasUrl(List<String> lines) {
        for (String s : lines) if (URL_PATTERN.matcher(s).find()) return true;
        return false;
    }

    public static String extractUrl(String text) {
        Matcher m = URL_PATTERN.matcher(text);
        return m.find() ? m.group() : null;
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

    /**
     * For a {@code DoubleStatistic<W>} where W is the StatisticWindow enum
     * named {@code windowEnumName}, returns a JSON object keyed by window name
     * with each window's poll() value. Window name is lowercased.
     */
    private static JsonObject readDoubleStatistic(Object stat, String windowEnumName) throws ReflectiveOperationException {
        JsonObject out = new JsonObject();
        Class<?> windowClass = Class.forName("me.lucko.spark.api.statistic.StatisticWindow$" + windowEnumName);
        Object[] windows = windowClass.getEnumConstants();
        for (Object w : windows) {
            String name = ((Enum<?>) w).name().toLowerCase();
            try {
                Object value = stat.getClass().getMethod("poll", Class.forName("me.lucko.spark.api.statistic.StatisticWindow")).invoke(stat, w);
                out.addProperty(name, ((Number) value).doubleValue());
            } catch (Throwable t) {
                out.addProperty(name + "_error", t.getClass().getSimpleName());
            }
        }
        return out;
    }

    /** GenericStatistic is the same idea but each window returns a structured value (DoubleAverageInfo). */
    private static JsonObject readGenericStatistic(Object stat, String windowEnumName) throws ReflectiveOperationException {
        JsonObject out = new JsonObject();
        Class<?> windowClass = Class.forName("me.lucko.spark.api.statistic.StatisticWindow$" + windowEnumName);
        Object[] windows = windowClass.getEnumConstants();
        for (Object w : windows) {
            String name = ((Enum<?>) w).name().toLowerCase();
            try {
                Object info = stat.getClass().getMethod("poll", Class.forName("me.lucko.spark.api.statistic.StatisticWindow")).invoke(stat, w);
                JsonObject obj = new JsonObject();
                Class<?> infoClass = info.getClass();
                obj.addProperty("mean", ((Number) callIfPresent(info, infoClass, "mean")).doubleValue());
                obj.addProperty("max", ((Number) callIfPresent(info, infoClass, "max")).doubleValue());
                obj.addProperty("min", ((Number) callIfPresent(info, infoClass, "min")).doubleValue());
                obj.addProperty("median", ((Number) callIfPresent(info, infoClass, "median")).doubleValue());
                obj.addProperty("p95", ((Number) callIfPresent(info, infoClass, "percentile95th")).doubleValue());
                out.add(name, obj);
            } catch (Throwable t) {
                JsonObject err = new JsonObject();
                err.addProperty("error", t.getClass().getSimpleName() + ": " + t.getMessage());
                out.add(name, err);
            }
        }
        return out;
    }

    private static JsonObject readGcStats(Object gcMap) throws ReflectiveOperationException {
        JsonObject out = new JsonObject();
        if (!(gcMap instanceof java.util.Map<?, ?> map)) return out;
        for (java.util.Map.Entry<?, ?> e : map.entrySet()) {
            String name = String.valueOf(e.getKey());
            Object stat = e.getValue();
            JsonObject info = new JsonObject();
            try {
                info.addProperty("total_collections", ((Number) callIfPresent(stat, stat.getClass(), "totalCollections")).longValue());
                info.addProperty("total_time_ms", ((Number) callIfPresent(stat, stat.getClass(), "totalTime")).longValue());
                info.addProperty("avg_time_ms", ((Number) callIfPresent(stat, stat.getClass(), "avgTime")).doubleValue());
                info.addProperty("avg_freq_ms", ((Number) callIfPresent(stat, stat.getClass(), "avgFrequency")).doubleValue());
            } catch (Throwable t) {
                info.addProperty("error", t.getClass().getSimpleName());
            }
            out.add(name, info);
        }
        return out;
    }

    private static Object callIfPresent(Object target, Class<?> cls, String method) throws ReflectiveOperationException {
        return cls.getMethod(method).invoke(target);
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

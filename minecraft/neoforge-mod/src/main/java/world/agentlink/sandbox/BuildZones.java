package world.agentlink.sandbox;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import world.agentlink.config.AgentLinkConfig;
import world.agentlink.dispatch.ToolArgs;

import java.util.List;
import java.util.Locale;

/**
 * Operator-declared regions where the agent may build without a per-call approval prompt.
 *
 * <h2>The problem this solves</h2>
 * Every mutating spatial tool sat in {@code admin_only_tools}, which means an OP had to click
 * "allow" for each one. A build of any size is hundreds of calls; the prompt stops being a
 * safety control and becomes a thing the operator clicks blind. Meanwhile blanket-trusting
 * {@code fill_blocks} lets the agent flatten spawn.
 *
 * <p>A geometric bound is the right axis: the operator marks a plot as the agent's sandbox, and
 * inside it writes are free. Outside it, approval still applies exactly as before. The operator
 * decides scope once, spatially, instead of repeatedly and blindly.
 *
 * <h2>Config shape</h2>
 * <pre>
 * [[build_zones]]
 *   label = "agent plot"
 *   dim = "minecraft:overworld"
 *   min = [100, -64, 100]
 *   max = [200, 320, 200]
 * </pre>
 *
 * <p>Empty list (the default) means no zones, so behaviour matches earlier releases: every
 * mutating tool goes through approval. This is opt-in.
 *
 * <p>A call is exempt only when the <em>entire</em> affected region lies inside one zone. A fill
 * that straddles the boundary is not partially allowed — it prompts, because "clip it to the zone"
 * would silently do something other than what was asked.
 */
public final class BuildZones {

    public record Zone(String label, String dimension, ToolArgs.Box box) {
        public boolean covers(String dim, ToolArgs.Box region) {
            return dimension.equalsIgnoreCase(dim) && box.containsBox(region);
        }

        public boolean coversPoint(String dim, int x, int y, int z) {
            return dimension.equalsIgnoreCase(dim) && box.contains(x, y, z);
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("label", label);
            o.addProperty("dim", dimension);
            o.add("min", box.minJson());
            o.add("max", box.maxJson());
            o.addProperty("volume", box.volume());
            return o;
        }
    }

    private BuildZones() {}

    public static List<Zone> all() {
        AgentLinkConfig.Snapshot cfg = AgentLinkConfig.get();
        return cfg == null ? List.of() : cfg.buildZones();
    }

    public static boolean anyConfigured() {
        return !all().isEmpty();
    }

    /** The zone fully containing {@code region}, or null. */
    public static Zone find(String dim, ToolArgs.Box region) {
        if (dim == null || region == null) return null;
        for (Zone z : all()) {
            if (z.covers(dim, region)) return z;
        }
        return null;
    }

    public static Zone findPoint(String dim, int x, int y, int z) {
        if (dim == null) return null;
        for (Zone zone : all()) {
            if (zone.coversPoint(dim, x, y, z)) return zone;
        }
        return null;
    }

    /**
     * Canonical description of the region a mutating call will touch, stashed on the args object
     * under {@link #SCOPE_KEY} by the tool before it runs. The approval layer reads it to decide
     * whether a build zone covers the call.
     *
     * <p>Passing it through the args JSON rather than a ThreadLocal is intentional: the approval
     * decision happens before the tool body runs, on a different thread, so there is no tool-side
     * hook to read. Tools that mutate space therefore declare their footprint up front — see
     * {@code SpatialScope}.
     */
    public static final String SCOPE_KEY = "__agentlink_scope";

    /** Attach a declared footprint to an args object so approval can evaluate it. */
    public static void declareScope(JsonObject args, String dim, ToolArgs.Box box) {
        if (args == null || box == null) return;
        JsonObject scope = new JsonObject();
        scope.addProperty("dim", dim);
        scope.add("min", box.minJson());
        scope.add("max", box.maxJson());
        args.add(SCOPE_KEY, scope);
    }

    /** Read back a footprint declared by {@link #declareScope}, or null when absent/garbled. */
    public static Declared readScope(JsonObject args) {
        if (args == null || !args.has(SCOPE_KEY) || !args.get(SCOPE_KEY).isJsonObject()) return null;
        JsonObject scope = args.getAsJsonObject(SCOPE_KEY);
        try {
            String dim = scope.get("dim").getAsString();
            JsonObject min = scope.getAsJsonObject("min");
            JsonObject max = scope.getAsJsonObject("max");
            ToolArgs.Box box = new ToolArgs.Box(
                    min.get("x").getAsInt(), min.get("y").getAsInt(), min.get("z").getAsInt(),
                    max.get("x").getAsInt(), max.get("y").getAsInt(), max.get("z").getAsInt());
            return new Declared(dim, box);
        } catch (Exception ex) {
            return null;
        }
    }

    public record Declared(String dimension, ToolArgs.Box box) {}

    /** For {@code whoami} / diagnostics output. */
    public static JsonArray toJsonArray() {
        JsonArray arr = new JsonArray();
        for (Zone z : all()) arr.add(z.toJson());
        return arr;
    }

    /**
     * Parse one config entry. Accepts {@code min}/{@code max} as either 3-element arrays or
     * {@code {x,y,z}} tables so hand-edited toml is forgiving. Returns null on anything malformed —
     * the caller logs and skips, because a typo'd zone must never widen permissions.
     */
    public static Zone parse(Object raw) {
        if (!(raw instanceof java.util.Map<?, ?> map)) return null;
        String label = str(map.get("label"), "zone");
        String dim = str(map.get("dim"), "minecraft:overworld").toLowerCase(Locale.ROOT);
        int[] min = coords(map.get("min"));
        int[] max = coords(map.get("max"));
        if (min == null || max == null) return null;
        ToolArgs.Box box = ToolArgs.box(
                new ToolArgs.IntPos(min[0], min[1], min[2]),
                new ToolArgs.IntPos(max[0], max[1], max[2]));
        return new Zone(label, dim, box);
    }

    private static String str(Object o, String fallback) {
        if (o == null) return fallback;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? fallback : s;
    }

    private static int[] coords(Object o) {
        if (o instanceof List<?> list && list.size() == 3) {
            try {
                return new int[]{
                        ((Number) list.get(0)).intValue(),
                        ((Number) list.get(1)).intValue(),
                        ((Number) list.get(2)).intValue()};
            } catch (Exception ex) {
                return null;
            }
        }
        if (o instanceof java.util.Map<?, ?> m) {
            try {
                return new int[]{
                        ((Number) m.get("x")).intValue(),
                        ((Number) m.get("y")).intValue(),
                        ((Number) m.get("z")).intValue()};
            } catch (Exception ex) {
                return null;
            }
        }
        return null;
    }
}

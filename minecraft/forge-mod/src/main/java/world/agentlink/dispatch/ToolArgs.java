package world.agentlink.dispatch;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shared argument-reading helpers.
 *
 * <p>The older tools each grew their own {@code requireInt} / {@code requireObj} private static
 * copies (see {@link world.agentlink.dispatch.tools.GetBlockTool}). Everything added in 0.5.0 goes
 * through this class instead so error messages stay uniform and clamping is centralized.
 *
 * <p>Convention: {@code require*} throws {@code INVALID_ARGS} when absent or of the wrong type;
 * {@code opt*} returns the supplied fallback for both absent and JSON-null.
 */
public final class ToolArgs {

    private ToolArgs() {}

    // ------------------------------------------------------------------ scalars

    public static String requireString(JsonObject args, String key) throws ToolException {
        JsonElement el = get(args, key);
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
            throw new ToolException("INVALID_ARGS", "Missing string arg: " + key);
        }
        return el.getAsString();
    }

    public static String optString(JsonObject args, String key, String fallback) {
        JsonElement el = get(args, key);
        if (el == null || !el.isJsonPrimitive()) return fallback;
        try {
            return el.getAsString();
        } catch (Exception ex) {
            return fallback;
        }
    }

    /** Lower-cased + trimmed {@link #optString}. Handy for {@code mode} style enums. */
    public static String optEnum(JsonObject args, String key, String fallback) {
        String raw = optString(args, key, fallback);
        return raw == null ? null : raw.trim().toLowerCase(Locale.ROOT);
    }

    public static int requireInt(JsonObject args, String key) throws ToolException {
        JsonElement el = get(args, key);
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
            throw new ToolException("INVALID_ARGS", "Missing int arg: " + key);
        }
        return el.getAsInt();
    }

    public static int optInt(JsonObject args, String key, int fallback) {
        JsonElement el = get(args, key);
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) return fallback;
        return el.getAsInt();
    }

    /** {@link #optInt} clamped into {@code [lo, hi]}. Out-of-range values are pulled in, not rejected. */
    public static int optIntClamped(JsonObject args, String key, int fallback, int lo, int hi) {
        int v = optInt(args, key, fallback);
        return Math.max(lo, Math.min(hi, v));
    }

    public static long optLong(JsonObject args, String key, long fallback) {
        JsonElement el = get(args, key);
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) return fallback;
        return el.getAsLong();
    }

    public static double requireDouble(JsonObject args, String key) throws ToolException {
        JsonElement el = get(args, key);
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
            throw new ToolException("INVALID_ARGS", "Missing number arg: " + key);
        }
        return el.getAsDouble();
    }

    public static double optDouble(JsonObject args, String key, double fallback) {
        JsonElement el = get(args, key);
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) return fallback;
        return el.getAsDouble();
    }

    public static double optDoubleClamped(JsonObject args, String key, double fallback, double lo, double hi) {
        double v = optDouble(args, key, fallback);
        return Math.max(lo, Math.min(hi, v));
    }

    public static boolean optBool(JsonObject args, String key, boolean fallback) {
        JsonElement el = get(args, key);
        if (el == null || !el.isJsonPrimitive()) return fallback;
        try {
            return el.getAsBoolean();
        } catch (Exception ex) {
            return fallback;
        }
    }

    // ------------------------------------------------------------- objects / arrays

    public static JsonObject requireObject(JsonObject args, String key) throws ToolException {
        JsonElement el = get(args, key);
        if (el == null || !el.isJsonObject()) {
            throw new ToolException("INVALID_ARGS", "Missing object arg: " + key);
        }
        return el.getAsJsonObject();
    }

    public static JsonObject optObject(JsonObject args, String key) {
        JsonElement el = get(args, key);
        return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
    }

    public static JsonArray requireArray(JsonObject args, String key) throws ToolException {
        JsonElement el = get(args, key);
        if (el == null || !el.isJsonArray()) {
            throw new ToolException("INVALID_ARGS", "Missing array arg: " + key);
        }
        return el.getAsJsonArray();
    }

    /**
     * Accepts either a single string or an array of strings under {@code key}, returning a list
     * either way. Several tools take "one block id or many" — this keeps both shapes legal.
     */
    public static List<String> requireStringList(JsonObject args, String key) throws ToolException {
        JsonElement el = get(args, key);
        if (el == null) throw new ToolException("INVALID_ARGS", "Missing arg: " + key);
        List<String> out = new ArrayList<>();
        if (el.isJsonPrimitive()) {
            out.add(el.getAsString());
            return out;
        }
        if (el.isJsonArray()) {
            for (JsonElement item : el.getAsJsonArray()) {
                if (item == null || item.isJsonNull()) continue;
                out.add(item.getAsString());
            }
            if (out.isEmpty()) throw new ToolException("INVALID_ARGS", key + " is an empty list");
            return out;
        }
        throw new ToolException("INVALID_ARGS", key + " must be a string or array of strings");
    }

    public static List<String> optStringList(JsonObject args, String key) {
        JsonElement el = get(args, key);
        if (el == null) return null;
        try {
            List<String> out = requireStringList(args, key);
            return out.isEmpty() ? null : out;
        } catch (ToolException ex) {
            return null;
        }
    }

    // ------------------------------------------------------------------ positions

    /** An integer block position. Used for every {@code {x,y,z}} arg in the write tools. */
    public record IntPos(int x, int y, int z) {
        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("x", x);
            o.addProperty("y", y);
            o.addProperty("z", z);
            return o;
        }
    }

    /** A floating-point position, for teleports and entity spawns. */
    public record DoublePos(double x, double y, double z) {
        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("x", x);
            o.addProperty("y", y);
            o.addProperty("z", z);
            return o;
        }
    }

    /**
     * Reads a block position from {@code key}. Accepts both the object form
     * {@code {"x":1,"y":2,"z":3}} and the array form {@code [1,2,3]} so agents that already
     * have a coordinate triple don't have to reshape it.
     */
    public static IntPos requireIntPos(JsonObject args, String key) throws ToolException {
        JsonElement el = get(args, key);
        if (el == null) throw new ToolException("INVALID_ARGS", "Missing position arg: " + key);
        if (el.isJsonArray()) {
            JsonArray a = el.getAsJsonArray();
            if (a.size() != 3) {
                throw new ToolException("INVALID_ARGS", key + " array form must have exactly 3 numbers");
            }
            return new IntPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
        }
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            return new IntPos(requireInt(o, "x"), requireInt(o, "y"), requireInt(o, "z"));
        }
        throw new ToolException("INVALID_ARGS", key + " must be {x,y,z} or [x,y,z]");
    }

    /** Reads {@code x} / {@code y} / {@code z} directly off {@code args} (flat form). */
    public static IntPos requireFlatIntPos(JsonObject args) throws ToolException {
        return new IntPos(requireInt(args, "x"), requireInt(args, "y"), requireInt(args, "z"));
    }

    public static DoublePos requireDoublePos(JsonObject args, String key) throws ToolException {
        JsonElement el = get(args, key);
        if (el == null) throw new ToolException("INVALID_ARGS", "Missing position arg: " + key);
        if (el.isJsonArray()) {
            JsonArray a = el.getAsJsonArray();
            if (a.size() != 3) {
                throw new ToolException("INVALID_ARGS", key + " array form must have exactly 3 numbers");
            }
            return new DoublePos(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
        }
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            return new DoublePos(requireDouble(o, "x"), requireDouble(o, "y"), requireDouble(o, "z"));
        }
        throw new ToolException("INVALID_ARGS", key + " must be {x,y,z} or [x,y,z]");
    }

    /** An axis-aligned integer box with min/max already normalized. */
    public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public long volume() {
            return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        }

        public boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }

        /** True when every corner of {@code other} lies inside this box. */
        public boolean containsBox(Box other) {
            return contains(other.minX, other.minY, other.minZ)
                    && contains(other.maxX, other.maxY, other.maxZ);
        }

        public JsonObject minJson() {
            JsonObject o = new JsonObject();
            o.addProperty("x", minX);
            o.addProperty("y", minY);
            o.addProperty("z", minZ);
            return o;
        }

        public JsonObject maxJson() {
            JsonObject o = new JsonObject();
            o.addProperty("x", maxX);
            o.addProperty("y", maxY);
            o.addProperty("z", maxZ);
            return o;
        }
    }

    /** Reads {@code min} + {@code max} and normalizes them into a {@link Box}. */
    public static Box requireBox(JsonObject args) throws ToolException {
        IntPos a = requireIntPos(args, "min");
        IntPos b = requireIntPos(args, "max");
        return box(a, b);
    }

    public static Box box(IntPos a, IntPos b) {
        return new Box(
                Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z()),
                Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z()));
    }

    /** Rejects a box whose volume exceeds {@code max}, naming the limit in the error. */
    public static void checkVolume(Box box, long max, String what) throws ToolException {
        long v = box.volume();
        if (v > max) {
            throw new ToolException("INVALID_ARGS",
                    what + " volume " + v + " exceeds limit " + max + " — split into smaller regions"
                            + " or use start_task for a long-running edit");
        }
    }

    private static JsonElement get(JsonObject args, String key) {
        if (args == null) return null;
        JsonElement el = args.get(key);
        return el == null || el.isJsonNull() ? null : el;
    }
}

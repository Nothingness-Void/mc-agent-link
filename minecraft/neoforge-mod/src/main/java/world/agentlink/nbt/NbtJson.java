package world.agentlink.nbt;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.NbtPathArgument;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import world.agentlink.dispatch.ToolException;

import java.util.List;

/**
 * NBT ↔ JSON / SNBT conversion for the {@code get_nbt} / {@code set_nbt} tools.
 *
 * <h2>Why two representations</h2>
 * SNBT ({@code {Items:[{id:"minecraft:stone",Count:1b}]}}) is what Minecraft itself uses and is
 * fully lossless — {@code 1b} is a byte, {@code 1} is an int, {@code 1.0f} is a float. JSON has no
 * such distinction. So:
 * <ul>
 *   <li>Reads return <b>both</b>: {@code nbt} (a JSON projection that an agent can navigate
 *       cheaply) and {@code snbt} (the canonical, round-trippable string).</li>
 *   <li>Writes prefer {@code snbt} for exactness. A JSON {@code value} is also accepted, in which
 *       case integral numbers become {@code IntTag}, fractional become {@code DoubleTag}, and
 *       anything relying on a narrower type must use SNBT instead.</li>
 * </ul>
 * That asymmetry is deliberate: silently widening {@code Count:1b} to an int corrupts an item
 * stack, so the lossless path is the one we document for writes.
 *
 * <p>The JSON projection annotates non-obvious types under a sibling {@code __types} map at each
 * compound level, so an agent can tell a byte from an int without parsing SNBT. Keys whose type is
 * unambiguous in JSON (string, compound, list) are omitted from that map.
 */
public final class NbtJson {

    /** Guardrail on recursion so a pathological structure can't blow the stack. */
    private static final int MAX_DEPTH = 64;
    /** Type-annotation sibling key. Chosen to be illegal-looking enough not to clash with real NBT. */
    public static final String TYPES_KEY = "__types";

    private NbtJson() {}

    // ---------------------------------------------------------------- tag → JSON

    /** JSON projection of {@code tag}. See the class doc for the type-fidelity caveat. */
    public static JsonElement toJson(Tag tag) {
        return toJson(tag, 0);
    }

    private static JsonElement toJson(Tag tag, int depth) {
        if (tag == null || depth > MAX_DEPTH) return JsonNull.INSTANCE;
        if (tag instanceof CompoundTag compound) {
            JsonObject out = new JsonObject();
            JsonObject types = new JsonObject();
            for (String key : compound.getAllKeys()) {
                Tag child = compound.get(key);
                out.add(key, toJson(child, depth + 1));
                String hint = typeHint(child);
                if (hint != null) types.addProperty(key, hint);
            }
            // gson 2.10 has no JsonObject#isEmpty; size() is the portable check.
            if (types.size() > 0) out.add(TYPES_KEY, types);
            return out;
        }
        if (tag instanceof ListTag list) {
            JsonArray out = new JsonArray();
            for (Tag child : list) out.add(toJson(child, depth + 1));
            return out;
        }
        if (tag instanceof ByteArrayTag bytes) {
            JsonArray out = new JsonArray();
            for (byte b : bytes.getAsByteArray()) out.add(b);
            return out;
        }
        if (tag instanceof IntArrayTag ints) {
            JsonArray out = new JsonArray();
            for (int i : ints.getAsIntArray()) out.add(i);
            return out;
        }
        if (tag instanceof LongArrayTag longs) {
            JsonArray out = new JsonArray();
            for (long l : longs.getAsLongArray()) out.add(l);
            return out;
        }
        if (tag instanceof StringTag str) return new JsonPrimitive(str.getAsString());
        if (tag instanceof ByteTag b) return new JsonPrimitive(b.getAsByte());
        if (tag instanceof ShortTag s) return new JsonPrimitive(s.getAsShort());
        if (tag instanceof IntTag i) return new JsonPrimitive(i.getAsInt());
        if (tag instanceof LongTag l) return new JsonPrimitive(l.getAsLong());
        if (tag instanceof FloatTag f) return new JsonPrimitive(f.getAsFloat());
        if (tag instanceof DoubleTag d) return new JsonPrimitive(d.getAsDouble());
        if (tag instanceof NumericTag n) return new JsonPrimitive(n.getAsDouble());
        // Unknown / EndTag — represent structurally rather than dropping it.
        return new JsonPrimitive(tag.toString());
    }

    /** Short type name for the {@code __types} annotation, or null when JSON is unambiguous. */
    private static String typeHint(Tag tag) {
        if (tag instanceof ByteTag) return "byte";
        if (tag instanceof ShortTag) return "short";
        if (tag instanceof IntTag) return "int";
        if (tag instanceof LongTag) return "long";
        if (tag instanceof FloatTag) return "float";
        if (tag instanceof DoubleTag) return "double";
        if (tag instanceof ByteArrayTag) return "byte_array";
        if (tag instanceof IntArrayTag) return "int_array";
        if (tag instanceof LongArrayTag) return "long_array";
        return null;
    }

    /** Canonical lossless SNBT for {@code tag}. */
    public static String toSnbt(Tag tag) {
        return tag == null ? "" : tag.toString();
    }

    /**
     * Standard read-side envelope: {@code {snbt, nbt, size_estimate}}. Every tool that returns NBT
     * uses this so the agent sees one consistent shape.
     */
    public static JsonObject envelope(Tag tag) {
        JsonObject out = new JsonObject();
        String snbt = toSnbt(tag);
        out.addProperty("snbt", snbt);
        out.add("nbt", toJson(tag));
        out.addProperty("snbt_length", snbt.length());
        return out;
    }

    // ---------------------------------------------------------------- JSON → tag

    /**
     * Parse SNBT into a compound. {@code TagParser} is Minecraft's own parser, so anything the
     * {@code /data merge} command accepts works here verbatim.
     */
    public static CompoundTag parseSnbtCompound(String snbt) throws ToolException {
        if (snbt == null || snbt.isBlank()) {
            throw new ToolException("INVALID_ARGS", "snbt is empty");
        }
        try {
            return TagParser.parseTag(snbt);
        } catch (CommandSyntaxException e) {
            throw new ToolException("INVALID_ARGS", "Invalid SNBT: " + e.getMessage());
        }
    }

    /**
     * Parse any SNBT value — not just a compound. Used by {@code set_nbt} when the target path
     * points at a scalar or list element. Wraps the value in a throwaway compound because
     * {@code TagParser} only exposes a compound entry point.
     */
    public static Tag parseSnbtValue(String snbt) throws ToolException {
        if (snbt == null || snbt.isBlank()) {
            throw new ToolException("INVALID_ARGS", "snbt is empty");
        }
        String trimmed = snbt.trim();
        if (trimmed.startsWith("{")) return parseSnbtCompound(trimmed);
        CompoundTag wrapper = parseSnbtCompound("{v:" + trimmed + "}");
        Tag value = wrapper.get("v");
        if (value == null) throw new ToolException("INVALID_ARGS", "Could not parse SNBT value: " + snbt);
        return value;
    }

    /**
     * Convert a JSON value into a tag. Integral numbers become ints (longs when out of int range),
     * fractional become doubles, booleans become bytes — matching how vanilla writes them.
     * Use SNBT when a narrower type matters.
     */
    public static Tag fromJson(JsonElement el) throws ToolException {
        return fromJson(el, 0);
    }

    private static Tag fromJson(JsonElement el, int depth) throws ToolException {
        if (depth > MAX_DEPTH) throw new ToolException("INVALID_ARGS", "NBT nesting exceeds " + MAX_DEPTH);
        if (el == null || el.isJsonNull()) {
            throw new ToolException("INVALID_ARGS", "NBT cannot contain null — omit the key instead");
        }
        if (el.isJsonObject()) {
            CompoundTag out = new CompoundTag();
            JsonObject obj = el.getAsJsonObject();
            JsonObject types = obj.has(TYPES_KEY) && obj.get(TYPES_KEY).isJsonObject()
                    ? obj.getAsJsonObject(TYPES_KEY)
                    : null;
            for (String key : obj.keySet()) {
                if (TYPES_KEY.equals(key)) continue;
                JsonElement child = obj.get(key);
                String hint = types != null && types.has(key) ? types.get(key).getAsString() : null;
                out.put(key, hint == null ? fromJson(child, depth + 1) : coerce(child, hint, depth));
            }
            return out;
        }
        if (el.isJsonArray()) {
            ListTag out = new ListTag();
            for (JsonElement child : el.getAsJsonArray()) out.add(fromJson(child, depth + 1));
            return out;
        }
        JsonPrimitive prim = el.getAsJsonPrimitive();
        if (prim.isBoolean()) return ByteTag.valueOf(prim.getAsBoolean());
        if (prim.isString()) return StringTag.valueOf(prim.getAsString());
        if (prim.isNumber()) {
            double d = prim.getAsDouble();
            if (d == Math.rint(d) && !Double.isInfinite(d) && !prim.getAsString().contains(".")) {
                long l = prim.getAsLong();
                if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) return IntTag.valueOf((int) l);
                return LongTag.valueOf(l);
            }
            return DoubleTag.valueOf(d);
        }
        throw new ToolException("INVALID_ARGS", "Unsupported JSON value in NBT: " + el);
    }

    /** Honour a {@code __types} hint so a JSON round-trip of our own output stays faithful. */
    private static Tag coerce(JsonElement el, String hint, int depth) throws ToolException {
        try {
            switch (hint) {
                case "byte": return ByteTag.valueOf((byte) el.getAsInt());
                case "short": return ShortTag.valueOf((short) el.getAsInt());
                case "int": return IntTag.valueOf(el.getAsInt());
                case "long": return LongTag.valueOf(el.getAsLong());
                case "float": return FloatTag.valueOf(el.getAsFloat());
                case "double": return DoubleTag.valueOf(el.getAsDouble());
                case "byte_array": {
                    JsonArray a = el.getAsJsonArray();
                    byte[] buf = new byte[a.size()];
                    for (int i = 0; i < a.size(); i++) buf[i] = (byte) a.get(i).getAsInt();
                    return new ByteArrayTag(buf);
                }
                case "int_array": {
                    JsonArray a = el.getAsJsonArray();
                    int[] buf = new int[a.size()];
                    for (int i = 0; i < a.size(); i++) buf[i] = a.get(i).getAsInt();
                    return new IntArrayTag(buf);
                }
                case "long_array": {
                    JsonArray a = el.getAsJsonArray();
                    long[] buf = new long[a.size()];
                    for (int i = 0; i < a.size(); i++) buf[i] = a.get(i).getAsLong();
                    return new LongArrayTag(buf);
                }
                default: return fromJson(el, depth + 1);
            }
        } catch (ToolException te) {
            throw te;
        } catch (Exception ex) {
            throw new ToolException("INVALID_ARGS",
                    "Value does not fit declared type " + hint + ": " + el);
        }
    }

    // ---------------------------------------------------------------- NBT paths

    /**
     * Compile an NBT path like {@code Items[0].tag.display.Name}. Same grammar as
     * {@code /data get entity @s <path>} — we reuse the vanilla parser so behaviour matches what
     * the operator would type in chat.
     */
    public static NbtPathArgument.NbtPath parsePath(String path) throws ToolException {
        if (path == null || path.isBlank()) {
            throw new ToolException("INVALID_ARGS", "path is empty");
        }
        try {
            return new NbtPathArgument().parse(new StringReader(path));
        } catch (CommandSyntaxException e) {
            throw new ToolException("INVALID_ARGS", "Invalid NBT path \"" + path + "\": " + e.getMessage());
        }
    }

    /**
     * Resolve {@code path} against {@code root}. Returns every match — vanilla paths can select
     * multiple nodes (e.g. {@code Items[]}). An empty result is not an error here; the caller
     * decides whether "no match" means 404 or an empty list.
     */
    public static List<Tag> resolvePath(Tag root, String path) throws ToolException {
        NbtPathArgument.NbtPath compiled = parsePath(path);
        try {
            return compiled.get(root);
        } catch (CommandSyntaxException e) {
            // Vanilla throws "found no elements" here rather than returning empty.
            return List.of();
        }
    }
}

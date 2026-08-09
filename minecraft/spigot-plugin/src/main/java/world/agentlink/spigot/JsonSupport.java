package world.agentlink.spigot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** Small JSON argument and schema helpers shared by the Bukkit tools. */
public final class JsonSupport {
    private JsonSupport() {}

    public static String requiredString(JsonObject args, String key) throws ToolException {
        JsonElement value = args == null ? null : args.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
            throw new ToolException("INVALID_ARGS", "Missing string arg: " + key);
        }
        return value.getAsString();
    }

    public static int requiredInt(JsonObject args, String key) throws ToolException {
        JsonElement value = args == null ? null : args.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
            throw new ToolException("INVALID_ARGS", "Missing integer arg: " + key);
        }
        return value.getAsInt();
    }

    public static int optionalInt(JsonObject args, String key, int fallback) {
        try {
            JsonElement value = args == null ? null : args.get(key);
            return value == null || value.isJsonNull() ? fallback : value.getAsInt();
        } catch (Exception e) {
            return fallback;
        }
    }

    public static double optionalDouble(JsonObject args, String key, double fallback) {
        try {
            JsonElement value = args == null ? null : args.get(key);
            return value == null || value.isJsonNull() ? fallback : value.getAsDouble();
        } catch (Exception e) {
            return fallback;
        }
    }

    public static boolean optionalBoolean(JsonObject args, String key, boolean fallback) {
        try {
            JsonElement value = args == null ? null : args.get(key);
            return value == null || value.isJsonNull() ? fallback : value.getAsBoolean();
        } catch (Exception e) {
            return fallback;
        }
    }

    public static String optionalString(JsonObject args, String key, String fallback) {
        try {
            JsonElement value = args == null ? null : args.get(key);
            return value == null || value.isJsonNull() ? fallback : value.getAsString();
        } catch (Exception e) {
            return fallback;
        }
    }

    public static List<String> optionalStringList(JsonObject args, String key) {
        JsonElement value = args == null ? null : args.get(key);
        if (value == null || value.isJsonNull()) return List.of();
        List<String> result = new ArrayList<>();
        if (value.isJsonPrimitive()) {
            result.add(value.getAsString());
            return result;
        }
        if (!value.isJsonArray()) return List.of();
        for (JsonElement item : value.getAsJsonArray()) {
            if (item != null && !item.isJsonNull()) result.add(item.getAsString());
        }
        return result;
    }

    public static Position requiredPosition(JsonObject args, String key) throws ToolException {
        JsonElement value = args == null ? null : args.get(key);
        if (value == null || value.isJsonNull()) throw new ToolException("INVALID_ARGS", "Missing position arg: " + key);
        try {
            if (value.isJsonArray()) {
                JsonArray array = value.getAsJsonArray();
                if (array.size() != 3) throw new IllegalArgumentException();
                return new Position(array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt());
            }
            JsonObject object = value.getAsJsonObject();
            return new Position(requiredInt(object, "x"), requiredInt(object, "y"), requiredInt(object, "z"));
        } catch (Exception e) {
            throw new ToolException("INVALID_ARGS", key + " must be [x,y,z] or {x,y,z}");
        }
    }

    public static JsonObject objectSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    public static JsonObject schema(boolean additionalProperties, String... required) {
        JsonObject schema = objectSchema();
        schema.addProperty("additionalProperties", additionalProperties);
        JsonArray requiredArray = new JsonArray();
        for (String key : required) requiredArray.add(key);
        if (requiredArray.size() > 0) schema.add("required", requiredArray);
        return schema;
    }

    public static void stringProperty(JsonObject schema, String key, String description) {
        JsonObject properties = schema.getAsJsonObject("properties");
        JsonObject value = new JsonObject();
        value.addProperty("type", "string");
        if (description != null && !description.isBlank()) value.addProperty("description", description);
        properties.add(key, value);
    }

    public static void integerProperty(JsonObject schema, String key, String description) {
        JsonObject properties = schema.getAsJsonObject("properties");
        JsonObject value = new JsonObject();
        value.addProperty("type", "integer");
        if (description != null && !description.isBlank()) value.addProperty("description", description);
        properties.add(key, value);
    }

    public static void numberProperty(JsonObject schema, String key, String description) {
        JsonObject properties = schema.getAsJsonObject("properties");
        JsonObject value = new JsonObject();
        value.addProperty("type", "number");
        if (description != null && !description.isBlank()) value.addProperty("description", description);
        properties.add(key, value);
    }

    public static void booleanProperty(JsonObject schema, String key, String description) {
        JsonObject properties = schema.getAsJsonObject("properties");
        JsonObject value = new JsonObject();
        value.addProperty("type", "boolean");
        if (description != null && !description.isBlank()) value.addProperty("description", description);
        properties.add(key, value);
    }

    public record Position(int x, int y, int z) {}
}

package world.agentlink.dispatch.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * List saved region snapshots so the agent can find one to restore.
 *
 * <p>Without this, {@code restore_block_snapshot} needs a name the agent has to remember across
 * sessions, or discover via {@code list_dir} on an internal config path. Reading the headers here
 * also lets the agent see the region and volume before committing to a restore.
 */
public class ListSnapshotsTool implements Tool {

    /** File reads only — no world access, so keep it off the tick loop. */
    @Override
    public boolean offThread() {
        return true;
    }

    @Override
    public String name() {
        return "list_snapshots";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        Path dir = RestoreBlockSnapshotTool.snapshotDir();
        JsonObject r = new JsonObject();
        r.addProperty("dir", dir.toString());

        if (!Files.isDirectory(dir)) {
            r.add("snapshots", new JsonArray());
            r.addProperty("count", 0);
            r.addProperty("note", "No snapshots saved yet — use save_block_snapshot first.");
            return r;
        }

        List<JsonObject> entries = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : ds) {
                entries.add(describe(file));
            }
        } catch (IOException e) {
            throw new ToolException("INTERNAL_ERROR", "list failed: " + e.getMessage());
        }

        entries.sort(Comparator.comparingLong(
                (JsonObject o) -> o.has("created_at_ms") ? o.get("created_at_ms").getAsLong() : 0L).reversed());

        JsonArray arr = new JsonArray();
        for (JsonObject o : entries) arr.add(o);
        r.add("snapshots", arr);
        r.addProperty("count", arr.size());
        return r;
    }

    /** Read just the header fields. A corrupt file is reported, not skipped silently. */
    private JsonObject describe(Path file) {
        String fileName = file.getFileName().toString();
        String name = fileName.endsWith(".json")
                ? fileName.substring(0, fileName.length() - 5)
                : fileName;
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        try {
            o.addProperty("file_size", Files.size(file));
        } catch (IOException ignored) {
        }
        try {
            JsonElement el = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!el.isJsonObject()) {
                o.addProperty("error", "not a JSON object");
                return o;
            }
            JsonObject snap = el.getAsJsonObject();
            copy(snap, o, "dim");
            copy(snap, o, "volume");
            copy(snap, o, "created_at_ms");
            if (snap.has("created_at_ms")) {
                o.addProperty("created_at_iso",
                        Instant.ofEpochMilli(snap.get("created_at_ms").getAsLong()).toString());
            }
            if (snap.has("min")) o.add("min", snap.get("min"));
            if (snap.has("max")) o.add("max", snap.get("max"));
            if (snap.has("palette") && snap.get("palette").isJsonArray()) {
                o.addProperty("palette_size", snap.getAsJsonArray("palette").size());
            }
        } catch (Exception e) {
            o.addProperty("error", "unreadable: " + e.getMessage());
        }
        return o;
    }

    private static void copy(JsonObject from, JsonObject to, String key) {
        if (from.has(key) && !from.get(key).isJsonNull()) to.add(key, from.get(key));
    }
}

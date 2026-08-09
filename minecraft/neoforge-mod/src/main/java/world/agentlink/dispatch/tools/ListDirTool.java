package world.agentlink.dispatch.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.sandbox.ServerPaths;
import world.agentlink.transport.ClientSession;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

public class ListDirTool implements Tool {

    private static final int DEFAULT_MAX = 500;
    private static final int HARD_MAX = 5000;

    private final MinecraftServer mc;

    public ListDirTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "list_dir";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        String rel = args.has("path") && !args.get("path").isJsonNull()
                ? args.get("path").getAsString()
                : "";
        int maxEntries = args.has("max_entries") ? args.get("max_entries").getAsInt() : DEFAULT_MAX;
        if (maxEntries <= 0) maxEntries = DEFAULT_MAX;
        if (maxEntries > HARD_MAX) maxEntries = HARD_MAX;

        Path target = ServerPaths.resolveUnderRoot(mc, rel);

        if (!Files.exists(target)) throw new ToolException("INVALID_ARGS", "Not found: " + rel);
        if (!Files.isDirectory(target)) throw new ToolException("INVALID_ARGS", "Not a directory: " + rel);

        JsonArray entries = new JsonArray();
        boolean truncated = false;
        int total = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(target)) {
            for (Path child : ds) {
                total++;
                if (entries.size() >= maxEntries) {
                    truncated = true;
                    continue;
                }
                JsonObject e = new JsonObject();
                e.addProperty("name", child.getFileName().toString());
                try {
                    BasicFileAttributes attrs = Files.readAttributes(child, BasicFileAttributes.class);
                    e.addProperty("is_dir", attrs.isDirectory());
                    if (!attrs.isDirectory()) e.addProperty("size", attrs.size());
                    e.addProperty("mtime_ms", attrs.lastModifiedTime().toMillis());
                } catch (IOException attrErr) {
                    e.addProperty("error", "stat failed: " + attrErr.getMessage());
                }
                entries.add(e);
            }
        } catch (IOException e) {
            throw new ToolException("INTERNAL_ERROR", "list failed: " + e.getMessage());
        }

        JsonObject r = new JsonObject();
        r.addProperty("path", ServerPaths.relativize(mc, target));
        r.add("entries", entries);
        r.addProperty("returned", entries.size());
        r.addProperty("total", total);
        r.addProperty("truncated", truncated);
        return r;
    }
}

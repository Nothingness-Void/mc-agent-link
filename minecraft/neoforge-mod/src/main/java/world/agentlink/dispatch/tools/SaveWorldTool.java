package world.agentlink.dispatch.tools;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolArgs;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

/**
 * Flush the world to disk.
 *
 * <p>The point is durability at a moment the agent chooses. After a large build or a config-driven
 * repair, the changes live in memory until the next autosave; a crash in between loses them and the
 * agent's report of "done" becomes false. Calling this makes the checkpoint explicit — the natural
 * bookend to a big {@code fill_blocks} or {@code restore_block_snapshot}.
 *
 * <p>Deliberately narrow: it saves, it does not stop the server or toggle autosave. Those belong to
 * the operator, and {@code /save-off} in particular is a footgun an agent should not be able to
 * leave behind.
 */
public class SaveWorldTool implements Tool {

    private final MinecraftServer mc;

    public SaveWorldTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "save_world";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        boolean flush = ToolArgs.optBool(args, "flush", true);
        long started = System.currentTimeMillis();

        // saveEverything(suppressLog, flush, forced): flush=true waits for chunk writes to land,
        // which is the whole reason to call this rather than waiting for an autosave.
        boolean ok = mc.saveEverything(true, flush, true);
        long elapsed = System.currentTimeMillis() - started;

        JsonObject r = new JsonObject();
        r.addProperty("saved", ok);
        r.addProperty("flushed", flush);
        r.addProperty("duration_ms", elapsed);

        com.google.gson.JsonArray dims = new com.google.gson.JsonArray();
        for (ServerLevel level : mc.getAllLevels()) {
            JsonObject o = new JsonObject();
            o.addProperty("dim", Dimensions.idOf(level));
            o.addProperty("loaded_chunks", level.getChunkSource().getLoadedChunksCount());
            o.addProperty("forced_chunks", level.getForcedChunks().size());
            dims.add(o);
        }
        r.add("dimensions", dims);

        if (!ok) {
            r.addProperty("note", "The engine reported an incomplete save. Check get_recent_logs for"
                    + " I/O errors before telling the user their work is durable.");
        }
        if (elapsed > 2000) {
            r.addProperty("performance_note", "The save took " + elapsed + "ms on the server thread,"
                    + " which players will have felt as a freeze. Avoid calling this in a loop.");
        }
        return r;
    }
}

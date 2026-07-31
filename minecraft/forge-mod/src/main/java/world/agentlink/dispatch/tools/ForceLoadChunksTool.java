package world.agentlink.dispatch.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolArgs;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

import java.util.LongSummaryStatistics;

/**
 * Force-load, release, or list force-loaded chunks.
 *
 * <h2>Why the agent needs this</h2>
 * Almost every world tool silently depends on the target chunk being loaded. Read an entity in an
 * unloaded chunk and you get "not found"; write a block there and the change may be discarded when
 * the chunk generates later. An agent working on coordinates nobody is standing near hits this
 * constantly, and the failure looks like a bug in the tool rather than a loading issue.
 *
 * <p>Force-loading the working area first makes the whole write surface reliable. The counterpart
 * matters just as much: force-loaded chunks tick forever and cost TPS, so leaving them on is a
 * performance leak. {@code mode:"list"} exists so the agent (or a later session) can find and clean
 * up what was left behind.
 */
public class ForceLoadChunksTool implements Tool {

    /**
     * Chunk-count ceiling per call. 1024 chunks is a 512×512 block area — generous for a build site
     * and far below what would meaningfully hurt a server if forgotten.
     */
    private static final int MAX_CHUNKS = 1024;

    private final MinecraftServer mc;

    public ForceLoadChunksTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "force_load_chunks";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        ServerLevel level = Dimensions.resolve(mc, args);
        String mode = ToolArgs.optEnum(args, "mode", "add");

        if ("list".equals(mode)) return list(level);
        if ("clear".equals(mode)) return clear(level);
        boolean add = switch (mode) {
            case "add" -> true;
            case "remove" -> false;
            default -> throw new ToolException("INVALID_ARGS",
                    "mode must be add, remove, list, or clear (got \"" + mode + "\")");
        };

        // Region can be given as a block box or as explicit chunk coordinates.
        int minChunkX, minChunkZ, maxChunkX, maxChunkZ;
        if (args.has("min") && args.has("max")) {
            ToolArgs.Box box = ToolArgs.requireBox(args);
            minChunkX = box.minX() >> 4;
            minChunkZ = box.minZ() >> 4;
            maxChunkX = box.maxX() >> 4;
            maxChunkZ = box.maxZ() >> 4;
        } else if (args.has("chunk")) {
            JsonObject c = ToolArgs.requireObject(args, "chunk");
            int cx = ToolArgs.requireInt(c, "x");
            int cz = ToolArgs.requireInt(c, "z");
            int radius = ToolArgs.optIntClamped(args, "chunk_radius", 0, 0, 16);
            minChunkX = cx - radius; maxChunkX = cx + radius;
            minChunkZ = cz - radius; maxChunkZ = cz + radius;
        } else {
            throw new ToolException("INVALID_ARGS",
                    "Provide a block region (`min` + `max`) or `chunk`:{x,z} with an optional"
                            + " `chunk_radius`");
        }

        long count = (long) (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
        if (count > MAX_CHUNKS) {
            throw new ToolException("INVALID_ARGS",
                    "That region covers " + count + " chunks, over the limit of " + MAX_CHUNKS
                            + ". Force-loaded chunks tick permanently — load only the area you are"
                            + " working on, and release it when done.");
        }

        int changed = 0;
        JsonArray affected = new JsonArray();
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                boolean ok = level.setChunkForced(cx, cz, add);
                if (ok) changed++;
                if (affected.size() < 256) {
                    JsonArray pair = new JsonArray();
                    pair.add(cx);
                    pair.add(cz);
                    affected.add(pair);
                }
            }
        }

        JsonObject r = new JsonObject();
        r.addProperty("dim", Dimensions.idOf(level));
        r.addProperty("mode", mode);
        r.addProperty("chunks_in_region", count);
        r.addProperty("changed", changed);
        r.addProperty("already_in_state", count - changed);
        r.add("chunk_range", range(minChunkX, minChunkZ, maxChunkX, maxChunkZ));
        if (affected.size() < count) {
            r.addProperty("chunks_listed", affected.size());
            r.addProperty("chunks_truncated", true);
        }
        r.add("chunks", affected);
        r.addProperty("total_forced_now", level.getForcedChunks().size());
        if (add) {
            r.addProperty("reminder", "These chunks now tick permanently and persist across restarts."
                    + " Release them with mode:\"remove\" (same region) or mode:\"clear\" when the work"
                    + " is finished.");
        }
        return r;
    }

    private JsonObject list(ServerLevel level) {
        var forced = level.getForcedChunks();
        JsonArray arr = new JsonArray();
        LongSummaryStatistics xs = new LongSummaryStatistics();
        LongSummaryStatistics zs = new LongSummaryStatistics();
        for (long packed : forced) {
            int cx = ChunkPos.getX(packed);
            int cz = ChunkPos.getZ(packed);
            xs.accept(cx);
            zs.accept(cz);
            if (arr.size() < 512) {
                JsonArray pair = new JsonArray();
                pair.add(cx);
                pair.add(cz);
                arr.add(pair);
            }
        }
        JsonObject r = new JsonObject();
        r.addProperty("dim", Dimensions.idOf(level));
        r.addProperty("mode", "list");
        r.addProperty("count", forced.size());
        r.add("chunks", arr);
        if (arr.size() < forced.size()) r.addProperty("truncated", true);
        if (forced.size() > 0) {
            r.add("bounds", range((int) xs.getMin(), (int) zs.getMin(), (int) xs.getMax(), (int) zs.getMax()));
        }
        r.addProperty("loaded_chunks_total", level.getChunkSource().getLoadedChunksCount());
        return r;
    }

    private JsonObject clear(ServerLevel level) {
        var forced = new java.util.ArrayList<Long>(level.getForcedChunks());
        int removed = 0;
        for (long packed : forced) {
            if (level.setChunkForced(ChunkPos.getX(packed), ChunkPos.getZ(packed), false)) removed++;
        }
        JsonObject r = new JsonObject();
        r.addProperty("dim", Dimensions.idOf(level));
        r.addProperty("mode", "clear");
        r.addProperty("released", removed);
        r.addProperty("total_forced_now", level.getForcedChunks().size());
        return r;
    }

    private static JsonObject range(int minX, int minZ, int maxX, int maxZ) {
        JsonObject o = new JsonObject();
        o.addProperty("min_chunk_x", minX);
        o.addProperty("min_chunk_z", minZ);
        o.addProperty("max_chunk_x", maxX);
        o.addProperty("max_chunk_z", maxZ);
        o.addProperty("min_block_x", minX << 4);
        o.addProperty("min_block_z", minZ << 4);
        o.addProperty("max_block_x", (maxX << 4) + 15);
        o.addProperty("max_block_z", (maxZ << 4) + 15);
        return o;
    }
}

package world.agentlink.dispatch.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import world.agentlink.dispatch.ServerThread;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolArgs;
import world.agentlink.dispatch.ToolException;
import world.agentlink.task.TaskContext;
import world.agentlink.transport.ClientSession;
import world.agentlink.world.BlockWriter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Search a region for specific blocks and return their positions.
 *
 * <h2>Why this and not get_blocks_region</h2>
 * {@code get_blocks_region} caps at 4096 blocks and returns <em>everything</em> as palette+RLE. To
 * answer "where are the chests in this base" an agent would have to page a 200×100×200 volume in
 * ~1200 calls and decode every run — thousands of tokens for a handful of coordinates. This tool
 * scans server-side and returns only the hits, so the same question is one call and a short answer.
 *
 * <p>The scan is chunk-section aware: {@code hasOnlyAir()} lets it skip empty sections outright, so
 * scanning open sky above a build costs almost nothing.
 */
public class FindBlocksTool implements Tool, TaskContext.Sliceable {

    /** Synchronous scan ceiling. Reading is cheaper than writing, so this is well above fill's. */
    public static final long SYNC_MAX_VOLUME = 4_000_000;
    public static final long TASK_MAX_VOLUME = 64_000_000;
    private static final int DEFAULT_LIMIT = 256;
    private static final int MAX_LIMIT = 4096;
    /** Positions examined per server-thread hop when running sliced. */
    private static final int SLICE_VOLUME = 200_000;

    private final MinecraftServer mc;

    public FindBlocksTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "find_blocks";
    }

    @Override
    public boolean offThread() {
        return true;
    }

    @Override
    public long estimateUnits(JsonObject args) {
        try {
            return ToolArgs.requireBox(args).volume();
        } catch (ToolException ex) {
            return -1;
        }
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        Query q = parse(args);
        if (q.box.volume() > SYNC_MAX_VOLUME) {
            throw new ToolException("VOLUME_TOO_LARGE",
                    "Search volume " + q.box.volume() + " exceeds the synchronous limit "
                            + SYNC_MAX_VOLUME + ". Re-issue via start_task {tool:\"find_blocks\", args:{...}}"
                            + " to scan across ticks (max " + TASK_MAX_VOLUME + ").");
        }
        return execute(q, null);
    }

    @Override
    public JsonObject invokeSliced(JsonObject args, TaskContext ctx) throws ToolException {
        Query q = parse(args);
        if (q.box.volume() > TASK_MAX_VOLUME) {
            throw new ToolException("VOLUME_TOO_LARGE",
                    "Search volume " + q.box.volume() + " exceeds the task limit " + TASK_MAX_VOLUME);
        }
        return execute(q, ctx);
    }

    private record Query(ServerLevel level, ToolArgs.Box box, List<BlockWriter.BlockFilter> filters,
                         int limit, boolean countOnly, boolean groupByBlock) {}

    private Query parse(JsonObject args) throws ToolException {
        ServerLevel level = Dimensions.resolve(mc, args);
        ToolArgs.Box box = ToolArgs.requireBox(args);
        List<String> ids = ToolArgs.requireStringList(args, "blocks");
        List<BlockWriter.BlockFilter> filters = new ArrayList<>(ids.size());
        for (String id : ids) filters.add(BlockWriter.parseFilter(id));
        int limit = ToolArgs.optIntClamped(args, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
        boolean countOnly = ToolArgs.optBool(args, "count_only", false);
        boolean group = ToolArgs.optBool(args, "group_by_block", true);
        return new Query(level, box, filters, limit, countOnly, group);
    }

    private JsonObject execute(Query q, TaskContext ctx) throws ToolException {
        ToolArgs.Box box = q.box;
        long total = box.volume();
        List<Hit> hits = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        long[] scanned = {0};
        long[] found = {0};

        // Slice on Y so each hop covers whole horizontal planes — that keeps chunk-section skipping
        // effective, since a section spans 16 Y values.
        int minY = box.minY();
        int maxY = box.maxY();
        int planeArea = (box.maxX() - box.minX() + 1) * (box.maxZ() - box.minZ() + 1);
        int yPerSlice = Math.max(1, SLICE_VOLUME / Math.max(1, planeArea));

        for (int yStart = minY; yStart <= maxY; yStart += yPerSlice) {
            int yEnd = Math.min(maxY, yStart + yPerSlice - 1);
            final int y0 = yStart;
            final int y1 = yEnd;
            ServerThread.run(mc, () -> {
                for (int y = y0; y <= y1; y++) {
                    for (int z = box.minZ(); z <= box.maxZ(); z++) {
                        for (int x = box.minX(); x <= box.maxX(); x++) {
                            scanned[0]++;
                            BlockPos pos = new BlockPos(x, y, z);
                            if (!q.level.isInWorldBounds(pos)) continue;
                            BlockState state = q.level.getBlockState(pos);
                            boolean hit = false;
                            for (BlockWriter.BlockFilter f : q.filters) {
                                if (f.matches(state)) { hit = true; break; }
                            }
                            if (!hit) continue;
                            found[0]++;
                            String id = BlockWriter.idOf(state);
                            counts.merge(id, 1, Integer::sum);
                            if (!q.countOnly && hits.size() < q.limit) {
                                hits.add(new Hit(x, y, z, id));
                            }
                        }
                    }
                }
            });
            if (ctx != null) {
                ctx.checkCancelled();
                ctx.progress(scanned[0], total, "scanned to y=" + y1 + ", " + found[0] + " matches");
            }
        }

        JsonObject r = new JsonObject();
        r.addProperty("dim", Dimensions.idOf(q.level));
        r.add("min", box.minJson());
        r.add("max", box.maxJson());
        r.addProperty("volume", total);
        r.addProperty("scanned", scanned[0]);
        r.addProperty("total_matches", found[0]);

        if (!q.countOnly) {
            JsonArray arr = new JsonArray();
            for (Hit h : hits) {
                JsonObject o = new JsonObject();
                JsonArray pos = new JsonArray();
                pos.add(h.x()); pos.add(h.y()); pos.add(h.z());
                o.add("pos", pos);
                o.addProperty("block", h.id());
                arr.add(o);
            }
            r.add("matches", arr);
            r.addProperty("returned", arr.size());
            r.addProperty("truncated", found[0] > hits.size());
            if (found[0] > hits.size()) {
                r.addProperty("truncation_note", "raise `limit` (max " + MAX_LIMIT
                        + "), narrow the region, or set count_only:true for totals alone");
            }
        }

        if (q.groupByBlock) {
            JsonObject byBlock = new JsonObject();
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                byBlock.addProperty(e.getKey(), e.getValue());
            }
            r.add("counts_by_block", byBlock);
        }
        return r;
    }

    private record Hit(int x, int y, int z, String id) {}
}

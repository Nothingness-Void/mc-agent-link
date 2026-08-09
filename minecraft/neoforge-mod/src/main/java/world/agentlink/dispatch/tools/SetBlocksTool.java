package world.agentlink.dispatch.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import world.agentlink.config.AgentLinkConfig;
import world.agentlink.dispatch.ServerThread;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolArgs;
import world.agentlink.dispatch.ToolException;
import world.agentlink.sandbox.BuildZones;
import world.agentlink.task.TaskContext;
import world.agentlink.transport.ClientSession;
import world.agentlink.world.BlockWriter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Write an arbitrary set of positions in one undoable operation.
 *
 * <p>{@code fill_blocks} covers boxes; real builds are not boxes. Without this an agent building a
 * spiral staircase would issue one call per block — hundreds of round trips, hundreds of audit
 * lines, hundreds of undo entries, and no atomicity. Here the whole shape is one call and one undo
 * step.
 *
 * <h2>Two input shapes</h2>
 * <pre>
 * // explicit, mixed blocks
 * blocks: [ {pos:[10,64,10], block:"stone"}, {pos:[10,65,10], block:"oak_slab[type=top]"} ]
 *
 * // palette + positions, for when many positions share few blocks
 * palette: ["stone", "oak_planks"]
 * blocks:  [ {pos:[10,64,10], p:0}, {pos:[11,64,10], p:1} ]
 * </pre>
 * The palette form exists because the explicit form repeats the block id on every entry, and for a
 * few thousand positions that dominates the request body — which the agent pays for in tokens.
 */
public class SetBlocksTool implements Tool, TaskContext.Sliceable {

    /** One-tick ceiling; above this use start_task. Lower than fill because entries are parsed too. */
    public static final int SYNC_MAX_BLOCKS = 20_000;
    public static final int TASK_MAX_BLOCKS = 1_000_000;
    /**
     * Above this many entries we skip the footprint scan in {@link #declareScope}. Walking a
     * million-entry array on the transport thread to decide an approval question is not worth it, and
     * declining to declare simply means the call goes through the normal prompt.
     */
    private static final int SCOPE_SCAN_MAX = 50_000;

    private final MinecraftServer mc;

    public SetBlocksTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "set_blocks";
    }

    @Override
    public boolean offThread() {
        return true;
    }

    /**
     * The footprint is the bounding box of every listed position. A build zone must contain all of
     * it — a scattered set that pokes outside the zone prompts, as it should.
     */
    /**
     * The footprint is the bounding box of every listed position. A build zone must contain all of
     * it — a scattered set that pokes outside the zone prompts, as it should.
     *
     * <p>This runs on the transport thread before the approval decision, so it walks the array once
     * and does nothing else. Above {@link #SCOPE_SCAN_MAX} entries we decline to compute a footprint
     * rather than spend the time: no declaration means no exemption, which errs toward asking.
     */
    @Override
    public void declareScope(JsonObject args) {
        try {
            JsonArray arr = ToolArgs.requireArray(args, "blocks");
            if (arr.isEmpty() || arr.size() > SCOPE_SCAN_MAX) return;
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) return;
                ToolArgs.IntPos p = ToolArgs.requireIntPos(el.getAsJsonObject(), "pos");
                minX = Math.min(minX, p.x()); maxX = Math.max(maxX, p.x());
                minY = Math.min(minY, p.y()); maxY = Math.max(maxY, p.y());
                minZ = Math.min(minZ, p.z()); maxZ = Math.max(maxZ, p.z());
            }
            BuildZones.declareScope(args, ToolArgs.optString(args, "dim", Dimensions.DEFAULT),
                    new ToolArgs.Box(minX, minY, minZ, maxX, maxY, maxZ));
        } catch (ToolException ignored) {
        }
    }

    @Override
    public long estimateUnits(JsonObject args) {
        try {
            return ToolArgs.requireArray(args, "blocks").size();
        } catch (ToolException ex) {
            return -1;
        }
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        Plan plan = plan(args);
        if (plan.entries.size() > SYNC_MAX_BLOCKS) {
            throw new ToolException("VOLUME_TOO_LARGE",
                    "blocks length " + plan.entries.size() + " exceeds the synchronous limit "
                            + SYNC_MAX_BLOCKS + ". Re-issue via start_task {tool:\"set_blocks\", args:{...}}"
                            + " to slice it across ticks (max " + TASK_MAX_BLOCKS + ").");
        }
        return ServerThread.call(mc, () -> execute(plan, null));
    }

    @Override
    public JsonObject invokeSliced(JsonObject args, TaskContext ctx) throws ToolException {
        Plan plan = plan(args);
        if (plan.entries.size() > TASK_MAX_BLOCKS) {
            throw new ToolException("VOLUME_TOO_LARGE",
                    "blocks length " + plan.entries.size() + " exceeds the task limit " + TASK_MAX_BLOCKS);
        }
        return execute(plan, ctx);
    }

    private record Entry(BlockPos pos, BlockWriter.ParsedBlock block) {}

    private record Plan(ServerLevel level, List<Entry> entries, String label) {}

    private Plan plan(JsonObject args) throws ToolException {
        ServerLevel level = Dimensions.resolve(mc, args);
        JsonArray arr = ToolArgs.requireArray(args, "blocks");
        if (arr.isEmpty()) throw new ToolException("INVALID_ARGS", "blocks is empty");

        // Optional palette. Parsing each distinct spec once matters: BlockStateParser is not cheap
        // and a 20k-entry list would otherwise re-parse the same handful of ids 20k times.
        List<BlockWriter.ParsedBlock> palette = new ArrayList<>();
        List<String> paletteIds = ToolArgs.optStringList(args, "palette");
        if (paletteIds != null) {
            for (String spec : paletteIds) palette.add(BlockWriter.parseBlock(spec));
        }

        Map<String, BlockWriter.ParsedBlock> cache = new HashMap<>();
        List<Entry> entries = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            JsonElement el = arr.get(i);
            if (!el.isJsonObject()) {
                throw new ToolException("INVALID_ARGS", "blocks[" + i + "] must be an object");
            }
            JsonObject o = el.getAsJsonObject();
            ToolArgs.IntPos p = ToolArgs.requireIntPos(o, "pos");
            BlockWriter.ParsedBlock block;
            if (o.has("p") && !o.get("p").isJsonNull()) {
                int idx = o.get("p").getAsInt();
                if (palette.isEmpty()) {
                    throw new ToolException("INVALID_ARGS",
                            "blocks[" + i + "] uses palette index p but no palette was supplied");
                }
                if (idx < 0 || idx >= palette.size()) {
                    throw new ToolException("INVALID_ARGS",
                            "blocks[" + i + "].p=" + idx + " is out of range (palette size " + palette.size() + ")");
                }
                block = palette.get(idx);
            } else {
                String spec = ToolArgs.requireString(o, "block");
                BlockWriter.ParsedBlock cached = cache.get(spec);
                if (cached == null) {
                    cached = BlockWriter.parseBlock(spec);
                    cache.put(spec, cached);
                }
                block = cached;
            }
            entries.add(new Entry(new BlockPos(p.x(), p.y(), p.z()), block));
        }
        return new Plan(level, entries, "set_blocks " + entries.size() + " positions");
    }

    private JsonObject execute(Plan plan, TaskContext ctx) throws ToolException {
        int perTick = sliceSize();
        BlockWriter.Batch batch = new BlockWriter.Batch(plan.level, plan.label, true);
        int done = 0;
        try {
            List<Entry> slice = new ArrayList<>(Math.min(perTick, plan.entries.size()));
            for (Entry e : plan.entries) {
                slice.add(e);
                if (slice.size() >= perTick) {
                    flush(batch, slice);
                    done += slice.size();
                    slice.clear();
                    if (ctx != null) {
                        ctx.checkCancelled();
                        ctx.progress(done, plan.entries.size(), "wrote " + batch.changed() + " blocks");
                    }
                }
            }
            if (!slice.isEmpty()) {
                flush(batch, slice);
                done += slice.size();
            }
        } finally {
            if (ctx == null) {
                batch.commit();
            } else {
                try {
                    ServerThread.call(mc, batch::commit);
                } catch (ToolException ignored) {
                    // Server went away mid-commit; the undo entry is lost but the write stands.
                }
            }
        }

        JsonObject r = BlockWriter.describe(batch, null);
        r.addProperty("requested", plan.entries.size());
        r.addProperty("processed", done);
        return r;
    }

    private void flush(BlockWriter.Batch batch, List<Entry> slice) throws ToolException {
        List<Entry> copy = new ArrayList<>(slice);
        ServerThread.run(mc, () -> {
            for (Entry e : copy) batch.set(e.pos(), e.block().state(), e.block().nbt());
            // Neighbour updates go out with their own slice rather than piling up for the end.
            batch.flushUpdates();
        });
    }

    private int sliceSize() {
        AgentLinkConfig.Snapshot cfg = AgentLinkConfig.get();
        return cfg == null ? 8000 : cfg.taskBlocksPerTick();
    }
}

package world.agentlink.dispatch.tools;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
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
import java.util.List;

/**
 * Fill a cuboid, optionally only where the existing block matches a filter. Undoable.
 *
 * <p>This is the agent's main building primitive and works without WorldEdit. Modes:
 * <ul>
 *   <li>default — fill every position;</li>
 *   <li>{@code replace} — only positions whose current block matches (one id or a list);</li>
 *   <li>{@code hollow} — fill the shell, leave the interior untouched;</li>
 *   <li>{@code outline} — like hollow but the interior is cleared to air.</li>
 * </ul>
 *
 * <h2>Volume limits and slicing</h2>
 * A synchronous call is capped at {@link #SYNC_MAX_VOLUME} because everything in one
 * {@code invoke} happens inside a single tick — a million-block fill there would freeze the server
 * for seconds. Larger regions go through {@code start_task}, where {@link #invokeSliced} spreads the
 * work across ticks at {@code tasks.blocks_per_tick} and reports progress. The error message for an
 * over-limit synchronous call says exactly that, so the agent can retry correctly instead of
 * bisecting the region itself.
 */
public class FillBlocksTool implements Tool, TaskContext.Sliceable {

    /** One-tick ceiling. Above this, ticks visibly stall, so we route to the task system instead. */
    public static final long SYNC_MAX_VOLUME = 32_768;
    /** Absolute ceiling even for a sliced task — keeps undo snapshots and runtimes sane. */
    public static final long TASK_MAX_VOLUME = 4_000_000;

    private final MinecraftServer mc;

    public FillBlocksTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "fill_blocks";
    }

    /** Slicing hops onto the server thread per batch, so the body itself belongs on a worker. */
    @Override
    public boolean offThread() {
        return true;
    }

    @Override
    public void declareScope(JsonObject args) {
        try {
            ToolArgs.Box box = ToolArgs.requireBox(args);
            BuildZones.declareScope(args, ToolArgs.optString(args, "dim", Dimensions.DEFAULT), box);
        } catch (ToolException ignored) {
        }
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
        Plan plan = plan(args);
        if (plan.box.volume() > SYNC_MAX_VOLUME) {
            throw new ToolException("VOLUME_TOO_LARGE",
                    "Volume " + plan.box.volume() + " exceeds the synchronous limit " + SYNC_MAX_VOLUME
                            + ". Re-issue via start_task {tool:\"fill_blocks\", args:{...}} — it slices the"
                            + " edit across ticks and reports progress (max " + TASK_MAX_VOLUME + ").");
        }
        // Synchronous path: one server-thread hop for the whole region.
        return ServerThread.call(mc, () -> execute(plan, null));
    }

    @Override
    public JsonObject invokeSliced(JsonObject args, TaskContext ctx) throws ToolException {
        Plan plan = plan(args);
        if (plan.box.volume() > TASK_MAX_VOLUME) {
            throw new ToolException("VOLUME_TOO_LARGE",
                    "Volume " + plan.box.volume() + " exceeds the task limit " + TASK_MAX_VOLUME);
        }
        return execute(plan, ctx);
    }

    /** Parsed, validated request. Built off-thread; contains no world references. */
    private record Plan(ServerLevel level, ToolArgs.Box box, BlockWriter.ParsedBlock block,
                        List<BlockWriter.BlockFilter> replace, String mode, String label) {}

    private Plan plan(JsonObject args) throws ToolException {
        ServerLevel level = Dimensions.resolve(mc, args);
        ToolArgs.Box box = ToolArgs.requireBox(args);
        String spec = ToolArgs.requireString(args, "block");
        BlockWriter.ParsedBlock parsed = BlockWriter.parseBlock(spec);
        String mode = ToolArgs.optEnum(args, "mode", "replace_all");
        if (!List.of("replace_all", "hollow", "outline").contains(mode)) {
            throw new ToolException("INVALID_ARGS",
                    "mode must be replace_all, hollow, or outline (got \"" + mode + "\")");
        }

        List<BlockWriter.BlockFilter> filters = new ArrayList<>();
        List<String> replaceIds = ToolArgs.optStringList(args, "replace");
        if (replaceIds != null) {
            for (String id : replaceIds) filters.add(BlockWriter.parseFilter(id));
        }

        String label = "fill_blocks " + spec + " " + box.volume() + " blocks"
                + (filters.isEmpty() ? "" : " replace=" + replaceIds);
        return new Plan(level, box, parsed, filters, mode, label);
    }

    /**
     * Perform the fill. When {@code ctx} is null we're inside a single server-thread call; when it
     * is present we're on a worker and hop on-thread per slice.
     */
    private JsonObject execute(Plan plan, TaskContext ctx) throws ToolException {
        ToolArgs.Box box = plan.box;
        long total = box.volume();
        int perTick = sliceSize();

        // The batch holds undo data across every slice so the whole fill undoes as one operation.
        BlockWriter.Batch batch = new BlockWriter.Batch(plan.level, plan.label, true);
        long processed = 0;

        try {
            // Iterate y-z-x to match get_blocks_region's ordering, so an agent that read a region
            // and computed a diff can write it back in the same sweep order.
            List<BlockPos> slice = new ArrayList<>(Math.min(perTick, (int) Math.min(total, perTick)));
            for (int y = box.minY(); y <= box.maxY(); y++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    for (int x = box.minX(); x <= box.maxX(); x++) {
                        if (!wanted(plan, box, x, y, z)) {
                            processed++;
                            continue;
                        }
                        slice.add(new BlockPos(x, y, z));
                        if (slice.size() >= perTick) {
                            processed += flush(plan, batch, slice, ctx);
                            slice.clear();
                            if (ctx != null) {
                                ctx.checkCancelled();
                                ctx.progress(processed, total,
                                        "filled " + batch.changed() + " blocks");
                            }
                        }
                    }
                }
            }
            if (!slice.isEmpty()) processed += flush(plan, batch, slice, ctx);
        } finally {
            // Commit even on cancellation / failure: whatever we wrote must be undoable.
            commit(batch, ctx);
        }

        JsonObject r = BlockWriter.describe(batch, box);
        r.addProperty("mode", plan.mode);
        r.addProperty("block", BlockWriter.idOf(plan.block.state()));
        r.addProperty("processed", processed);
        if (!plan.replace.isEmpty()) {
            com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
            for (BlockWriter.BlockFilter f : plan.replace) arr.add(f.describe());
            r.add("replace", arr);
        }
        return r;
    }

    /**
     * Push undo data and issue the deferred neighbour updates. Must happen on the server thread, and
     * must happen even when the edit failed part-way — otherwise the blocks already written have no
     * undo entry.
     */
    private void commit(BlockWriter.Batch batch, TaskContext ctx) {
        if (ctx == null) {
            batch.commit();
            return;
        }
        try {
            ServerThread.call(mc, batch::commit);
        } catch (ToolException ex) {
            // Server thread is gone (shutdown). The write stands; the undo entry is lost.
        }
    }

    /** Write one slice on the server thread. Returns how many positions were consumed. */
    private long flush(Plan plan, BlockWriter.Batch batch, List<BlockPos> slice, TaskContext ctx)
            throws ToolException {
        List<BlockPos> copy = new ArrayList<>(slice);
        ToolArgs.Box box = plan.box;
        ServerThread.Body<Long> body = () -> {
            for (BlockPos pos : copy) {
                if (!plan.replace.isEmpty()) {
                    BlockState existing = plan.level.getBlockState(pos);
                    boolean hit = false;
                    for (BlockWriter.BlockFilter f : plan.replace) {
                        if (f.matches(existing)) { hit = true; break; }
                    }
                    if (!hit) continue;
                }
                // outline: shell gets the requested block, interior is cleared to air.
                boolean interior = "outline".equals(plan.mode) && !onShell(box, pos);
                if (interior) {
                    batch.set(pos, AIR, null);
                } else {
                    batch.set(pos, plan.block.state(), plan.block.nbt());
                }
            }
            // Drain neighbour updates with the slice that produced them, so a long fill spreads the
            // update cost across ticks instead of bursting at the end.
            batch.flushUpdates();
            return (long) copy.size();
        };
        // Inside a synchronous invoke we're already on-thread and this runs inline.
        return ServerThread.call(mc, body);
    }

    private static final BlockState AIR = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();

    private static boolean onShell(ToolArgs.Box box, BlockPos pos) {
        return pos.getX() == box.minX() || pos.getX() == box.maxX()
                || pos.getY() == box.minY() || pos.getY() == box.maxY()
                || pos.getZ() == box.minZ() || pos.getZ() == box.maxZ();
    }

    /**
     * Which positions the mode cares about. {@code hollow} skips the interior entirely;
     * {@code outline} still visits it (to clear it to air), so it wants everything.
     */
    private boolean wanted(Plan plan, ToolArgs.Box box, int x, int y, int z) {
        if ("replace_all".equals(plan.mode) || "outline".equals(plan.mode)) return true;
        return x == box.minX() || x == box.maxX()
                || y == box.minY() || y == box.maxY()
                || z == box.minZ() || z == box.maxZ();
    }

    private int sliceSize() {
        AgentLinkConfig.Snapshot cfg = AgentLinkConfig.get();
        return cfg == null ? 8000 : cfg.taskBlocksPerTick();
    }
}

package world.agentlink.dispatch.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.loading.FMLPaths;
import world.agentlink.config.AgentLinkConfig;
import world.agentlink.dispatch.ServerThread;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolArgs;
import world.agentlink.dispatch.ToolException;
import world.agentlink.sandbox.BuildZones;
import world.agentlink.task.TaskContext;
import world.agentlink.transport.ClientSession;
import world.agentlink.world.BlockWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Restore a region previously captured by {@code save_block_snapshot}, closing that loop.
 *
 * <p>{@code save_block_snapshot} has existed since 0.2.4 with a comment saying restore "should be a
 * separate, explicitly-approved feature". It is: admin-only by default, build-zone aware, and
 * undoable through the same stack as every other native write. A save with no restore is a backup
 * you can't use.
 *
 * <p>Snapshots are palette+RLE JSON under {@code config/agent-link/snapshots/}. Restore reads the
 * file, walks the runs in the recorded {@code y_z_x} order, and writes each position — offset by
 * {@code offset} when the caller wants the region placed somewhere other than where it was captured
 * (that turns snapshot/restore into a working copy-paste without WorldEdit).
 */
public class RestoreBlockSnapshotTool implements Tool, TaskContext.Sliceable {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    /** Same one-tick reasoning as fill_blocks. Bigger restores go through start_task. */
    public static final long SYNC_MAX_VOLUME = 32_768;

    private final MinecraftServer mc;

    public RestoreBlockSnapshotTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "restore_block_snapshot";
    }

    /** Reads a file and slices world writes; both want to be off the tick loop. */
    @Override
    public boolean offThread() {
        return true;
    }

    /**
     * The footprint comes from the snapshot file, not the args, so we read the header here. That is
     * file I/O on the transport thread — acceptable because it's a small local JSON read, and the
     * alternative (no declared scope) means build zones could never exempt a restore.
     */
    @Override
    public void declareScope(JsonObject args) {
        try {
            Header header = readHeader(ToolArgs.requireString(args, "name"));
            ToolArgs.IntPos offset = args.has("offset")
                    ? ToolArgs.requireIntPos(args, "offset")
                    : new ToolArgs.IntPos(0, 0, 0);
            ToolArgs.Box shifted = new ToolArgs.Box(
                    header.box.minX() + offset.x(), header.box.minY() + offset.y(), header.box.minZ() + offset.z(),
                    header.box.maxX() + offset.x(), header.box.maxY() + offset.y(), header.box.maxZ() + offset.z());
            String dim = ToolArgs.optString(args, "dim", header.dim);
            BuildZones.declareScope(args, dim, shifted);
        } catch (ToolException ignored) {
        }
    }

    @Override
    public long estimateUnits(JsonObject args) {
        try {
            return readHeader(ToolArgs.requireString(args, "name")).box.volume();
        } catch (ToolException ex) {
            return -1;
        }
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        Plan plan = plan(args);
        if (plan.box.volume() > SYNC_MAX_VOLUME) {
            throw new ToolException("VOLUME_TOO_LARGE",
                    "Snapshot volume " + plan.box.volume() + " exceeds the synchronous limit "
                            + SYNC_MAX_VOLUME + ". Re-issue via start_task {tool:\"restore_block_snapshot\","
                            + " args:{...}} to slice it across ticks.");
        }
        return execute(plan, null);
    }

    @Override
    public JsonObject invokeSliced(JsonObject args, TaskContext ctx) throws ToolException {
        return execute(plan(args), ctx);
    }

    // ------------------------------------------------------------------ snapshot file

    private record Header(String name, String dim, ToolArgs.Box box, Path path) {}

    private record Plan(ServerLevel level, Header header, ToolArgs.IntPos offset, ToolArgs.Box box,
                        List<BlockState> palette, JsonArray runs, boolean skipAir, String label) {}

    static Path snapshotDir() {
        return FMLPaths.CONFIGDIR.get().resolve("agent-link").resolve("snapshots");
    }

    private static JsonObject readSnapshot(String name) throws ToolException {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw new ToolException("INVALID_ARGS",
                    "name must match [A-Za-z0-9._-]{1,64}: " + name);
        }
        Path file = snapshotDir().resolve(name + ".json");
        if (!Files.exists(file)) {
            throw new ToolException("INVALID_ARGS",
                    "No snapshot named \"" + name + "\" — call list_snapshots for what's available");
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException("INTERNAL_ERROR", "read failed: " + e.getMessage());
        }
        JsonElement el;
        try {
            el = JsonParser.parseString(text);
        } catch (Exception e) {
            throw new ToolException("INTERNAL_ERROR", "snapshot is not valid JSON: " + e.getMessage());
        }
        if (!el.isJsonObject()) throw new ToolException("INTERNAL_ERROR", "snapshot is not a JSON object");
        return el.getAsJsonObject();
    }

    private Header readHeader(String name) throws ToolException {
        JsonObject snap = readSnapshot(name);
        return headerOf(name, snap);
    }

    private static Header headerOf(String name, JsonObject snap) throws ToolException {
        JsonObject min = requireObj(snap, "min");
        JsonObject max = requireObj(snap, "max");
        ToolArgs.Box box = new ToolArgs.Box(
                min.get("x").getAsInt(), min.get("y").getAsInt(), min.get("z").getAsInt(),
                max.get("x").getAsInt(), max.get("y").getAsInt(), max.get("z").getAsInt());
        String dim = snap.has("dim") ? snap.get("dim").getAsString() : Dimensions.DEFAULT;
        return new Header(name, dim, box, snapshotDir().resolve(name + ".json"));
    }

    private Plan plan(JsonObject args) throws ToolException {
        String name = ToolArgs.requireString(args, "name");
        JsonObject snap = readSnapshot(name);
        Header header = headerOf(name, snap);

        if (!"y_z_x".equals(snap.has("order") ? snap.get("order").getAsString() : "y_z_x")) {
            throw new ToolException("INTERNAL_ERROR",
                    "Unsupported snapshot iteration order: " + snap.get("order").getAsString());
        }

        // Palette entries are bare block ids as written by save_block_snapshot, so blockstate
        // properties are NOT preserved by that format. Restore therefore places default states;
        // we surface that rather than pretending the round-trip is lossless.
        JsonArray paletteArr = snap.has("palette") && snap.get("palette").isJsonArray()
                ? snap.getAsJsonArray("palette")
                : new JsonArray();
        List<BlockState> palette = new ArrayList<>(paletteArr.size());
        for (JsonElement item : paletteArr) {
            palette.add(BlockWriter.defaultStateOf(item.getAsString()));
        }
        if (palette.isEmpty()) throw new ToolException("INTERNAL_ERROR", "snapshot palette is empty");

        JsonArray runs = snap.has("runs") && snap.get("runs").isJsonArray()
                ? snap.getAsJsonArray("runs")
                : new JsonArray();
        if (runs.isEmpty()) throw new ToolException("INTERNAL_ERROR", "snapshot has no runs");

        ToolArgs.IntPos offset = args.has("offset")
                ? ToolArgs.requireIntPos(args, "offset")
                : new ToolArgs.IntPos(0, 0, 0);
        ServerLevel level = args.has("dim")
                ? Dimensions.resolve(mc, args)
                : Dimensions.byId(mc, header.dim);
        boolean skipAir = ToolArgs.optBool(args, "skip_air", false);

        ToolArgs.Box target = new ToolArgs.Box(
                header.box.minX() + offset.x(), header.box.minY() + offset.y(), header.box.minZ() + offset.z(),
                header.box.maxX() + offset.x(), header.box.maxY() + offset.y(), header.box.maxZ() + offset.z());

        String label = "restore_block_snapshot " + name
                + (offset.x() == 0 && offset.y() == 0 && offset.z() == 0
                        ? "" : " offset=" + offset.x() + "," + offset.y() + "," + offset.z());
        return new Plan(level, header, offset, target, palette, runs, skipAir, label);
    }

    // ------------------------------------------------------------------ execution

    private JsonObject execute(Plan plan, TaskContext ctx) throws ToolException {
        ToolArgs.Box src = plan.header.box;
        int width = src.maxX() - src.minX() + 1;
        int depth = src.maxZ() - src.minZ() + 1;
        long total = src.volume();
        int perTick = sliceSize();

        BlockWriter.Batch batch = new BlockWriter.Batch(plan.level, plan.label, true);
        long index = 0;
        long written = 0;
        List<Pending> slice = new ArrayList<>(perTick);

        try {
            for (JsonElement runEl : plan.runs) {
                JsonArray run = runEl.getAsJsonArray();
                int paletteIdx = run.get(0).getAsInt();
                int count = run.get(1).getAsInt();
                if (paletteIdx < 0 || paletteIdx >= plan.palette.size()) {
                    throw new ToolException("INTERNAL_ERROR",
                            "snapshot run references palette index " + paletteIdx + " out of range");
                }
                BlockState state = plan.palette.get(paletteIdx);
                boolean isAir = state.isAir();

                for (int n = 0; n < count; n++, index++) {
                    if (index >= total) break;
                    if (plan.skipAir && isAir) continue;
                    // Decode the linear index back to coords using the recorded y_z_x order.
                    long rem = index;
                    int y = (int) (rem / ((long) width * depth));
                    rem -= (long) y * width * depth;
                    int z = (int) (rem / width);
                    int x = (int) (rem - (long) z * width);
                    BlockPos pos = new BlockPos(
                            src.minX() + x + plan.offset.x(),
                            src.minY() + y + plan.offset.y(),
                            src.minZ() + z + plan.offset.z());
                    slice.add(new Pending(pos, state));

                    if (slice.size() >= perTick) {
                        written += flush(batch, slice);
                        slice.clear();
                        if (ctx != null) {
                            ctx.checkCancelled();
                            ctx.progress(index, total, "restored " + batch.changed() + " blocks");
                        }
                    }
                }
            }
            if (!slice.isEmpty()) written += flush(batch, slice);
        } finally {
            try {
                ServerThread.call(mc, batch::commit);
            } catch (ToolException ignored) {
            }
        }

        JsonObject r = BlockWriter.describe(batch, plan.box);
        r.addProperty("snapshot", plan.header.name());
        r.addProperty("source_dim", plan.header.dim);
        r.add("offset", plan.offset.toJson());
        r.addProperty("positions_written", written);
        r.addProperty("skip_air", plan.skipAir);
        r.addProperty("palette_size", plan.palette.size());
        r.addProperty("note", "save_block_snapshot stores bare block ids, so blockstate properties"
                + " (log axis, stair facing, ...) restore as defaults and block entities are not"
                + " captured. Use undo_blocks to reverse this restore.");
        return r;
    }

    private record Pending(BlockPos pos, BlockState state) {}

    private long flush(BlockWriter.Batch batch, List<Pending> slice) throws ToolException {
        List<Pending> copy = new ArrayList<>(slice);
        return ServerThread.call(mc, () -> {
            long n = 0;
            for (Pending p : copy) {
                batch.set(p.pos(), p.state());
                n++;
            }
            // Spread neighbour updates across slices instead of bursting at commit time.
            batch.flushUpdates();
            return n;
        });
    }

    private static JsonObject requireObj(JsonObject o, String key) throws ToolException {
        if (!o.has(key) || !o.get(key).isJsonObject()) {
            throw new ToolException("INTERNAL_ERROR", "snapshot is missing object field: " + key);
        }
        return o.getAsJsonObject(key);
    }

    private int sliceSize() {
        AgentLinkConfig.Snapshot cfg = AgentLinkConfig.get();
        return cfg == null ? 8000 : cfg.taskBlocksPerTick();
    }
}

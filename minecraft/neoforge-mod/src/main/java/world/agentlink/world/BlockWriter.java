package world.agentlink.world;

import com.google.gson.JsonObject;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import world.agentlink.dispatch.ToolArgs;
import world.agentlink.dispatch.ToolException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Native block writing with an undo stack — no WorldEdit required.
 *
 * <h2>Why not just use WorldEdit</h2>
 * The WE bridge stays (it is faster on huge selections and has richer brushes), but it is optional
 * software the operator may not have installed, and until now every mutating capability the agent
 * had was either "shell out to {@code run_console_command /setblock}" (no undo, one block per
 * console call, output parsing) or "requires WorldEdit". This class gives the agent a first-class
 * write path that always exists.
 *
 * <h2>Undo</h2>
 * Each operation captures the prior {@link BlockState} (plus block-entity NBT when present) for
 * every position it actually changed, and pushes that as one {@link Change} onto a bounded stack.
 * {@code undo_blocks} pops and reapplies. The stack is in-memory only: a restart drops it, which is
 * deliberate — replaying an edit against a world that may have been modified offline is worse than
 * losing the undo.
 *
 * <h2>Flags</h2>
 * Writes use {@link Block#UPDATE_CLIENTS} by default and deliberately skip neighbour updates during
 * bulk fills, then run a single relight/notify pass at the end. Sending a neighbour update per block
 * in a 100k fill is what makes naive {@code /setblock} loops melt a server.
 */
public final class BlockWriter {

    /** How many operations we keep for undo. Each entry holds one snapshot of what it overwrote. */
    private static final int UNDO_STACK_MAX = 32;
    /** Per-change position cap. Above this we still perform the edit but record no undo data. */
    public static final int UNDO_CAPTURE_MAX = 300_000;

    private static final Deque<Change> UNDO_STACK = new ArrayDeque<>();
    private static final Object UNDO_LOCK = new Object();
    private static final AtomicLong OPERATION_SEQUENCE = new AtomicLong();

    private BlockWriter() {}

    /** One recorded block, enough to put it back exactly as it was. */
    public record PriorBlock(BlockPos pos, BlockState state, CompoundTag blockEntityNbt) {}

    /** One undoable operation. {@code label} is what shows up in {@code undo_blocks} output. */
    public static final class Change {
        private final String operationId;
        private final String label;
        private final ServerLevel level;
        private final List<PriorBlock> prior;
        private final long createdAtMs;
        private final boolean truncated;

        Change(String operationId, String label, ServerLevel level, List<PriorBlock> prior,
               boolean truncated) {
            this.operationId = operationId;
            this.label = label;
            this.level = level;
            this.prior = prior;
            this.createdAtMs = System.currentTimeMillis();
            this.truncated = truncated;
        }

        public String operationId() { return operationId; }
        public String label() { return label; }
        public int size() { return prior.size(); }
        public long createdAtMs() { return createdAtMs; }
        public boolean truncated() { return truncated; }
        public String dimension() { return level.dimension().location().toString(); }
    }

    /**
     * Accumulates an edit: callers push positions through {@link #set}, and the batch records what
     * it overwrote so the whole thing lands on the undo stack as one unit.
     */
    public static final class Batch {
        private final ServerLevel level;
        private final String label;
        private final List<PriorBlock> prior = new ArrayList<>();
        /**
         * Positions written but not yet neighbour-updated. Kept separate from {@link #prior} so a
         * long sliced edit can issue its updates incrementally — see {@link #flushUpdates()}.
         */
        private final List<BlockPos> pendingUpdates = new ArrayList<>();
        private final boolean captureUndo;
        private int changed;
        private int skipped;
        private boolean truncated;
        private final String operationId;
        private boolean finished;

        public Batch(ServerLevel level, String label, boolean captureUndo) {
            this.level = level;
            this.label = label;
            this.captureUndo = captureUndo;
            this.operationId = nextOperationId();
        }

        public ServerLevel level() { return level; }
        public String operationId() { return operationId; }
        public int changed() { return changed; }
        public int skipped() { return skipped; }

        /**
         * Write one block. Returns true when the world actually changed.
         *
         * <p>Identical states are skipped rather than rewritten — that keeps the undo snapshot
         * honest (it only contains blocks we really touched) and avoids pointless chunk dirtying.
         */
        public boolean set(BlockPos pos, BlockState state, CompoundTag blockEntityNbt) {
            if (finished) throw new IllegalStateException("batch is already finished");
            if (!level.isInWorldBounds(pos)) {
                skipped++;
                return false;
            }
            BlockState existing = level.getBlockState(pos);
            boolean sameState = existing == state;
            if (sameState && blockEntityNbt == null) {
                skipped++;
                return false;
            }

            if (captureUndo) {
                if (prior.size() < UNDO_CAPTURE_MAX) {
                    BlockEntity existingBe = level.getBlockEntity(pos);
                    CompoundTag beNbt = existingBe == null ? null
                            : existingBe.saveWithFullMetadata(level.registryAccess());
                    prior.add(new PriorBlock(pos.immutable(), existing, beNbt));
                } else {
                    truncated = true;
                }
            }

            // Clear a stale block entity first: setBlock over a container otherwise leaves the old
            // BE attached when the new state also wants one of the same type.
            if (!sameState && level.getBlockEntity(pos) != null) {
                level.removeBlockEntity(pos);
            }
            level.setBlock(pos, state, Block.UPDATE_CLIENTS);
            if (blockEntityNbt != null) applyBlockEntityNbt(level, pos, blockEntityNbt);
            pendingUpdates.add(pos.immutable());
            changed++;
            return true;
        }

        public boolean set(BlockPos pos, BlockState state) {
            return set(pos, state, null);
        }

        /**
         * Issue neighbour updates for everything written since the last flush.
         *
         * <p>Split out from {@link #commit()} so a sliced edit can drain it per slice. Deferring all
         * of them to the very end of a 500k-block fill would mean one enormous update burst in a
         * single tick — exactly the stall the slicing exists to avoid.
         */
        public int flushUpdates() {
            if (finished) throw new IllegalStateException("batch is already finished");
            if (pendingUpdates.isEmpty()) return 0;
            int n = pendingUpdates.size();
            for (BlockPos pos : pendingUpdates) {
                level.blockUpdated(pos, level.getBlockState(pos).getBlock());
            }
            pendingUpdates.clear();
            return n;
        }

        /**
         * Finish the batch: push undo data and issue any remaining neighbour updates.
         * Always call this, even on a partially-completed edit, or the undo entry is lost.
         */
        public Change commit() {
            if (finished) throw new IllegalStateException("batch is already finished");
            Change change = new Change(operationId, label, level, List.copyOf(prior), truncated);
            flushUpdates();
            finished = true;
            if (captureUndo && !prior.isEmpty()) push(change);
            return change;
        }

        /**
         * Abort the batch and restore everything written by it without adding an undo entry.
         * This is used by cooperative task cancellation so a cancelled high-level operation does
         * not leave an untracked half-built structure in the world.
         */
        public int abort() {
            if (finished) throw new IllegalStateException("batch is already finished");
            finished = true;
            pendingUpdates.clear();
            return restore(level, prior);
        }
    }

    // ------------------------------------------------------------------ undo stack

    private static void push(Change change) {
        synchronized (UNDO_LOCK) {
            UNDO_STACK.push(change);
            while (UNDO_STACK.size() > UNDO_STACK_MAX) UNDO_STACK.pollLast();
        }
    }

    public static int undoDepth() {
        synchronized (UNDO_LOCK) {
            return UNDO_STACK.size();
        }
    }

    /** Metadata for the {@code undo_blocks} listing, newest first. Does not mutate the stack. */
    public static List<Change> peekAll() {
        synchronized (UNDO_LOCK) {
            return new ArrayList<>(UNDO_STACK);
        }
    }

    public record UndoResult(int operationsUndone, int blocksRestored, int remainingDepth,
                             List<String> labels) {}

    public record OperationUndoResult(String operationId, String label, int blocksRestored,
                                      int remainingDepth) {}

    /**
     * Pop and reverse the most recent {@code steps} operations.
     *
     * <p>Restoration is not itself recorded for undo — a redo stack would need a snapshot of the
     * post-edit state too, and "undo then undo again to get back" is a confusing model for an
     * agent. Re-issue the original edit instead.
     */
    public static UndoResult undo(int steps) throws ToolException {
        if (steps <= 0) throw new ToolException("INVALID_ARGS", "steps must be > 0");
        int ops = 0;
        int blocks = 0;
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < steps; i++) {
            Change change;
            synchronized (UNDO_LOCK) {
                change = UNDO_STACK.pollFirst();
            }
            if (change == null) break;
            blocks += restore(change);
            labels.add(change.label());
            ops++;
        }
        return new UndoResult(ops, blocks, undoDepth(), labels);
    }

    /**
     * Reverse one named operation. Only the newest operation may be named explicitly; allowing a
     * middle entry to be removed would restore stale prior states over newer edits.
     */
    public static OperationUndoResult undoOperation(String operationId) throws ToolException {
        if (operationId == null || operationId.isBlank()) {
            throw new ToolException("INVALID_ARGS", "operation_id is required");
        }
        Change change;
        synchronized (UNDO_LOCK) {
            change = UNDO_STACK.peekFirst();
            if (change == null) {
                throw new ToolException("NOTHING_TO_UNDO", "The native block-write undo stack is empty");
            }
            if (!operationId.equals(change.operationId())) {
                boolean found = UNDO_STACK.stream().anyMatch(c -> operationId.equals(c.operationId()));
                throw new ToolException(found ? "OPERATION_NOT_TOP" : "NOT_FOUND",
                        found
                                ? "operation_id is not the newest native edit; undo newer operations first"
                                : "No native block operation matches operation_id: " + operationId);
            }
            UNDO_STACK.removeFirst();
        }
        int restored = restore(change);
        return new OperationUndoResult(change.operationId(), change.label(), restored, undoDepth());
    }

    private static int restore(Change change) {
        return restore(change.level, change.prior);
    }

    private static int restore(ServerLevel level, List<PriorBlock> prior) {
        int restored = 0;
        // Reverse order so overlapping writes within one batch unwind correctly.
        for (int i = prior.size() - 1; i >= 0; i--) {
            PriorBlock p = prior.get(i);
            if (level.getBlockEntity(p.pos()) != null) level.removeBlockEntity(p.pos());
            level.setBlock(p.pos(), p.state(), Block.UPDATE_CLIENTS);
            if (p.blockEntityNbt() != null) applyBlockEntityNbt(level, p.pos(), p.blockEntityNbt());
            restored++;
        }
        for (PriorBlock p : prior) {
            level.blockUpdated(p.pos(), p.state().getBlock());
        }
        return restored;
    }

    private static String nextOperationId() {
        long sequence = OPERATION_SEQUENCE.incrementAndGet();
        return "op-" + Long.toUnsignedString(System.currentTimeMillis(), 36)
                + "-" + Long.toUnsignedString(sequence, 36);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Parse a block spec into a state. Accepts the full vanilla command grammar, so
     * {@code minecraft:oak_stairs[facing=east,half=top]} and {@code chest{Items:[...]}} both work —
     * we hand it to {@link BlockStateParser}, the same parser {@code /setblock} uses.
     */
    public static ParsedBlock parseBlock(String spec) throws ToolException {
        if (spec == null || spec.isBlank()) throw new ToolException("INVALID_ARGS", "block is empty");
        try {
            BlockStateParser.BlockResult result = BlockStateParser.parseForBlock(
                    BuiltInRegistries.BLOCK.asLookup(), new StringReader(spec.trim()), true);
            return new ParsedBlock(result.blockState(), result.nbt());
        } catch (CommandSyntaxException e) {
            throw new ToolException("INVALID_ARGS", "Invalid block \"" + spec + "\": " + e.getMessage());
        }
    }

    public record ParsedBlock(BlockState state, CompoundTag nbt) {}

    /**
     * Parse a block spec used as a <em>filter</em> rather than a value. Bare ids match any
     * blockstate of that block ({@code oak_log} matches every axis), whereas a spec with explicit
     * properties matches only those. That asymmetry is what makes {@code fill_blocks(replace:...)}
     * behave the way an operator expects.
     */
    public static BlockFilter parseFilter(String spec) throws ToolException {
        if (spec == null || spec.isBlank()) throw new ToolException("INVALID_ARGS", "filter is empty");
        String trimmed = spec.trim();
        boolean hasProperties = trimmed.indexOf('[') >= 0;
        ParsedBlock parsed = parseBlock(trimmed);
        return new BlockFilter(parsed.state(), hasProperties);
    }

    /** A match predicate over block states. */
    public record BlockFilter(BlockState state, boolean exactProperties) {
        public boolean matches(BlockState candidate) {
            return exactProperties ? candidate == state : candidate.getBlock() == state.getBlock();
        }

        public String describe() {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            return id == null ? "?" : id.toString();
        }
    }

    /** Resolve a bare block id (no properties) to its default state. */
    public static BlockState defaultStateOf(String id) throws ToolException {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) throw new ToolException("INVALID_ARGS", "Invalid block id: " + id);
        if (!BuiltInRegistries.BLOCK.containsKey(rl)) {
            throw new ToolException("INVALID_ARGS", "Unknown block id: " + id);
        }
        Block block = BuiltInRegistries.BLOCK.get(rl);
        return block == null ? Blocks.AIR.defaultBlockState() : block.defaultBlockState();
    }

    public static String idOf(BlockState state) {
        ResourceLocation rl = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return rl == null ? "minecraft:unknown" : rl.toString();
    }

    /**
     * Write NBT into the block entity at {@code pos}. Vanilla requires the {@code x/y/z} fields to
     * agree with the position, so we overwrite them rather than trusting the caller's copy.
     */
    private static void applyBlockEntityNbt(ServerLevel level, BlockPos pos, CompoundTag nbt) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return;
        CompoundTag merged = nbt.copy();
        merged.putInt("x", pos.getX());
        merged.putInt("y", pos.getY());
        merged.putInt("z", pos.getZ());
        try {
            be.loadWithComponents(merged, level.registryAccess());
            be.setChanged();
        } catch (Exception ignored) {
            // A malformed NBT payload for this BE type: leave the block placed but un-populated
            // rather than failing the whole batch.
        }
    }

    /** Shared summary fields so every write tool reports the same shape. */
    public static JsonObject describe(Batch batch, ToolArgs.Box box) {
        JsonObject r = new JsonObject();
        r.addProperty("operation_id", batch.operationId());
        r.addProperty("dim", batch.level().dimension().location().toString());
        r.addProperty("changed", batch.changed());
        r.addProperty("skipped", batch.skipped());
        if (box != null) {
            r.add("min", box.minJson());
            r.add("max", box.maxJson());
            r.addProperty("volume", box.volume());
        }
        r.addProperty("undo_depth", undoDepth());
        return r;
    }
}

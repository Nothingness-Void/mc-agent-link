package world.agentlink.api;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import world.agentlink.dispatch.ToolException;
import world.agentlink.world.BlockWriter;

import java.util.List;

/** Native block parsing and undoable writes for addon code. */
public final class AgentBlockApi {

    public record ParsedBlock(BlockState state, net.minecraft.nbt.CompoundTag blockEntityNbt) {}
    public record EditResult(String label, String dimension, int changed, int skipped,
                             int undoDepth, boolean undoCaptured, String operationId) {}
    public record UndoResult(int operationsUndone, int blocksRestored, int remainingDepth,
                             List<String> labels) {}
    public record OperationUndoResult(String operationId, String label, int blocksRestored,
                                      int remainingDepth) {}

    // Addon calls are synchronous; keep the shared facade below the normal one-tick MCP cap.
    private static final long MAX_API_FILL_VOLUME = 32_768L;

    AgentBlockApi() {}

    /**
     * Start one undoable bulk edit. Callers must invoke the returned batch only on the server
     * thread. A batch may be flushed between slices and is committed exactly once at the end.
     */
    public Batch beginBatch(ServerLevel level, String label, boolean captureUndo)
            throws AgentApiException {
        if (level == null) throw new AgentApiException("INVALID_ARGS", "level is required");
        String safeLabel = label == null || label.isBlank() ? "addon_bulk_edit" : label;
        return new Batch(new BlockWriter.Batch(level, safeLabel, captureUndo), safeLabel, captureUndo);
    }

    /** Public wrapper around the shared undo-aware bulk writer. */
    public static final class Batch {
        private final BlockWriter.Batch delegate;
        private final String label;
        private final boolean captureUndo;
        private boolean committed;

        private Batch(BlockWriter.Batch delegate, String label, boolean captureUndo) {
            this.delegate = delegate;
            this.label = label;
            this.captureUndo = captureUndo;
        }

        public ServerLevel level() { return delegate.level(); }
        public String operationId() { return delegate.operationId(); }

        public boolean set(BlockPos pos, ParsedBlock block) throws AgentApiException {
            if (committed) throw new AgentApiException("INVALID_STATE", "batch is already committed");
            if (pos == null || block == null) {
                throw new AgentApiException("INVALID_ARGS", "pos and block are required");
            }
            return delegate.set(pos, block.state(), block.blockEntityNbt());
        }

        /** Drain neighbour updates for the current slice without closing the undo batch. */
        public int flushUpdates() throws AgentApiException {
            if (committed) throw new AgentApiException("INVALID_STATE", "batch is already finished");
            try {
                return delegate.flushUpdates();
            } catch (IllegalStateException ex) {
                throw new AgentApiException("INVALID_STATE", ex.getMessage(), ex);
            }
        }

        /** Push one undo entry and flush any remaining updates. */
        public EditResult commit() throws AgentApiException {
            if (committed) throw new AgentApiException("INVALID_STATE", "batch is already finished");
            try {
                delegate.commit();
                committed = true;
                return result(delegate, captureUndo, label);
            } catch (IllegalStateException ex) {
                throw new AgentApiException("INVALID_STATE", ex.getMessage(), ex);
            }
        }

        /** Restore all writes in this batch without pushing an undo entry. */
        public int abort() throws AgentApiException {
            if (committed) throw new AgentApiException("INVALID_STATE", "batch is already finished");
            try {
                int restored = delegate.abort();
                committed = true;
                return restored;
            } catch (IllegalStateException ex) {
                throw new AgentApiException("INVALID_STATE", ex.getMessage(), ex);
            }
        }

        public int changed() { return delegate.changed(); }
        public int skipped() { return delegate.skipped(); }
    }

    public ParsedBlock parse(String spec) throws AgentApiException {
        try {
            BlockWriter.ParsedBlock parsed = BlockWriter.parseBlock(spec);
            return new ParsedBlock(parsed.state(), parsed.nbt());
        } catch (ToolException ex) {
            throw new AgentApiException(ex.code(), ex.getMessage(), ex);
        }
    }

    public EditResult set(ServerLevel level, BlockPos pos, String blockSpec, String label,
                          boolean captureUndo) throws AgentApiException {
        if (level == null || pos == null) throw new AgentApiException("INVALID_ARGS", "level and pos are required");
        ParsedBlock block = parse(blockSpec);
        String safeLabel = label == null || label.isBlank() ? "addon_set_block" : label;
        BlockWriter.Batch batch = new BlockWriter.Batch(level, safeLabel, captureUndo);
        batch.set(pos, block.state(), block.blockEntityNbt());
        batch.commit();
        return result(batch, captureUndo, safeLabel);
    }

    public EditResult fill(ServerLevel level, BlockPos first, BlockPos second, String blockSpec,
                           String label, boolean captureUndo) throws AgentApiException {
        if (level == null || first == null || second == null) {
            throw new AgentApiException("INVALID_ARGS", "level and positions are required");
        }
        int minX = Math.min(first.getX(), second.getX());
        int minY = Math.min(first.getY(), second.getY());
        int minZ = Math.min(first.getZ(), second.getZ());
        int maxX = Math.max(first.getX(), second.getX());
        int maxY = Math.max(first.getY(), second.getY());
        int maxZ = Math.max(first.getZ(), second.getZ());
        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume > MAX_API_FILL_VOLUME) {
            throw new AgentApiException("INVALID_ARGS", "fill volume exceeds " + MAX_API_FILL_VOLUME);
        }
        ParsedBlock block = parse(blockSpec);
        String safeLabel = label == null || label.isBlank() ? "addon_fill_blocks" : label;
        BlockWriter.Batch batch = new BlockWriter.Batch(level, safeLabel, captureUndo);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    batch.set(new BlockPos(x, y, z), block.state(), block.blockEntityNbt());
                }
            }
        }
        batch.commit();
        return result(batch, captureUndo, safeLabel);
    }

    public UndoResult undo(int steps) throws AgentApiException {
        try {
            BlockWriter.UndoResult result = BlockWriter.undo(steps);
            return new UndoResult(result.operationsUndone(), result.blocksRestored(),
                    result.remainingDepth(), List.copyOf(result.labels()));
        } catch (ToolException ex) {
            throw new AgentApiException(ex.code(), ex.getMessage(), ex);
        }
    }

    /** Undo the newest native edit only when its operation id matches. */
    public OperationUndoResult undoOperation(String operationId) throws AgentApiException {
        try {
            BlockWriter.OperationUndoResult result = BlockWriter.undoOperation(operationId);
            return new OperationUndoResult(result.operationId(), result.label(),
                    result.blocksRestored(), result.remainingDepth());
        } catch (ToolException ex) {
            throw new AgentApiException(ex.code(), ex.getMessage(), ex);
        }
    }

    public int undoDepth() {
        return BlockWriter.undoDepth();
    }

    private static EditResult result(BlockWriter.Batch batch, boolean captureUndo, String label) {
        return new EditResult(label, batch.level().dimension().location().toString(),
                batch.changed(), batch.skipped(), BlockWriter.undoDepth(), captureUndo,
                batch.operationId());
    }
}

package world.agentlink.api;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;

/** Force-loaded chunk lifecycle operations shared by world-writing addons. */
public final class AgentChunkApi {

    AgentChunkApi() {}

    /** Add or remove one forced chunk. Returns whether the forced state changed. */
    public boolean setForced(ServerLevel level, int chunkX, int chunkZ, boolean forced)
            throws AgentApiException {
        requireLevel(level);
        return level.setChunkForced(chunkX, chunkZ, forced);
    }

    /** Snapshot all currently force-loaded chunks in a dimension. */
    public List<ChunkPos> forced(ServerLevel level) throws AgentApiException {
        requireLevel(level);
        List<ChunkPos> result = new ArrayList<>();
        for (long packed : level.getForcedChunks()) {
            result.add(new ChunkPos(packed));
        }
        return List.copyOf(result);
    }

    /** Release every force-loaded chunk in a dimension and return the number changed. */
    public int clear(ServerLevel level) throws AgentApiException {
        requireLevel(level);
        int changed = 0;
        for (ChunkPos pos : forced(level)) {
            if (level.setChunkForced(pos.x, pos.z, false)) changed++;
        }
        return changed;
    }

    private static void requireLevel(ServerLevel level) throws AgentApiException {
        if (level == null) throw new AgentApiException("INVALID_ARGS", "level is required");
    }
}

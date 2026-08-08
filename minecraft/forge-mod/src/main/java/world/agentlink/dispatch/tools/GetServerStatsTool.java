package world.agentlink.dispatch.tools;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import world.agentlink.dispatch.Tool;
import world.agentlink.transport.ClientSession;

public class GetServerStatsTool implements Tool {
    private final MinecraftServer mc;

    public GetServerStatsTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "get_server_stats";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) {
        // tickTimes is nanos per tick over the last 100 ticks
        long[] tickTimes = mc.tickTimes;
        double avgNanos = 0;
        int samples = 0;
        for (long t : tickTimes) {
            if (t <= 0) continue;
            avgNanos += t;
            samples++;
        }
        avgNanos /= Math.max(1, samples);
        double mspt = avgNanos / 1_000_000.0;
        double tps = Math.min(1000.0 / Math.max(mspt, 50.0), 20.0);

        Runtime rt = Runtime.getRuntime();
        long memUsed = rt.totalMemory() - rt.freeMemory();
        long memMax = rt.maxMemory();

        int loadedChunks = 0;
        for (ServerLevel level : mc.getAllLevels()) {
            loadedChunks += level.getChunkSource().getLoadedChunksCount();
        }

        JsonObject r = new JsonObject();
        r.addProperty("tps", round2(tps));
        r.addProperty("mspt", round2(mspt));
        r.addProperty("tick_samples", samples);
        r.addProperty("tick_window_capacity", tickTimes.length);
        r.addProperty("mem_used_mb", memUsed / (1024 * 1024));
        r.addProperty("mem_max_mb", memMax / (1024 * 1024));
        r.addProperty("loaded_chunks", loadedChunks);
        r.addProperty("online", mc.getPlayerCount());
        r.addProperty("max_players", mc.getMaxPlayers());
        return r;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}

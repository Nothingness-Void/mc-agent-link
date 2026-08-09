package world.agentlink.dispatch.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import world.agentlink.dispatch.Tool;
import world.agentlink.transport.ClientSession;

public class ListDimensionsTool implements Tool {

    private final MinecraftServer mc;

    public ListDimensionsTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "list_dimensions";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) {
        JsonArray arr = new JsonArray();
        for (ServerLevel level : mc.getAllLevels()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", level.dimension().location().toString());
            o.addProperty("min_y", level.getMinBuildHeight());
            o.addProperty("height", level.getHeight());
            o.addProperty("logical_height", level.getLogicalHeight());
            o.addProperty("sea_level", level.getSeaLevel());
            o.addProperty("loaded_chunks", level.getChunkSource().getLoadedChunksCount());
            arr.add(o);
        }
        JsonObject r = new JsonObject();
        r.add("dimensions", arr);
        r.addProperty("count", arr.size());
        return r;
    }
}

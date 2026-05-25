package world.agentlink.dispatch.tools;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

public class GetBiomeTool implements Tool {

    private final MinecraftServer mc;

    public GetBiomeTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "get_biome";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        ServerLevel level = GetBlockTool.resolveDimension(mc, args);
        int x = GetBlockTool.requireInt(args, "x");
        int y = GetBlockTool.requireInt(args, "y");
        int z = GetBlockTool.requireInt(args, "z");
        BlockPos pos = new BlockPos(x, y, z);
        var holder = level.getBiome(pos);

        JsonObject r = new JsonObject();
        JsonObject p = new JsonObject();
        p.addProperty("x", x);
        p.addProperty("y", y);
        p.addProperty("z", z);
        r.add("pos", p);
        r.addProperty("dim", level.dimension().location().toString());
        holder.unwrapKey().ifPresent(k -> r.addProperty("biome", k.location().toString()));
        var biome = holder.value();
        r.addProperty("base_temperature", biome.getBaseTemperature());
        r.addProperty("downfall", biome.getModifiedClimateSettings().downfall());
        r.addProperty("has_precipitation", biome.hasPrecipitation());
        return r;
    }
}

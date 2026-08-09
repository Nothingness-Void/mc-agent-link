package world.agentlink.dispatch.tools;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import world.agentlink.api.AgentApiException;
import world.agentlink.api.AgentLinkApi;
import world.agentlink.api.AgentWorldApi;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolArgs;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

/** Set the shared spawn point for one dimension or for every loaded dimension. */
public final class SetWorldSpawnTool implements Tool {

    private final MinecraftServer mc;

    public SetWorldSpawnTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "set_world_spawn";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        try {
            ToolArgs.IntPos pos = args.has("pos")
                    ? ToolArgs.requireIntPos(args, "pos") : ToolArgs.requireFlatIntPos(args);
            float angle = (float) ToolArgs.optDouble(args, "angle", 0);
            boolean all = ToolArgs.optBool(args, "all_dimensions", false);
            JsonObject result = new JsonObject();
            result.add("pos", pos.toJson());
            result.addProperty("angle", angle);
            if (all) {
                for (ServerLevel level : mc.getAllLevels()) AgentLinkApi.worlds().setSpawn(
                        level, new BlockPos(pos.x(), pos.y(), pos.z()), angle);
                result.addProperty("all_dimensions", true);
            } else {
                ServerLevel level = AgentLinkApi.worlds().level(mc,
                        ToolArgs.optString(args, "dim", AgentWorldApi.DEFAULT_DIMENSION));
                AgentLinkApi.worlds().setSpawn(level, new BlockPos(pos.x(), pos.y(), pos.z()), angle);
                result.addProperty("dim", level.dimension().location().toString());
            }
            result.addProperty("changed", true);
            return result;
        } catch (AgentApiException ex) {
            throw new ToolException(ex.code(), ex.getMessage());
        }
    }

}

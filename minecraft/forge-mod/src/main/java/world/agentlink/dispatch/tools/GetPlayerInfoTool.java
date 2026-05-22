package world.agentlink.dispatch.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import world.agentlink.dispatch.RequestDispatcher;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

public class GetPlayerInfoTool implements Tool {
    private final MinecraftServer mc;

    public GetPlayerInfoTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "get_player_info";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        String name = RequestDispatcher.requireString(args, "name");
        ServerPlayer p = mc.getPlayerList().getPlayerByName(name);
        if (p == null) {
            throw new ToolException("INVALID_ARGS", "Player not online: " + name);
        }

        JsonObject r = new JsonObject();
        r.addProperty("name", p.getGameProfile().getName());
        r.addProperty("uuid", p.getUUID().toString());

        JsonArray pos = new JsonArray();
        pos.add(p.getX());
        pos.add(p.getY());
        pos.add(p.getZ());
        r.add("pos", pos);

        r.addProperty("dim", p.level().dimension().location().toString());
        r.addProperty("yaw", p.getYRot());
        r.addProperty("pitch", p.getXRot());
        r.addProperty("health", p.getHealth());
        r.addProperty("max_health", p.getMaxHealth());
        r.addProperty("food", p.getFoodData().getFoodLevel());
        r.addProperty("xp_level", p.experienceLevel);
        r.addProperty("gamemode", p.gameMode.getGameModeForPlayer().getName());
        r.addProperty("ping", p.connection.latency());
        return r;
    }
}

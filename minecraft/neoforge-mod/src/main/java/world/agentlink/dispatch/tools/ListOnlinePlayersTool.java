package world.agentlink.dispatch.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import world.agentlink.dispatch.Tool;
import world.agentlink.transport.ClientSession;

public class ListOnlinePlayersTool implements Tool {
    private final MinecraftServer mc;

    public ListOnlinePlayersTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "list_online_players";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) {
        JsonArray arr = new JsonArray();
        for (ServerPlayer p : mc.getPlayerList().getPlayers()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", p.getGameProfile().getName());
            o.addProperty("uuid", p.getUUID().toString());
            o.addProperty("ping", p.connection.latency());
            o.addProperty("dim", p.level().dimension().location().toString());
            arr.add(o);
        }
        JsonObject r = new JsonObject();
        r.add("players", arr);
        r.addProperty("count", arr.size());
        return r;
    }
}

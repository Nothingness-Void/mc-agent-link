package world.agentlink.dispatch.tools;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import world.agentlink.dispatch.Tool;
import world.agentlink.transport.ClientSession;

import java.lang.management.ManagementFactory;

public class PingTool implements Tool {
    private final MinecraftServer mc;

    public PingTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "ping";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) {
        JsonObject r = new JsonObject();
        r.addProperty("pong", true);
        r.addProperty("uptime_ms", ManagementFactory.getRuntimeMXBean().getUptime());
        return r;
    }
}

package world.agentlink.dispatch.tools;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import world.agentlink.dispatch.RequestDispatcher;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

public class BroadcastTool implements Tool {
    private final MinecraftServer mc;

    public BroadcastTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "broadcast";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        String message = RequestDispatcher.requireString(args, "message");
        String color = args.has("color") && !args.get("color").isJsonNull()
                ? args.get("color").getAsString()
                : null;

        MutableComponent comp = Component.literal(message);
        if (color != null) {
            ChatFormatting fmt = ChatFormatting.getByName(color);
            if (fmt == null) throw new ToolException("INVALID_ARGS", "Unknown color: " + color);
            comp = comp.withStyle(fmt);
        }

        Component finalComp = comp;
        mc.getPlayerList().broadcastSystemMessage(finalComp, false);

        JsonObject r = new JsonObject();
        r.addProperty("sent", true);
        r.addProperty("recipients", mc.getPlayerCount());
        return r;
    }
}

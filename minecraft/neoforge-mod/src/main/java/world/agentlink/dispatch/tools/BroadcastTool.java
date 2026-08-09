package world.agentlink.dispatch.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import world.agentlink.api.AgentApiException;
import world.agentlink.api.AgentLinkApi;
import world.agentlink.dispatch.RequestDispatcher;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

import java.util.UUID;

/**
 * Sends a system message to online players. Three modes:
 *
 * <ul>
 *   <li>{@code message} — plain text. Optional {@code color} (vanilla
 *       ChatFormatting name) styles the whole line.</li>
 *   <li>{@code components} — raw tellraw JSON value (object or array of
 *       components). Lets the agent build a clickable / multi-style message
 *       without composing /tellraw command syntax.</li>
 *   <li>{@code target} — optional. {@code "@a"} (default), a player name, or
 *       a UUID string. Anything else falls back to broadcasting to all.</li>
 * </ul>
 *
 * Exactly one of {@code message} / {@code components} must be present.
 */
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
        Component msg = buildComponent(args);
        String target = args.has("target") && !args.get("target").isJsonNull()
                ? args.get("target").getAsString().trim()
                : "@a";

        int recipients;
        if (target.isEmpty() || "@a".equalsIgnoreCase(target)) {
            try {
                recipients = AgentLinkApi.messages().broadcast(mc, msg);
            } catch (AgentApiException ex) {
                throw new ToolException(ex.code(), ex.getMessage());
            }
        } else {
            ServerPlayer player = resolveTarget(target);
            if (player == null) {
                throw new ToolException("INVALID_ARGS", "No online player matching target: " + target);
            }
            try {
                AgentLinkApi.messages().send(player, msg);
            } catch (AgentApiException ex) {
                throw new ToolException(ex.code(), ex.getMessage());
            }
            recipients = 1;
        }

        JsonObject r = new JsonObject();
        r.addProperty("sent", true);
        r.addProperty("recipients", recipients);
        r.addProperty("target", target);
        return r;
    }

    private Component buildComponent(JsonObject args) throws ToolException {
        boolean hasMessage = args.has("message") && !args.get("message").isJsonNull();
        boolean hasComponents = args.has("components") && !args.get("components").isJsonNull();
        if (hasMessage == hasComponents) {
            throw new ToolException("INVALID_ARGS",
                    "Provide exactly one of: message (plain string) or components (tellraw JSON)");
        }
        if (hasComponents) {
            JsonElement el = args.get("components");
            try {
                Component comp = Component.Serializer.fromJson(el.toString(), mc.registryAccess());
                if (comp == null) throw new IllegalArgumentException("component JSON resolved to null");
                return comp;
            } catch (Exception ex) {
                throw new ToolException("INVALID_ARGS",
                        "Invalid tellraw components: " + ex.getMessage());
            }
        }
        String message = RequestDispatcher.requireString(args, "message");
        MutableComponent comp = Component.literal(message);
        if (args.has("color") && !args.get("color").isJsonNull()) {
            String color = args.get("color").getAsString();
            ChatFormatting fmt = ChatFormatting.getByName(color);
            if (fmt == null) throw new ToolException("INVALID_ARGS", "Unknown color: " + color);
            comp = comp.withStyle(fmt);
        }
        return comp;
    }

    private ServerPlayer resolveTarget(String target) {
        try {
            UUID uuid = UUID.fromString(target);
            return mc.getPlayerList().getPlayer(uuid);
        } catch (IllegalArgumentException ignored) {
        }
        return mc.getPlayerList().getPlayerByName(target);
    }
}

package world.agentlink.dispatch.tools;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolArgs;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

import java.util.Locale;

/**
 * Change a player's game mode.
 *
 * <p>Small tool, but it closes a specific gap: an agent building for a player often needs to put
 * them in spectator to get them out of the way, or into creative to hand them flight. Doing that
 * through {@code run_console_command} means requesting arbitrary-console permission for what is a
 * narrow, reversible action — and the response reports the previous mode so the agent can put it
 * back.
 */
public class SetGamemodeTool implements Tool {

    private final MinecraftServer mc;
    private final GetNbtTool resolver;

    public SetGamemodeTool(MinecraftServer mc) {
        this.mc = mc;
        this.resolver = new GetNbtTool(mc);
    }

    @Override
    public String name() {
        return "set_gamemode";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        ServerPlayer player = resolver.requirePlayer(ToolArgs.requireString(args, "name"));
        String raw = ToolArgs.requireString(args, "gamemode").trim().toLowerCase(Locale.ROOT);
        GameType mode = switch (raw) {
            case "survival", "s", "0" -> GameType.SURVIVAL;
            case "creative", "c", "1" -> GameType.CREATIVE;
            case "adventure", "a", "2" -> GameType.ADVENTURE;
            case "spectator", "sp", "3" -> GameType.SPECTATOR;
            default -> throw new ToolException("INVALID_ARGS",
                    "gamemode must be survival, creative, adventure, or spectator (got \"" + raw + "\")");
        };

        GameType previous = player.gameMode.getGameModeForPlayer();
        boolean changed = previous != mode;
        if (changed) player.setGameMode(mode);

        JsonObject r = new JsonObject();
        r.addProperty("name", player.getGameProfile().getName());
        r.addProperty("uuid", player.getUUID().toString());
        r.addProperty("previous_gamemode", previous.getName());
        r.addProperty("gamemode", player.gameMode.getGameModeForPlayer().getName());
        r.addProperty("changed", changed);
        return r;
    }
}

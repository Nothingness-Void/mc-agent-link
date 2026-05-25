package world.agentlink.dispatch.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import world.agentlink.dispatch.RequestDispatcher;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

import java.util.List;

/**
 * Resolves a vanilla {@code @a / @e / @p / @r} entity selector and returns the matching
 * online players (or, with {@code include_entities=true}, all matching entities).
 *
 * <p>Optional anchor: {@code anchor:{x,y,z,dim?}} or {@code anchor_player:"Steve"} sets the
 * source position for relative selectors like {@code @a[distance=..16]}. Without an anchor
 * the source defaults to the overworld spawn at op level 2 — fine for global queries
 * (gamemode, tag, team, name), useless for distance-based ones.
 */
public class FindPlayersTool implements Tool {

    private final MinecraftServer mc;

    public FindPlayersTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "find_players";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        String selector = RequestDispatcher.requireString(args, "selector");
        boolean includeEntities = args.has("include_entities") && !args.get("include_entities").isJsonNull()
                && args.get("include_entities").getAsBoolean();
        CommandSourceStack source = sourceFrom(args);

        EntitySelectorParser parser = new EntitySelectorParser(new StringReader(selector));
        EntitySelector parsed;
        try {
            parsed = parser.parse();
        } catch (CommandSyntaxException ex) {
            throw new ToolException("INVALID_ARGS",
                    "Invalid selector \"" + selector + "\": " + ex.getMessage());
        }

        JsonObject r = new JsonObject();
        r.addProperty("selector", selector);
        JsonArray players = new JsonArray();
        try {
            List<ServerPlayer> matched = parsed.findPlayers(source);
            for (ServerPlayer p : matched) {
                JsonObject o = new JsonObject();
                o.addProperty("name", p.getGameProfile().getName());
                o.addProperty("uuid", p.getUUID().toString());
                o.addProperty("dim", p.level().dimension().location().toString());
                JsonArray pos = new JsonArray();
                pos.add(p.getX()); pos.add(p.getY()); pos.add(p.getZ());
                o.add("pos", pos);
                o.addProperty("ping", p.latency);
                o.addProperty("gamemode", p.gameMode.getGameModeForPlayer().getName());
                players.add(o);
            }
            r.addProperty("count", matched.size());
            r.add("players", players);
        } catch (CommandSyntaxException ex) {
            throw new ToolException("INVALID_ARGS",
                    "Selector did not match players: " + ex.getMessage());
        }

        if (includeEntities) {
            JsonArray entities = new JsonArray();
            try {
                List<? extends Entity> matched = parsed.findEntities(source);
                for (Entity e : matched) {
                    if (e instanceof ServerPlayer) continue;
                    JsonObject o = new JsonObject();
                    o.addProperty("uuid", e.getUUID().toString());
                    o.addProperty("type", e.getType().builtInRegistryHolder().key().location().toString());
                    JsonArray pos = new JsonArray();
                    pos.add(e.getX()); pos.add(e.getY()); pos.add(e.getZ());
                    o.add("pos", pos);
                    o.addProperty("dim", e.level().dimension().location().toString());
                    if (e.hasCustomName()) o.addProperty("custom_name", e.getCustomName().getString());
                    entities.add(o);
                }
            } catch (CommandSyntaxException ex) {
                throw new ToolException("INVALID_ARGS",
                        "Selector did not match entities: " + ex.getMessage());
            }
            r.add("entities", entities);
        }

        return r;
    }

    private CommandSourceStack sourceFrom(JsonObject args) throws ToolException {
        ServerLevel level = mc.overworld();
        Vec3 anchorPos = Vec3.atCenterOf(level.getSharedSpawnPos());
        Vec2 rotation = Vec2.ZERO;

        if (args.has("anchor_player") && !args.get("anchor_player").isJsonNull()) {
            String name = args.get("anchor_player").getAsString();
            ServerPlayer p = mc.getPlayerList().getPlayerByName(name);
            if (p == null) throw new ToolException("INVALID_ARGS", "anchor_player not online: " + name);
            level = (ServerLevel) p.level();
            anchorPos = p.position();
            rotation = new Vec2(p.getXRot(), p.getYRot());
        } else if (args.has("anchor") && args.get("anchor").isJsonObject()) {
            JsonObject anchor = args.getAsJsonObject("anchor");
            double x = GetBlockTool.requireDouble(anchor, "x");
            double y = GetBlockTool.requireDouble(anchor, "y");
            double z = GetBlockTool.requireDouble(anchor, "z");
            anchorPos = new Vec3(x, y, z);
            if (anchor.has("dim") && !anchor.get("dim").isJsonNull()) {
                level = GetBlockTool.resolveDimension(mc, anchor);
            }
        }

        CommandSource silent = new CommandSource() {
            @Override public void sendSystemMessage(Component component) {}
            @Override public boolean acceptsSuccess() { return false; }
            @Override public boolean acceptsFailure() { return false; }
            @Override public boolean shouldInformAdmins() { return false; }
        };
        return new CommandSourceStack(
                silent, anchorPos, rotation, level,
                4, "agent-link/find_players",
                Component.literal("agent-link/find_players"),
                mc, null
        );
    }
}

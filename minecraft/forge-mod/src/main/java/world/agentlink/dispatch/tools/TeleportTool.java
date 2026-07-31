package world.agentlink.dispatch.tools;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolArgs;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

import java.util.Collections;

/**
 * Move a player or entity, optionally across dimensions.
 *
 * <p>Structured alternative to {@code run_console_command "/tp ..."}. The console route needs a
 * blanket console-command approval, has to be string-formatted correctly (including the
 * {@code /execute in <dim> run tp} dance for cross-dimension moves), and returns chat text the agent
 * has to parse to learn whether it worked. Here the arguments are typed, the result reports the
 * before/after positions, and approving "teleport" grants exactly the ability to teleport.
 *
 * <p>Destinations can be a coordinate triple, another player, or "the safe surface at this column"
 * ({@code to_surface}). The last one matters because an agent computing a destination from a map
 * often has good X/Z and no idea what Y is solid ground.
 */
public class TeleportTool implements Tool {

    private final MinecraftServer mc;
    private final GetNbtTool resolver;

    public TeleportTool(MinecraftServer mc) {
        this.mc = mc;
        this.resolver = new GetNbtTool(mc);
    }

    @Override
    public String name() {
        return "teleport";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        Entity subject = resolveSubject(args);
        ServerLevel targetLevel = args.has("dim")
                ? Dimensions.resolve(mc, args)
                : (ServerLevel) subject.level();

        double fromX = subject.getX(), fromY = subject.getY(), fromZ = subject.getZ();
        String fromDim = subject.level().dimension().location().toString();

        double x, y, z;
        float yaw = subject.getYRot();
        float pitch = subject.getXRot();
        String how;

        String toPlayerName = ToolArgs.optString(args, "to_player", null);
        if (toPlayerName != null && !toPlayerName.isBlank()) {
            ServerPlayer dest = resolver.requirePlayer(toPlayerName);
            targetLevel = (ServerLevel) dest.level();
            x = dest.getX(); y = dest.getY(); z = dest.getZ();
            yaw = dest.getYRot(); pitch = dest.getXRot();
            how = "to_player:" + dest.getGameProfile().getName();
        } else if (args.has("to")) {
            ToolArgs.DoublePos p = ToolArgs.requireDoublePos(args, "to");
            x = p.x(); y = p.y(); z = p.z();
            how = "coordinates";
        } else {
            throw new ToolException("INVALID_ARGS",
                    "Provide `to` (a position) or `to_player` (a name to teleport to)");
        }

        if (ToolArgs.optBool(args, "to_surface", false)) {
            // Highest motion-blocking block, then stand on top of it. Better than guessing Y and
            // dropping the subject into a cave or suffocating them in stone.
            int surfaceY = targetLevel.getHeight(
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    (int) Math.floor(x), (int) Math.floor(z));
            y = surfaceY;
            how += "+surface";
        }

        if (args.has("yaw")) yaw = (float) ToolArgs.requireDouble(args, "yaw");
        if (args.has("pitch")) pitch = (float) ToolArgs.requireDouble(args, "pitch");

        // Sanity-check the height range; a teleport far below the void limit is a slow death rather
        // than an error, which is a confusing outcome for the agent to debug.
        int minY = targetLevel.getMinBuildHeight();
        int maxY = minY + targetLevel.getHeight();
        if (y < minY - 64 || y > maxY + 512) {
            throw new ToolException("INVALID_ARGS",
                    "y=" + y + " is far outside " + Dimensions.idOf(targetLevel)
                            + "'s build range (" + minY + ".." + maxY + ")");
        }

        boolean crossDim = targetLevel != subject.level();
        if (subject instanceof ServerPlayer player) {
            player.teleportTo(targetLevel, x, y, z, Collections.emptySet(), yaw, pitch);
        } else if (crossDim) {
            subject.changeDimension(targetLevel);
            subject.teleportTo(x, y, z);
            subject.setYRot(yaw);
            subject.setXRot(pitch);
        } else {
            subject.teleportTo(x, y, z);
            subject.setYRot(yaw);
            subject.setXRot(pitch);
        }

        JsonObject r = new JsonObject();
        r.addProperty("uuid", subject.getUUID().toString());
        r.addProperty("type", subject.getType().builtInRegistryHolder().key().location().toString());
        if (subject instanceof ServerPlayer sp) r.addProperty("name", sp.getGameProfile().getName());
        JsonObject from = new JsonObject();
        from.addProperty("dim", fromDim);
        from.addProperty("x", fromX); from.addProperty("y", fromY); from.addProperty("z", fromZ);
        r.add("from", from);
        JsonObject to = new JsonObject();
        to.addProperty("dim", Dimensions.idOf(targetLevel));
        to.addProperty("x", subject.getX()); to.addProperty("y", subject.getY()); to.addProperty("z", subject.getZ());
        to.addProperty("yaw", subject.getYRot());
        to.addProperty("pitch", subject.getXRot());
        r.add("to", to);
        r.addProperty("cross_dimension", crossDim);
        r.addProperty("resolved_by", how);
        return r;
    }

    /** Player by {@code name}, or any loaded entity by {@code uuid}. */
    private Entity resolveSubject(JsonObject args) throws ToolException {
        String name = ToolArgs.optString(args, "name", null);
        if (name != null && !name.isBlank()) return resolver.requirePlayer(name);
        String uuid = ToolArgs.optString(args, "uuid", null);
        if (uuid != null && !uuid.isBlank()) return resolver.resolveEntity(args);
        throw new ToolException("INVALID_ARGS",
                "Provide `name` (a player) or `uuid` (any loaded entity) to teleport");
    }
}

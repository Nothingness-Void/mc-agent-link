package world.agentlink.dispatch.tools;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

/**
 * Standalone block raycast: caller picks origin and direction explicitly. For "what's the player
 * looking at right now", {@link GetPlayerInfoTool} already returns {@code look_target}.
 */
public class RaycastTool implements Tool {

    private static final double MAX_DISTANCE = 64.0;

    private final MinecraftServer mc;

    public RaycastTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "raycast";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        ServerLevel level = GetBlockTool.resolveDimension(mc, args);
        JsonObject originObj = requireObj(args, "origin");
        JsonObject dirObj = requireObj(args, "direction");
        double ox = GetBlockTool.requireDouble(originObj, "x");
        double oy = GetBlockTool.requireDouble(originObj, "y");
        double oz = GetBlockTool.requireDouble(originObj, "z");
        double dx = GetBlockTool.requireDouble(dirObj, "x");
        double dy = GetBlockTool.requireDouble(dirObj, "y");
        double dz = GetBlockTool.requireDouble(dirObj, "z");
        double distance = args.has("max_distance") && !args.get("max_distance").isJsonNull()
                ? args.get("max_distance").getAsDouble()
                : 16.0;
        if (distance <= 0) throw new ToolException("INVALID_ARGS", "max_distance must be positive");
        if (distance > MAX_DISTANCE) distance = MAX_DISTANCE;

        Vec3 dir = new Vec3(dx, dy, dz);
        if (dir.lengthSqr() < 1e-9) throw new ToolException("INVALID_ARGS", "direction must be non-zero");
        dir = dir.normalize();
        Vec3 from = new Vec3(ox, oy, oz);
        Vec3 to = from.add(dir.scale(distance));

        boolean ignoreFluids = !args.has("hit_fluids") || !args.get("hit_fluids").getAsBoolean();
        ClipContext ctx = new ClipContext(from, to,
                ClipContext.Block.OUTLINE,
                ignoreFluids ? ClipContext.Fluid.NONE : ClipContext.Fluid.ANY,
                null);
        BlockHitResult hit = level.clip(ctx);

        JsonObject r = new JsonObject();
        r.addProperty("dim", level.dimension().location().toString());
        r.addProperty("max_distance", distance);
        if (hit.getType() == HitResult.Type.MISS) {
            r.addProperty("hit", false);
            r.add("block", JsonNull.INSTANCE);
            return r;
        }
        BlockPos bp = hit.getBlockPos();
        BlockState state = level.getBlockState(bp);
        ResourceLocation rl = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        r.addProperty("hit", true);
        JsonObject pos = new JsonObject();
        pos.addProperty("x", bp.getX());
        pos.addProperty("y", bp.getY());
        pos.addProperty("z", bp.getZ());
        r.add("pos", pos);
        r.addProperty("block", rl == null ? "minecraft:unknown" : rl.toString());
        r.addProperty("face", hit.getDirection().getName());
        r.addProperty("distance", hit.getLocation().distanceTo(from));
        JsonObject hitPoint = new JsonObject();
        hitPoint.addProperty("x", hit.getLocation().x);
        hitPoint.addProperty("y", hit.getLocation().y);
        hitPoint.addProperty("z", hit.getLocation().z);
        r.add("hit_point", hitPoint);
        return r;
    }

    private static JsonObject requireObj(JsonObject args, String key) throws ToolException {
        if (!args.has(key) || args.get(key).isJsonNull() || !args.get(key).isJsonObject()) {
            throw new ToolException("INVALID_ARGS", "Missing object arg: " + key);
        }
        return args.getAsJsonObject(key);
    }
}

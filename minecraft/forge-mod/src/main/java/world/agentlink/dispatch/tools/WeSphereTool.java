package world.agentlink.dispatch.tools;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import world.agentlink.dispatch.RequestDispatcher;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;
import world.agentlink.we.WorldEditBridge;

/**
 * Build a sphere via WorldEdit. Tier-4 admin-only.
 *
 * <p>Args: {@code center:{x,y,z}, radius (1..50), block, hollow?, dim?}
 */
public class WeSphereTool implements Tool {

    private static final double MAX_RADIUS = 50.0;

    private final MinecraftServer mc;

    public WeSphereTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "we_sphere";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        ServerLevel level = GetBlockTool.resolveDimension(mc, args);
        JsonObject c = WeSetTool.requireObj(args, "center");
        int cx = GetBlockTool.requireInt(c, "x");
        int cy = GetBlockTool.requireInt(c, "y");
        int cz = GetBlockTool.requireInt(c, "z");
        double radius = GetBlockTool.requireDouble(args, "radius");
        if (radius <= 0 || radius > MAX_RADIUS) {
            throw new ToolException("INVALID_ARGS", "radius must be in (0, " + MAX_RADIUS + "]");
        }
        String blockId = RequestDispatcher.requireString(args, "block");
        boolean hollow = args.has("hollow") && !args.get("hollow").isJsonNull() && args.get("hollow").getAsBoolean();
        boolean filled = !hollow;

        Object pattern = WorldEditBridge.resolveBlockState(blockId);
        WorldEditBridge.EditResult res = WorldEditBridge.performEdit(level, ctx -> {
            ctx.makeSphere(cx, cy, cz, radius, pattern, filled);
        });

        JsonObject r = new JsonObject();
        r.addProperty("dim", level.dimension().location().toString());
        r.addProperty("changed", res.changed());
        r.addProperty("undo_depth", res.undoDepth());
        r.addProperty("hollow", hollow);
        r.addProperty("radius", radius);
        return r;
    }
}

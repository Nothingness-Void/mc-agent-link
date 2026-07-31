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
 * Fill a cuboid with a single block via WorldEdit (so the operation can be undone with we_undo
 * and is dispatched through FAWE's async writer when present). Tier-4 admin-only by default.
 */
public class WeSetTool implements Tool {

    /** Hard cap on a single fill volume to prevent the agent from flatlining the server. */
    private static final long MAX_VOLUME = 200_000;

    private final MinecraftServer mc;

    public WeSetTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "we_set";
    }

    /** Declare the cuboid so a build zone can exempt this call from the approval prompt. */
    @Override
    public void declareScope(JsonObject args) {
        try {
            world.agentlink.sandbox.BuildZones.declareScope(args,
                    world.agentlink.dispatch.ToolArgs.optString(args, "dim", Dimensions.DEFAULT),
                    world.agentlink.dispatch.ToolArgs.requireBox(args));
        } catch (ToolException ignored) {
        }
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        ServerLevel level = GetBlockTool.resolveDimension(mc, args);
        // ToolArgs accepts both {x,y,z} and [x,y,z], matching the native write tools.
        world.agentlink.dispatch.ToolArgs.Box box = world.agentlink.dispatch.ToolArgs.requireBox(args);
        int x1 = box.minX(), y1 = box.minY(), z1 = box.minZ();
        int x2 = box.maxX(), y2 = box.maxY(), z2 = box.maxZ();
        String blockId = RequestDispatcher.requireString(args, "block");

        long volume = box.volume();
        if (volume > MAX_VOLUME) {
            throw new ToolException("INVALID_ARGS",
                    "Region volume " + volume + " exceeds we_set limit " + MAX_VOLUME);
        }

        Object pattern = WorldEditBridge.resolveBlockState(blockId);
        WorldEditBridge.EditResult res = WorldEditBridge.performEdit(level, ctx -> {
            ctx.replaceBlocks(x1, y1, z1, x2, y2, z2, null, pattern);
        });

        JsonObject r = new JsonObject();
        r.addProperty("dim", level.dimension().location().toString());
        r.addProperty("changed", res.changed());
        r.addProperty("undo_depth", res.undoDepth());
        r.addProperty("volume", volume);
        return r;
    }

    static JsonObject requireObj(JsonObject args, String key) throws ToolException {
        if (!args.has(key) || args.get(key).isJsonNull() || !args.get(key).isJsonObject()) {
            throw new ToolException("INVALID_ARGS", "Missing object arg: " + key);
        }
        return args.getAsJsonObject(key);
    }
}

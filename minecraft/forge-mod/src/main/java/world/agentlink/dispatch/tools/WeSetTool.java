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

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        ServerLevel level = GetBlockTool.resolveDimension(mc, args);
        JsonObject min = requireObj(args, "min");
        JsonObject max = requireObj(args, "max");
        int x1 = GetBlockTool.requireInt(min, "x");
        int y1 = GetBlockTool.requireInt(min, "y");
        int z1 = GetBlockTool.requireInt(min, "z");
        int x2 = GetBlockTool.requireInt(max, "x");
        int y2 = GetBlockTool.requireInt(max, "y");
        int z2 = GetBlockTool.requireInt(max, "z");
        String blockId = RequestDispatcher.requireString(args, "block");

        long volume = (long) (Math.abs(x2 - x1) + 1)
                * (Math.abs(y2 - y1) + 1)
                * (Math.abs(z2 - z1) + 1);
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

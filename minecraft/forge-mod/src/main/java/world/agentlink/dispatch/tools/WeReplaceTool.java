package world.agentlink.dispatch.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import world.agentlink.dispatch.RequestDispatcher;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;
import world.agentlink.we.WorldEditBridge;

import java.util.ArrayList;
import java.util.List;

/**
 * Replace blocks in a cuboid: every block matching one of {@code from} is replaced with
 * {@code to}. Tier-4 admin-only.
 */
public class WeReplaceTool implements Tool {

    private static final long MAX_VOLUME = 200_000;

    private final MinecraftServer mc;

    public WeReplaceTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "we_replace";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        ServerLevel level = GetBlockTool.resolveDimension(mc, args);
        JsonObject min = WeSetTool.requireObj(args, "min");
        JsonObject max = WeSetTool.requireObj(args, "max");
        int x1 = GetBlockTool.requireInt(min, "x");
        int y1 = GetBlockTool.requireInt(min, "y");
        int z1 = GetBlockTool.requireInt(min, "z");
        int x2 = GetBlockTool.requireInt(max, "x");
        int y2 = GetBlockTool.requireInt(max, "y");
        int z2 = GetBlockTool.requireInt(max, "z");

        List<String> from = readStringList(args, "from");
        if (from.isEmpty()) throw new ToolException("INVALID_ARGS", "from must be a non-empty array of block ids");
        String to = RequestDispatcher.requireString(args, "to");

        long volume = (long) (Math.abs(x2 - x1) + 1)
                * (Math.abs(y2 - y1) + 1)
                * (Math.abs(z2 - z1) + 1);
        if (volume > MAX_VOLUME) {
            throw new ToolException("INVALID_ARGS",
                    "Region volume " + volume + " exceeds we_replace limit " + MAX_VOLUME);
        }

        Object pattern = WorldEditBridge.resolveBlockState(to);
        WorldEditBridge.EditResult res = WorldEditBridge.performEdit(level, ctx -> {
            Object mask = WorldEditBridge.resolveBlockMask(from, ctx.editSession());
            ctx.replaceBlocks(x1, y1, z1, x2, y2, z2, mask, pattern);
        });

        JsonObject r = new JsonObject();
        r.addProperty("dim", level.dimension().location().toString());
        r.addProperty("changed", res.changed());
        r.addProperty("undo_depth", res.undoDepth());
        r.addProperty("volume", volume);
        return r;
    }

    private static List<String> readStringList(JsonObject args, String key) throws ToolException {
        JsonElement el = args.get(key);
        if (el == null || el.isJsonNull()) return List.of();
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
            return List.of(el.getAsString());
        }
        if (!el.isJsonArray()) throw new ToolException("INVALID_ARGS", key + " must be a string or array");
        JsonArray arr = el.getAsJsonArray();
        List<String> out = new ArrayList<>(arr.size());
        for (JsonElement item : arr) {
            if (item == null || item.isJsonNull()) continue;
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                throw new ToolException("INVALID_ARGS", key + " must contain strings");
            }
            out.add(item.getAsString());
        }
        return out;
    }
}

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

        List<String> from = readStringList(args, "from");
        if (from.isEmpty()) throw new ToolException("INVALID_ARGS", "from must be a non-empty array of block ids");
        String to = RequestDispatcher.requireString(args, "to");

        long volume = box.volume();
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

package world.agentlink.dispatch.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads a cuboid of blocks. Volume capped at 4096 (e.g. 16×16×16) to keep one call cheap.
 *
 * <p>Output uses a palette + run-length encoding so a uniform region collapses to a few bytes:
 * <pre>
 *   palette: ["minecraft:air", "minecraft:stone", ...]
 *   runs: [[paletteIndex, count], ...]   // iterates in y → z → x order
 * </pre>
 * Agents that need raw coords can decode the runs themselves; iteration order is fixed.
 */
public class GetBlocksRegionTool implements Tool {

    private static final int MAX_VOLUME = 4096;

    private final MinecraftServer mc;

    public GetBlocksRegionTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "get_blocks_region";
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

        int xLo = Math.min(x1, x2), xHi = Math.max(x1, x2);
        int yLo = Math.min(y1, y2), yHi = Math.max(y1, y2);
        int zLo = Math.min(z1, z2), zHi = Math.max(z1, z2);

        long volume = (long) (xHi - xLo + 1) * (yHi - yLo + 1) * (zHi - zLo + 1);
        if (volume > MAX_VOLUME) {
            throw new ToolException("INVALID_ARGS",
                    "Region volume " + volume + " exceeds limit " + MAX_VOLUME + " — split into smaller chunks");
        }

        Map<String, Integer> paletteIndex = new LinkedHashMap<>();
        JsonArray runs = new JsonArray();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int currentIdx = -1;
        int currentCount = 0;

        for (int y = yLo; y <= yHi; y++) {
            for (int z = zLo; z <= zHi; z++) {
                for (int x = xLo; x <= xHi; x++) {
                    pos.set(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    ResourceLocation rl = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                    String id = rl == null ? "minecraft:unknown" : rl.toString();
                    int idx = paletteIndex.computeIfAbsent(id, k -> paletteIndex.size());
                    if (idx == currentIdx) {
                        currentCount++;
                    } else {
                        if (currentIdx >= 0) {
                            JsonArray run = new JsonArray();
                            run.add(currentIdx);
                            run.add(currentCount);
                            runs.add(run);
                        }
                        currentIdx = idx;
                        currentCount = 1;
                    }
                }
            }
        }
        if (currentIdx >= 0) {
            JsonArray run = new JsonArray();
            run.add(currentIdx);
            run.add(currentCount);
            runs.add(run);
        }

        JsonArray palette = new JsonArray();
        for (String id : paletteIndex.keySet()) palette.add(id);

        JsonObject r = new JsonObject();
        r.addProperty("dim", level.dimension().location().toString());
        JsonObject minOut = new JsonObject();
        minOut.addProperty("x", xLo);
        minOut.addProperty("y", yLo);
        minOut.addProperty("z", zLo);
        r.add("min", minOut);
        JsonObject maxOut = new JsonObject();
        maxOut.addProperty("x", xHi);
        maxOut.addProperty("y", yHi);
        maxOut.addProperty("z", zHi);
        r.add("max", maxOut);
        r.addProperty("volume", volume);
        r.addProperty("order", "y_z_x");
        r.add("palette", palette);
        r.add("runs", runs);
        return r;
    }

    private static JsonObject requireObj(JsonObject args, String key) throws ToolException {
        if (!args.has(key) || args.get(key).isJsonNull() || !args.get(key).isJsonObject()) {
            throw new ToolException("INVALID_ARGS", "Missing object arg: " + key);
        }
        return args.getAsJsonObject(key);
    }
}

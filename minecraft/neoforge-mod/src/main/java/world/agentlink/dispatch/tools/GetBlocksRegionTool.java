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
        // Via ToolArgs so both {x,y,z} and [x,y,z] work, consistent with every 0.5.0 spatial tool.
        world.agentlink.dispatch.ToolArgs.Box box = world.agentlink.dispatch.ToolArgs.requireBox(args);
        int xLo = box.minX(), xHi = box.maxX();
        int yLo = box.minY(), yHi = box.maxY();
        int zLo = box.minZ(), zHi = box.maxZ();

        long volume = box.volume();
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

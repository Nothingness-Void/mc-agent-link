package world.agentlink.dispatch.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import world.agentlink.dispatch.RequestDispatcher;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Saves a region snapshot to {@code config/agent-link/snapshots/<name>.json}. Pure read-side
 * (palette + RLE), so it inherits the same auto-allow tier as get_blocks_region.
 *
 * <p>{@code restore_block_snapshot} is intentionally not part of this tool — restoration
 * touches the world and should be a separate, explicitly-approved feature.
 */
public class SaveBlockSnapshotTool implements Tool {

    private static final Logger LOG = LogUtils.getLogger();
    private static final int MAX_VOLUME = 65536;
    private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    private final MinecraftServer mc;

    public SaveBlockSnapshotTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "save_block_snapshot";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        ServerLevel level = GetBlockTool.resolveDimension(mc, args);
        String name = RequestDispatcher.requireString(args, "name");
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new ToolException("INVALID_ARGS",
                    "name must match [A-Za-z0-9._-]{1,64}: " + name);
        }
        // Read through ToolArgs so this accepts both {x,y,z} and [x,y,z], matching
        // restore_block_snapshot. Accepting one shape here and both there is a trap: the agent
        // that just restored with arrays gets INVALID_ARGS when it tries to save the same way.
        world.agentlink.dispatch.ToolArgs.Box box = world.agentlink.dispatch.ToolArgs.requireBox(args);
        int xLo = box.minX(), xHi = box.maxX();
        int yLo = box.minY(), yHi = box.maxY();
        int zLo = box.minZ(), zHi = box.maxZ();

        long volume = box.volume();
        if (volume > MAX_VOLUME) {
            throw new ToolException("INVALID_ARGS",
                    "Snapshot volume " + volume + " exceeds limit " + MAX_VOLUME);
        }

        Map<String, Integer> paletteIndex = new LinkedHashMap<>();
        StringBuilder runs = new StringBuilder();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int currentIdx = -1;
        int currentCount = 0;
        runs.append('[');
        boolean first = true;

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
                            if (!first) runs.append(',');
                            first = false;
                            runs.append('[').append(currentIdx).append(',').append(currentCount).append(']');
                        }
                        currentIdx = idx;
                        currentCount = 1;
                    }
                }
            }
        }
        if (currentIdx >= 0) {
            if (!first) runs.append(',');
            runs.append('[').append(currentIdx).append(',').append(currentCount).append(']');
        }
        runs.append(']');

        JsonArray palette = new JsonArray();
        for (String id : paletteIndex.keySet()) palette.add(id);

        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("version", 1);
        snapshot.addProperty("name", name);
        snapshot.addProperty("dim", level.dimension().location().toString());
        snapshot.addProperty("created_at_ms", System.currentTimeMillis());
        JsonObject minOut = new JsonObject();
        minOut.addProperty("x", xLo); minOut.addProperty("y", yLo); minOut.addProperty("z", zLo);
        JsonObject maxOut = new JsonObject();
        maxOut.addProperty("x", xHi); maxOut.addProperty("y", yHi); maxOut.addProperty("z", zHi);
        snapshot.add("min", minOut);
        snapshot.add("max", maxOut);
        snapshot.addProperty("volume", volume);
        snapshot.addProperty("order", "y_z_x");
        snapshot.add("palette", palette);

        // Inject runs JSON manually so we don't double-parse the StringBuilder.
        com.google.gson.JsonElement runsEl = com.google.gson.JsonParser.parseString(runs.toString());
        snapshot.add("runs", runsEl);

        Path dir = FMLPaths.CONFIGDIR.get().resolve("agent-link").resolve("snapshots");
        Path target = dir.resolve(name + ".json");
        Path tmp = dir.resolve(name + ".json.tmp");
        try {
            Files.createDirectories(dir);
            try (Writer w = new OutputStreamWriter(
                    Files.newOutputStream(tmp, StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE),
                    StandardCharsets.UTF_8)) {
                w.write(snapshot.toString());
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            throw new ToolException("INTERNAL_ERROR", "write failed: " + ex.getMessage());
        }

        JsonObject r = new JsonObject();
        r.addProperty("name", name);
        r.addProperty("path", target.toString());
        r.addProperty("dim", level.dimension().location().toString());
        r.addProperty("volume", volume);
        r.addProperty("palette_size", palette.size());
        r.add("min", minOut);
        r.add("max", maxOut);

        LOG.info("[agent-link] save_block_snapshot name={} dim={} volume={} palette=",
                name, level.dimension().location(), volume, palette.size());
        return r;
    }

    private static JsonObject requireObj(JsonObject args, String key) throws ToolException {
        if (!args.has(key) || args.get(key).isJsonNull() || !args.get(key).isJsonObject()) {
            throw new ToolException("INVALID_ARGS", "Missing object arg: " + key);
        }
        return args.getAsJsonObject(key);
    }
}

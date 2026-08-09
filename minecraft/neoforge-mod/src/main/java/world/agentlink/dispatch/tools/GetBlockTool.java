package world.agentlink.dispatch.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import world.agentlink.dispatch.RequestDispatcher;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

import java.util.Map;

public class GetBlockTool implements Tool {

    private final MinecraftServer mc;

    public GetBlockTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "get_block";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        ServerLevel level = resolveDimension(mc, args);
        int x = requireInt(args, "x");
        int y = requireInt(args, "y");
        int z = requireInt(args, "z");

        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = level.getBlockState(pos);
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());

        JsonObject r = new JsonObject();
        JsonObject posObj = new JsonObject();
        posObj.addProperty("x", x);
        posObj.addProperty("y", y);
        posObj.addProperty("z", z);
        r.add("pos", posObj);
        r.addProperty("dim", level.dimension().location().toString());
        r.addProperty("id", blockId == null ? "minecraft:unknown" : blockId.toString());
        r.addProperty("is_air", state.isAir());
        r.addProperty("is_solid", state.isSolid());
        r.addProperty("light_emission", state.getLightEmission());
        r.addProperty("destroy_speed_fallback", state.getDestroySpeed(level, pos));

        JsonObject props = new JsonObject();
        for (Map.Entry<Property<?>, Comparable<?>> entry : state.getValues().entrySet()) {
            props.addProperty(entry.getKey().getName(), entry.getValue().toString());
        }
        r.add("properties", props);

        BlockEntity be = level.getBlockEntity(pos);
        r.addProperty("has_block_entity", be != null);
        if (be != null) {
            ResourceLocation beId = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
            r.addProperty("block_entity", beId == null ? "minecraft:unknown" : beId.toString());
        }

        // Biome at this column.
        var biomeHolder = level.getBiome(pos);
        biomeHolder.unwrapKey().ifPresent(k ->
                r.addProperty("biome", k.location().toString()));

        return r;
    }

    static ServerLevel resolveDimension(MinecraftServer mc, JsonObject args) throws ToolException {
        String dimRaw = args.has("dim") && !args.get("dim").isJsonNull()
                ? args.get("dim").getAsString()
                : "minecraft:overworld";
        ResourceLocation rl = ResourceLocation.tryParse(dimRaw);
        if (rl == null) throw new ToolException("INVALID_ARGS", "Invalid dimension: " + dimRaw);
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> key =
                net.minecraft.resources.ResourceKey.create(Registries.DIMENSION, rl);
        ServerLevel level = mc.getLevel(key);
        if (level == null) throw new ToolException("INVALID_ARGS", "Unknown dimension: " + dimRaw);
        return level;
    }

    static int requireInt(JsonObject args, String key) throws ToolException {
        JsonElement el = args.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
            throw new ToolException("INVALID_ARGS", "Missing int arg: " + key);
        }
        return el.getAsInt();
    }

    static double requireDouble(JsonObject args, String key) throws ToolException {
        JsonElement el = args.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
            throw new ToolException("INVALID_ARGS", "Missing number arg: " + key);
        }
        return el.getAsDouble();
    }
}

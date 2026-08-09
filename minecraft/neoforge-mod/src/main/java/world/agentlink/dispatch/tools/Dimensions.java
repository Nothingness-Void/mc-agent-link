package world.agentlink.dispatch.tools;

import com.google.gson.JsonObject;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import world.agentlink.dispatch.ToolException;

/**
 * Dimension resolution shared by every tool that takes a {@code dim} argument.
 *
 * <p>{@link GetBlockTool#resolveDimension} predates this and is package-private with the same
 * behaviour; this class is the public entry point for the 0.5.0 tools and for anything outside the
 * {@code tools} package. Both accept a bare id and default to the overworld.
 */
public final class Dimensions {

    public static final String DEFAULT = "minecraft:overworld";

    private Dimensions() {}

    /** Resolve {@code args.dim}, defaulting to the overworld. */
    public static ServerLevel resolve(MinecraftServer mc, JsonObject args) throws ToolException {
        String raw = args != null && args.has("dim") && !args.get("dim").isJsonNull()
                ? args.get("dim").getAsString()
                : DEFAULT;
        return byId(mc, raw);
    }

    public static ServerLevel byId(MinecraftServer mc, String id) throws ToolException {
        String raw = id == null || id.isBlank() ? DEFAULT : id.trim();
        ResourceLocation rl = ResourceLocation.tryParse(raw);
        if (rl == null) throw new ToolException("INVALID_ARGS", "Invalid dimension: " + raw);
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, rl);
        ServerLevel level = mc.getLevel(key);
        if (level == null) {
            throw new ToolException("INVALID_ARGS",
                    "Unknown dimension: " + raw + " — call list_dimensions for valid ids");
        }
        return level;
    }

    public static String idOf(ServerLevel level) {
        return level.dimension().location().toString();
    }
}

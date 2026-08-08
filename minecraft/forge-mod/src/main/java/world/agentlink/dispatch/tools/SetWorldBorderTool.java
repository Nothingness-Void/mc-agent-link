package world.agentlink.dispatch.tools;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.border.WorldBorder;
import world.agentlink.api.AgentApiException;
import world.agentlink.api.AgentLinkApi;
import world.agentlink.api.AgentWorldApi;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolArgs;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

/** Read and mutate the vanilla world border without formatting /worldborder command strings. */
public final class SetWorldBorderTool implements Tool {

    private final MinecraftServer mc;

    public SetWorldBorderTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "set_world_border";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        try {
            ServerLevel level = AgentLinkApi.worlds().level(mc,
                    ToolArgs.optString(args, "dim", AgentWorldApi.DEFAULT_DIMENSION));
            String action = ToolArgs.optEnum(args, "action", "get");
            WorldBorder border = level.getWorldBorder();
            switch (action) {
                case "get" -> { }
                case "center" -> {
                    JsonObject center = ToolArgs.optObject(args, "center");
                    double x = center == null ? ToolArgs.requireDouble(args, "x") : ToolArgs.requireDouble(center, "x");
                    double z = center == null ? ToolArgs.requireDouble(args, "z") : ToolArgs.requireDouble(center, "z");
                    AgentLinkApi.worlds().setBorderCenter(level, x, z);
                }
                case "size" -> AgentLinkApi.worlds().setBorderSize(level,
                        ToolArgs.requireDouble(args, "size"));
                case "lerp" -> {
                    long duration = ToolArgs.optLong(args, "duration_ticks", -1);
                    if (duration < 1) throw new ToolException("INVALID_ARGS", "duration_ticks must be >= 1");
                    AgentLinkApi.worlds().lerpBorderSize(level,
                            ToolArgs.optDouble(args, "from", border.getSize()),
                            ToolArgs.requireDouble(args, "to"), duration);
                }
                case "damage" -> AgentLinkApi.worlds().setBorderDamage(level,
                        ToolArgs.optDouble(args, "damage_per_block", border.getDamagePerBlock()),
                        ToolArgs.optDouble(args, "safe_zone", border.getDamageSafeZone()));
                case "warning" -> AgentLinkApi.worlds().setBorderWarning(level,
                        ToolArgs.optInt(args, "warning_blocks", border.getWarningBlocks()),
                        ToolArgs.optInt(args, "warning_seconds", border.getWarningTime()));
                case "reset" -> {
                    AgentLinkApi.worlds().setBorderCenter(level, 0, 0);
                    AgentLinkApi.worlds().setBorderSize(level, WorldBorder.MAX_SIZE);
                }
                default -> throw new ToolException("INVALID_ARGS",
                        "action must be get, center, size, lerp, damage, warning, or reset");
            }
            JsonObject result = snapshot(level);
            result.addProperty("action", action);
            return result;
        } catch (AgentApiException ex) {
            throw new ToolException(ex.code(), ex.getMessage());
        }
    }

    private static JsonObject snapshot(ServerLevel level) {
        WorldBorder border = level.getWorldBorder();
        JsonObject result = new JsonObject();
        result.addProperty("dim", level.dimension().location().toString());
        result.addProperty("center_x", border.getCenterX());
        result.addProperty("center_z", border.getCenterZ());
        result.addProperty("size", border.getSize());
        result.addProperty("lerp_target", border.getLerpTarget());
        result.addProperty("lerp_remaining_ticks", border.getLerpRemainingTime());
        result.addProperty("damage_per_block", border.getDamagePerBlock());
        result.addProperty("safe_zone", border.getDamageSafeZone());
        result.addProperty("warning_blocks", border.getWarningBlocks());
        result.addProperty("warning_seconds", border.getWarningTime());
        return result;
    }

}

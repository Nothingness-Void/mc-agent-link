package world.agentlink.dispatch.tools;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import world.agentlink.api.AgentApiException;
import world.agentlink.api.AgentLinkApi;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolArgs;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

/** Typed player vitals, progression, fire, and ability flags. */
public final class SetPlayerStateTool implements Tool {

    private final MinecraftServer mc;

    public SetPlayerStateTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "set_player_state";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        try {
            ServerPlayer player = AgentLinkApi.players().online(mc, ToolArgs.requireString(args, "name"));
            JsonObject before = snapshot(player);
            boolean changed = false;
            if (args.has("health")) {
                AgentLinkApi.players().setHealth(player, (float) ToolArgs.requireDouble(args, "health"));
                changed = true;
            }
            if (args.has("absorption")) {
                AgentLinkApi.players().setAbsorption(player, (float) ToolArgs.requireDouble(args, "absorption"));
                changed = true;
            }
            if (args.has("food") || args.has("saturation")) {
                int food = ToolArgs.optInt(args, "food", player.getFoodData().getFoodLevel());
                float saturation = (float) ToolArgs.optDouble(args, "saturation", player.getFoodData().getSaturationLevel());
                AgentLinkApi.players().setFood(player, food, saturation);
                changed = true;
            }
            if (args.has("air")) {
                AgentLinkApi.players().setAir(player, ToolArgs.requireInt(args, "air"));
                changed = true;
            }
            Integer points = args.has("experience_points") ? ToolArgs.requireInt(args, "experience_points") : null;
            Integer levels = args.has("experience_levels") ? ToolArgs.requireInt(args, "experience_levels") : null;
            if (points != null || levels != null) {
                AgentLinkApi.players().setExperience(player, points, levels);
                changed = true;
            }
            if (args.has("fire_seconds")) {
                AgentLinkApi.players().setFire(player, ToolArgs.requireInt(args, "fire_seconds"));
                changed = true;
            }
            if (args.has("invulnerable") || args.has("mayfly") || args.has("flying")) {
                AgentLinkApi.players().setAbilities(player,
                        optionalBoolean(args, "invulnerable"), optionalBoolean(args, "mayfly"),
                        optionalBoolean(args, "flying"));
                changed = true;
            }
            if (!changed) throw new ToolException("INVALID_ARGS", "Provide at least one player state field to set");
            JsonObject result = new JsonObject();
            result.addProperty("name", player.getGameProfile().getName());
            result.addProperty("uuid", player.getUUID().toString());
            result.add("before", before);
            result.add("after", snapshot(player));
            return result;
        } catch (AgentApiException ex) {
            throw new ToolException(ex.code(), ex.getMessage());
        }
    }

    private static JsonObject snapshot(ServerPlayer player) {
        JsonObject result = new JsonObject();
        result.addProperty("health", player.getHealth());
        result.addProperty("absorption", player.getAbsorptionAmount());
        result.addProperty("food", player.getFoodData().getFoodLevel());
        result.addProperty("saturation", player.getFoodData().getSaturationLevel());
        result.addProperty("air", player.getAirSupply());
        result.addProperty("experience_level", player.experienceLevel);
        result.addProperty("experience_points", player.totalExperience);
        result.addProperty("on_fire", player.isOnFire());
        result.addProperty("fire_ticks", player.getRemainingFireTicks());
        result.addProperty("invulnerable", player.getAbilities().invulnerable);
        result.addProperty("mayfly", player.getAbilities().mayfly);
        result.addProperty("flying", player.getAbilities().flying);
        return result;
    }

    private static Boolean optionalBoolean(JsonObject args, String key) {
        return args.has(key) && !args.get(key).isJsonNull() ? ToolArgs.optBool(args, key, false) : null;
    }
}

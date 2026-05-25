package world.agentlink.dispatch.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.border.WorldBorder;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

/**
 * One-shot snapshot covering the time/weather/world settings an agent might want to consult
 * before taking action. Per-dimension data lives under {@code dimensions[]}; the {@code primary}
 * fields summarize the overworld for the common case.
 */
public class GetWorldInfoTool implements Tool {

    private final MinecraftServer mc;

    public GetWorldInfoTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "get_world_info";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        ServerLevel target = args.has("dim") && !args.get("dim").isJsonNull()
                ? GetBlockTool.resolveDimension(mc, args)
                : mc.overworld();
        JsonObject r = new JsonObject();

        long dayTime = target.getDayTime();
        long gameTime = target.getGameTime();
        long timeOfDay = ((dayTime % 24000) + 24000) % 24000;
        long day = dayTime / 24000;

        r.addProperty("dim", target.dimension().location().toString());
        r.addProperty("server_running_ms", System.currentTimeMillis());
        r.addProperty("game_time_ticks", gameTime);
        r.addProperty("day_time_ticks", dayTime);
        r.addProperty("time_of_day_ticks", timeOfDay);
        r.addProperty("day", day);
        r.addProperty("moon_phase", target.getMoonPhase());
        r.addProperty("is_day", target.isDay());
        r.addProperty("is_night", target.isNight());
        r.addProperty("is_raining", target.isRaining());
        r.addProperty("is_thundering", target.isThundering());
        r.addProperty("rain_level", target.getRainLevel(1.0F));
        r.addProperty("thunder_level", target.getThunderLevel(1.0F));

        r.addProperty("difficulty", mc.getWorldData().getDifficulty().getKey());
        r.addProperty("hardcore", mc.getWorldData().isHardcore());
        r.addProperty("default_gametype", mc.getDefaultGameType().getName());
        r.addProperty("level_name", mc.getWorldData().getLevelName());
        r.addProperty("seed", target.getSeed());

        BlockPos spawn = target.getSharedSpawnPos();
        JsonObject spawnObj = new JsonObject();
        spawnObj.addProperty("x", spawn.getX());
        spawnObj.addProperty("y", spawn.getY());
        spawnObj.addProperty("z", spawn.getZ());
        spawnObj.addProperty("angle", target.getSharedSpawnAngle());
        r.add("spawn", spawnObj);

        WorldBorder border = target.getWorldBorder();
        JsonObject borderObj = new JsonObject();
        borderObj.addProperty("center_x", border.getCenterX());
        borderObj.addProperty("center_z", border.getCenterZ());
        borderObj.addProperty("size", border.getSize());
        borderObj.addProperty("damage_per_block", border.getDamagePerBlock());
        borderObj.addProperty("safe_zone", border.getDamageSafeZone());
        borderObj.addProperty("warning_blocks", border.getWarningBlocks());
        borderObj.addProperty("warning_time_seconds", border.getWarningTime());
        r.add("border", borderObj);

        // GameRules — flatten to {name: value-as-string}. Gamerules are server-wide so the
        // overworld instance is fine even when caller asked for another dimension.
        JsonObject rulesObj = new JsonObject();
        GameRules rules = mc.overworld().getGameRules();
        GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
            @Override
            public <T extends GameRules.Value<T>> void visit(GameRules.Key<T> key, GameRules.Type<T> type) {
                T value = rules.getRule(key);
                rulesObj.addProperty(key.getId(), value.toString());
            }
        });
        r.add("gamerules", rulesObj);

        // Per-dimension summary (always full list so the agent doesn't have to call list_dimensions
        // for the common "snapshot the server" case).
        JsonArray dims = new JsonArray();
        for (ServerLevel level : mc.getAllLevels()) {
            JsonObject d = new JsonObject();
            d.addProperty("id", level.dimension().location().toString());
            d.addProperty("loaded_chunks", level.getChunkSource().getLoadedChunksCount());
            d.addProperty("entity_count", entityCount(level));
            d.addProperty("min_y", level.getMinBuildHeight());
            d.addProperty("height", level.getHeight());
            d.addProperty("logical_height", level.getLogicalHeight());
            d.addProperty("sea_level", level.getSeaLevel());
            d.addProperty("is_raining", level.isRaining());
            d.addProperty("is_thundering", level.isThundering());
            dims.add(d);
        }
        r.add("dimensions", dims);

        return r;
    }

    private static int entityCount(ServerLevel level) {
        int n = 0;
        for (var ignored : level.getAllEntities()) n++;
        return n;
    }
}

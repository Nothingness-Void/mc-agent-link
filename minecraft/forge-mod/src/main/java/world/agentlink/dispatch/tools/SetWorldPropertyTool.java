package world.agentlink.dispatch.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import world.agentlink.api.AgentApiException;
import world.agentlink.api.AgentLinkApi;
import world.agentlink.api.AgentWorldApi;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolArgs;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

import java.util.Locale;

/**
 * Set time, weather, difficulty, or a gamerule.
 *
 * <p>{@code get_world_info} has always reported all of these; none of them could be changed except
 * through {@code run_console_command}. That asymmetry is the gap this closes — and it matters for
 * ordinary operations: stopping a thunderstorm that's spawning skeletons in someone's base, turning
 * off {@code doDaylightCycle} while the agent builds, setting {@code keepInventory} before a risky
 * experiment.
 *
 * <p>Gamerule writes are validated against the actual rule registry and its declared type, so a
 * typo'd rule name or a string where a boolean belongs is an error here rather than a silently
 * ignored command.
 */
public class SetWorldPropertyTool implements Tool {

    private final MinecraftServer mc;

    public SetWorldPropertyTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "set_world_property";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        String property = ToolArgs.requireString(args, "property").trim().toLowerCase(Locale.ROOT);
        JsonObject r = new JsonObject();
        r.addProperty("property", property);

        switch (property) {
            case "time" -> setTime(args, r);
            case "weather" -> setWeather(args, r);
            case "difficulty" -> setDifficulty(args, r);
            case "gamerule" -> setGamerule(args, r);
            default -> throw new ToolException("INVALID_ARGS",
                    "property must be time, weather, difficulty, or gamerule (got \"" + property + "\")");
        }
        return r;
    }

    // ------------------------------------------------------------------ time

    private void setTime(JsonObject args, JsonObject r) throws ToolException {
        ServerLevel level = Dimensions.resolve(mc, args);
        long before = level.getDayTime();
        String preset = ToolArgs.optString(args, "value", null);
        long dayTime;

        if (preset != null && !isNumeric(preset)) {
            // Vanilla's own preset names, so the agent can say "night" instead of computing ticks.
            long timeOfDay = switch (preset.trim().toLowerCase(Locale.ROOT)) {
                case "day" -> 1000L;
                case "noon" -> 6000L;
                case "sunset", "evening" -> 12000L;
                case "night" -> 13000L;
                case "midnight" -> 18000L;
                case "sunrise", "morning" -> 23000L;
                default -> throw new ToolException("INVALID_ARGS",
                        "Unknown time preset \"" + preset + "\" — use day, noon, sunset, night,"
                                + " midnight, sunrise, or a tick count");
            };
            // Advance to the next occurrence rather than jumping backwards a day, matching /time set.
            long currentDay = before / 24000L;
            dayTime = currentDay * 24000L + timeOfDay;
            if (dayTime < before) dayTime += 24000L;
        } else {
            long ticks = ToolArgs.optLong(args, "value", -1);
            if (ticks < 0) {
                throw new ToolException("INVALID_ARGS",
                        "value must be a tick count >= 0 or a preset (day, noon, night, midnight, ...)");
            }
            boolean add = ToolArgs.optBool(args, "add", false);
            dayTime = add ? before + ticks : ticks;
        }

        // Time is per-dimension in the API but shared in practice; set every level so the agent
        // doesn't have to know that the nether tracks its own counter.
        if (ToolArgs.optBool(args, "all_dimensions", true)) {
            for (ServerLevel l : mc.getAllLevels()) setTime(l, dayTime);
            r.addProperty("all_dimensions", true);
        } else {
            setTime(level, dayTime);
            r.addProperty("dim", Dimensions.idOf(level));
        }

        long timeOfDay = ((dayTime % 24000) + 24000) % 24000;
        r.addProperty("previous_day_time", before);
        r.addProperty("day_time", dayTime);
        r.addProperty("time_of_day", timeOfDay);
        r.addProperty("day", dayTime / 24000);
        // Derived from the tick count we just wrote, NOT from level.isDay(). The engine's day/night
        // flag and sky darkening are recomputed on the next tick, so reading them back here would
        // report the state we just replaced — which reads as "the write didn't work".
        r.addProperty("is_day", timeOfDay < 13000 || timeOfDay >= 23000);
    }

    // ------------------------------------------------------------------ weather

    private void setWeather(JsonObject args, JsonObject r) throws ToolException {
        ServerLevel level = Dimensions.resolve(mc, args);
        String value = ToolArgs.requireString(args, "value").trim().toLowerCase(Locale.ROOT);
        int seconds = ToolArgs.optIntClamped(args, "seconds", 300, 1, 1_000_000);
        boolean wasRaining = level.isRaining();
        boolean wasThundering = level.isThundering();
        setWeather(level, value, seconds);

        r.addProperty("dim", Dimensions.idOf(level));
        r.addProperty("value", value);
        r.addProperty("seconds", seconds);
        r.addProperty("was_raining", wasRaining);
        r.addProperty("was_thundering", wasThundering);
        // Report the requested end state, not level.isRaining(). setWeatherParameters sets the
        // countdown; the rain/thunder gradients ramp over the following ticks, so reading the live
        // flags back within the same tick reports the OLD weather and looks like a failed write.
        r.addProperty("is_raining", !"clear".equals(value));
        r.addProperty("is_thundering", "thunder".equals(value));
        r.addProperty("note", "Weather is per-dimension and only meaningful where it can rain"
                + " (the nether and the end never do). The visual transition ramps over the next few"
                + " seconds; get_world_info will show rain_level climbing.");
    }

    // ------------------------------------------------------------------ difficulty

    private void setDifficulty(JsonObject args, JsonObject r) throws ToolException {
        String value = ToolArgs.requireString(args, "value").trim().toLowerCase(Locale.ROOT);
        Difficulty before = mc.getWorldData().getDifficulty();
        Difficulty after = setDifficulty(value);

        r.addProperty("previous_difficulty", before.getKey());
        r.addProperty("difficulty", after.getKey());
        if (after == Difficulty.PEACEFUL) {
            r.addProperty("note", "Peaceful removes all hostile mobs immediately and prevents them"
                    + " from spawning.");
        }
    }

    // ------------------------------------------------------------------ gamerule

    private void setGamerule(JsonObject args, JsonObject r) throws ToolException {
        String ruleName = ToolArgs.requireString(args, "rule").trim();
        String raw = ToolArgs.requireString(args, "value").trim();
        try {
            AgentWorldApi.GameruleState state = AgentLinkApi.worlds().setGameRule(mc, ruleName, raw);
            r.addProperty("rule", state.rule());
            r.addProperty("type", state.type());
            r.addProperty("previous_value", state.previousValue());
            r.addProperty("value", state.value());
            r.addProperty("changed", state.changed());
        } catch (AgentApiException ex) {
            throw new ToolException(ex.code(), ex.getMessage());
        }
    }

    private static void setTime(ServerLevel level, long dayTime) throws ToolException {
        try {
            AgentLinkApi.worlds().setTime(level, dayTime);
        } catch (AgentApiException ex) {
            throw new ToolException(ex.code(), ex.getMessage());
        }
    }

    private static void setWeather(ServerLevel level, String value, int seconds) throws ToolException {
        try {
            AgentLinkApi.worlds().setWeather(level, value, seconds);
        } catch (AgentApiException ex) {
            throw new ToolException(ex.code(), ex.getMessage());
        }
    }

    private Difficulty setDifficulty(String value) throws ToolException {
        try {
            return AgentLinkApi.worlds().setDifficulty(mc, value);
        } catch (AgentApiException ex) {
            throw new ToolException(ex.code(), ex.getMessage());
        }
    }

    private static boolean isNumeric(String s) {
        if (s == null || s.isBlank()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }
}

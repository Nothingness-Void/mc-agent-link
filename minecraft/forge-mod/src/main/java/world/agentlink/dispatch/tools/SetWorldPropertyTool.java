package world.agentlink.dispatch.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
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
            for (ServerLevel l : mc.getAllLevels()) l.setDayTime(dayTime);
            r.addProperty("all_dimensions", true);
        } else {
            level.setDayTime(dayTime);
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
        int ticks = seconds * 20;

        boolean wasRaining = level.isRaining();
        boolean wasThundering = level.isThundering();

        switch (value) {
            case "clear" -> level.setWeatherParameters(ticks, 0, false, false);
            case "rain" -> level.setWeatherParameters(0, ticks, true, false);
            case "thunder" -> level.setWeatherParameters(0, ticks, true, true);
            default -> throw new ToolException("INVALID_ARGS",
                    "value must be clear, rain, or thunder (got \"" + value + "\")");
        }

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
        Difficulty target = switch (value) {
            case "peaceful", "0" -> Difficulty.PEACEFUL;
            case "easy", "1" -> Difficulty.EASY;
            case "normal", "2" -> Difficulty.NORMAL;
            case "hard", "3" -> Difficulty.HARD;
            default -> throw new ToolException("INVALID_ARGS",
                    "value must be peaceful, easy, normal, or hard (got \"" + value + "\")");
        };
        Difficulty before = mc.getWorldData().getDifficulty();
        mc.setDifficulty(target, true);

        r.addProperty("previous_difficulty", before.getKey());
        r.addProperty("difficulty", mc.getWorldData().getDifficulty().getKey());
        if (target == Difficulty.PEACEFUL) {
            r.addProperty("note", "Peaceful removes all hostile mobs immediately and prevents them"
                    + " from spawning.");
        }
    }

    // ------------------------------------------------------------------ gamerule

    /**
     * Apply a gamerule change through each value type's own public setter.
     *
     * <p>{@code GameRules.Value.deserialize} is protected, and going through it would also skip the
     * change callback that rules like {@code reducedDebugInfo} rely on to notify clients. The two
     * concrete subclasses expose {@code set(value, server)} publicly and do fire that callback, so we
     * branch on the type — which additionally lets us reject a boolean written into an integer rule
     * instead of silently parsing it as 0.
     */
    private void setGamerule(JsonObject args, JsonObject r) throws ToolException {
        String ruleName = ToolArgs.requireString(args, "rule").trim();
        String raw = ToolArgs.requireString(args, "value").trim();
        GameRules rules = mc.getGameRules();

        GameRules.Value<?> found = findRule(rules, ruleName);
        if (found == null) {
            throw new ToolException("INVALID_ARGS",
                    "Unknown gamerule \"" + ruleName + "\". get_world_info returns every rule with its"
                            + " current value.");
        }

        String before = found.toString();
        if (found instanceof GameRules.BooleanValue boolRule) {
            if (!"true".equalsIgnoreCase(raw) && !"false".equalsIgnoreCase(raw)) {
                throw new ToolException("INVALID_ARGS",
                        ruleName + " is a boolean rule; value must be \"true\" or \"false\" (got \""
                                + raw + "\")");
            }
            boolRule.set(Boolean.parseBoolean(raw), mc);
            r.addProperty("type", "boolean");
        } else if (found instanceof GameRules.IntegerValue intRule) {
            int parsed;
            try {
                parsed = Integer.parseInt(raw);
            } catch (NumberFormatException ex) {
                throw new ToolException("INVALID_ARGS",
                        ruleName + " is an integer rule; value must be a whole number (got \""
                                + raw + "\")");
            }
            intRule.set(parsed, mc);
            r.addProperty("type", "integer");
        } else {
            throw new ToolException("UNSUPPORTED",
                    "Gamerule " + ruleName + " uses the custom value type "
                            + found.getClass().getSimpleName() + ", which this tool cannot set."
                            + " Use run_console_command with /gamerule for it.");
        }

        String after = found.toString();
        r.addProperty("rule", ruleName);
        r.addProperty("previous_value", before);
        r.addProperty("value", after);
        r.addProperty("changed", !before.equals(after));
    }

    /** Locate a rule's live value by id. Returns null when the id is unknown. */
    private static GameRules.Value<?> findRule(GameRules rules, String ruleName) {
        GameRules.Value<?>[] found = new GameRules.Value<?>[1];
        GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
            @Override
            public <T extends GameRules.Value<T>> void visit(GameRules.Key<T> key, GameRules.Type<T> type) {
                if (found[0] != null || !key.getId().equals(ruleName)) return;
                found[0] = rules.getRule(key);
            }
        });
        return found[0];
    }

    private static boolean isNumeric(String s) {
        if (s == null || s.isBlank()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }
}

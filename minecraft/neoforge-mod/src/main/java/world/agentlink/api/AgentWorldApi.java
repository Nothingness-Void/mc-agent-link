package world.agentlink.api;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.border.WorldBorder;

import java.util.Locale;

/** Dimension lookup and typed world geometry controls. */
public final class AgentWorldApi {

    public static final String DEFAULT_DIMENSION = "minecraft:overworld";

    public record WeatherState(String value, int seconds, boolean raining, boolean thundering) {}
    public record GameruleState(String rule, String type, String previousValue, String value,
                                boolean changed) {}

    AgentWorldApi() {}

    public ServerLevel level(MinecraftServer server, String dimension) throws AgentApiException {
        if (server == null) throw new AgentApiException("SERVER_UNAVAILABLE", "server is not running");
        String raw = dimension == null || dimension.isBlank() ? DEFAULT_DIMENSION : dimension.trim();
        ResourceLocation id = ResourceLocation.tryParse(raw);
        if (id == null) throw new AgentApiException("INVALID_ARGS", "Invalid dimension: " + raw);
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
        if (level == null) throw new AgentApiException("NOT_FOUND", "Unknown dimension: " + raw);
        return level;
    }

    public void setSpawn(ServerLevel level, BlockPos pos, float angle) throws AgentApiException {
        if (level == null || pos == null) throw new AgentApiException("INVALID_ARGS", "level and pos are required");
        if (!Float.isFinite(angle)) throw new AgentApiException("INVALID_ARGS", "angle must be finite");
        level.setDefaultSpawnPos(pos, angle);
    }

    /** Set the absolute day-time counter for one dimension. */
    public void setTime(ServerLevel level, long dayTime) throws AgentApiException {
        requireLevel(level);
        if (dayTime < 0) throw new AgentApiException("INVALID_ARGS", "dayTime must be >= 0");
        level.setDayTime(dayTime);
    }

    /** Set clear, rain, or thunder for a bounded duration. */
    public WeatherState setWeather(ServerLevel level, String value, int seconds)
            throws AgentApiException {
        requireLevel(level);
        String weather = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (seconds < 1 || seconds > 1_000_000) {
            throw new AgentApiException("INVALID_ARGS", "seconds must be 1..1000000");
        }
        int ticks = seconds * 20;
        switch (weather) {
            case "clear" -> level.setWeatherParameters(ticks, 0, false, false);
            case "rain" -> level.setWeatherParameters(0, ticks, true, false);
            case "thunder" -> level.setWeatherParameters(0, ticks, true, true);
            default -> throw new AgentApiException("INVALID_ARGS",
                    "value must be clear, rain, or thunder");
        }
        return new WeatherState(weather, seconds, !"clear".equals(weather), "thunder".equals(weather));
    }

    /** Change the server difficulty using the same names accepted by vanilla commands. */
    public Difficulty setDifficulty(MinecraftServer server, String value) throws AgentApiException {
        requireServer(server);
        String raw = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        Difficulty difficulty = switch (raw) {
            case "peaceful", "0" -> Difficulty.PEACEFUL;
            case "easy", "1" -> Difficulty.EASY;
            case "normal", "2" -> Difficulty.NORMAL;
            case "hard", "3" -> Difficulty.HARD;
            default -> throw new AgentApiException("INVALID_ARGS",
                    "value must be peaceful, easy, normal, or hard");
        };
        server.setDifficulty(difficulty, true);
        return server.getWorldData().getDifficulty();
    }

    /** Set a vanilla gamerule after validating its real registered value type. */
    public GameruleState setGameRule(MinecraftServer server, String ruleName, String rawValue)
            throws AgentApiException {
        requireServer(server);
        if (ruleName == null || ruleName.isBlank()) {
            throw new AgentApiException("INVALID_ARGS", "rule is required");
        }
        if (rawValue == null || rawValue.isBlank()) {
            throw new AgentApiException("INVALID_ARGS", "value is required");
        }
        GameRules.Value<?> found = findRule(server.getGameRules(), ruleName.trim());
        if (found == null) throw new AgentApiException("INVALID_ARGS", "Unknown gamerule: " + ruleName);

        String before = found.toString();
        String type;
        if (found instanceof GameRules.BooleanValue boolRule) {
            if (!"true".equalsIgnoreCase(rawValue) && !"false".equalsIgnoreCase(rawValue)) {
                throw new AgentApiException("INVALID_ARGS", ruleName + " expects true or false");
            }
            boolRule.set(Boolean.parseBoolean(rawValue), server);
            type = "boolean";
        } else if (found instanceof GameRules.IntegerValue intRule) {
            final int parsed;
            try {
                parsed = Integer.parseInt(rawValue.trim());
            } catch (NumberFormatException ex) {
                throw new AgentApiException("INVALID_ARGS", ruleName + " expects an integer", ex);
            }
            intRule.set(parsed, server);
            type = "integer";
        } else {
            throw new AgentApiException("UNSUPPORTED",
                    "Gamerule uses unsupported value type " + found.getClass().getSimpleName());
        }
        String after = found.toString();
        return new GameruleState(ruleName.trim(), type, before, after, !before.equals(after));
    }

    public void setBorderCenter(ServerLevel level, double x, double z) throws AgentApiException {
        requireLevel(level);
        finite(x, "x"); finite(z, "z");
        level.getWorldBorder().setCenter(x, z);
    }

    public void setBorderSize(ServerLevel level, double size) throws AgentApiException {
        requireLevel(level);
        finite(size, "size");
        if (size < 1 || size > WorldBorder.MAX_SIZE) {
            throw new AgentApiException("INVALID_ARGS", "size must be between 1 and " + WorldBorder.MAX_SIZE);
        }
        level.getWorldBorder().setSize(size);
    }

    public void lerpBorderSize(ServerLevel level, double from, double to, long durationTicks)
            throws AgentApiException {
        requireLevel(level);
        finite(from, "from"); finite(to, "to");
        if (from < 1 || from > WorldBorder.MAX_SIZE || to < 1 || to > WorldBorder.MAX_SIZE) {
            throw new AgentApiException("INVALID_ARGS", "border sizes must be between 1 and " + WorldBorder.MAX_SIZE);
        }
        if (durationTicks < 1) throw new AgentApiException("INVALID_ARGS", "duration_ticks must be >= 1");
        level.getWorldBorder().lerpSizeBetween(from, to, durationTicks);
    }

    public void setBorderDamage(ServerLevel level, double damagePerBlock, double safeZone)
            throws AgentApiException {
        requireLevel(level);
        finite(damagePerBlock, "damage_per_block"); finite(safeZone, "safe_zone");
        if (damagePerBlock < 0 || safeZone < 0) {
            throw new AgentApiException("INVALID_ARGS", "damage_per_block and safe_zone must be >= 0");
        }
        WorldBorder border = level.getWorldBorder();
        border.setDamagePerBlock(damagePerBlock);
        border.setDamageSafeZone(safeZone);
    }

    public void setBorderWarning(ServerLevel level, int blocks, int seconds) throws AgentApiException {
        requireLevel(level);
        if (blocks < 0 || seconds < 0) throw new AgentApiException("INVALID_ARGS", "warning values must be >= 0");
        WorldBorder border = level.getWorldBorder();
        border.setWarningBlocks(blocks);
        border.setWarningTime(seconds);
    }

    private static GameRules.Value<?> findRule(GameRules rules, String ruleName) {
        GameRules.Value<?>[] found = new GameRules.Value<?>[1];
        GameRules.visitGameRuleTypes(new GameRules.GameRuleTypeVisitor() {
            @Override
            public <T extends GameRules.Value<T>> void visit(GameRules.Key<T> key, GameRules.Type<T> type) {
                if (found[0] == null && key.getId().equals(ruleName)) found[0] = rules.getRule(key);
            }
        });
        return found[0];
    }

    private static void requireLevel(ServerLevel level) throws AgentApiException {
        if (level == null) throw new AgentApiException("INVALID_ARGS", "level is required");
    }

    private static void requireServer(MinecraftServer server) throws AgentApiException {
        if (server == null) throw new AgentApiException("SERVER_UNAVAILABLE", "server is not running");
    }

    private static void finite(double value, String name) throws AgentApiException {
        if (!Double.isFinite(value)) throw new AgentApiException("INVALID_ARGS", name + " must be finite");
    }
}

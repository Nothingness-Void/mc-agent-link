package world.agentlink.spigot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/** Bukkit implementation of the base agent-link tool surface. */
public final class SpigotTools {
    private static final int MAX_REGION_VOLUME = 4096;
    private static final int MAX_WRITE_VOLUME = 32768;
    private static final int MAX_FILE_BYTES = 4 * 1024 * 1024;

    private SpigotTools() {}

    public static void registerAll(SpigotDispatcher dispatcher, JavaPlugin plugin,
                                   SpigotConfig.Snapshot config, EventBuffer events,
                                   TickMonitor ticks, ModernCompatibility.RuntimeInfo platform) {
        Context context = new Context(plugin, config, events, ticks, platform);
        dispatcher.register(new SimpleTool("ping", "Liveness probe. Returns server uptime and tick timing.",
                JsonSupport.objectSchema(), false, false, (args, session) -> {
                    JsonObject result = new JsonObject();
                    result.addProperty("pong", true);
                    result.addProperty("uptime_ms", System.currentTimeMillis() - context.startedAt);
                    result.addProperty("loader", "spigot");
                    return result;
                }));
        dispatcher.register(new SimpleTool("whoami", "Reports token tier and the plugin's permission boundaries.",
                JsonSupport.objectSchema(), false, false, (args, session) -> whoami(context)));
        dispatcher.register(new SimpleTool("get_server_capabilities",
                "Report the runtime Bukkit platform and the modern compatibility capabilities available to this connection.",
                JsonSupport.objectSchema(), false, false, (args, session) -> context.platform.toJson()));
        dispatcher.register(new SimpleTool("list_online_players",
                "List currently online players with name, UUID, ping, and world.",
                JsonSupport.objectSchema(), false, false, (args, session) -> listPlayers()));
        dispatcher.register(new SimpleTool("get_player_info",
                "Read detailed information about one online player.",
                requiredSchema("name"), false, false, (args, session) -> playerInfo(args)));
        dispatcher.register(new SimpleTool("get_player_inventory",
                "Snapshot one online player's inventory.",
                requiredSchema("name"), false, false, (args, session) -> playerInventory(args)));
        dispatcher.register(new SimpleTool("get_world_info",
                "Read loaded world time, weather, spawn, border, and height information.",
                JsonSupport.objectSchema(), false, false, (args, session) -> worldInfo()));
        dispatcher.register(new SimpleTool("get_block",
                "Read one block and its block data.",
                positionSchema(false), false, false, (args, session) -> getBlock(args)));
        dispatcher.register(new SimpleTool("get_blocks_region",
                "Read a bounded cuboid of blocks as a palette and entries.",
                requiredSchema("min", "max"), false, false, (args, session) -> getBlocksRegion(args)));
        dispatcher.register(new SimpleTool("get_biome",
                "Read the biome at a block column.",
                positionSchema(false), false, false, (args, session) -> getBiome(args)));
        dispatcher.register(new SimpleTool("raycast",
                "Raycast from an online player and return the first hit block or entity.",
                requiredSchema("name"), false, false, (args, session) -> raycast(args)));
        dispatcher.register(new SimpleTool("list_entities_near",
                "List entities around a player or coordinate.",
                JsonSupport.objectSchema(), false, false, (args, session) -> listEntitiesNear(args)));
        dispatcher.register(new SimpleTool("list_dimensions",
                "List loaded Bukkit worlds and their environments.",
                JsonSupport.objectSchema(), false, false, (args, session) -> listDimensions()));
        dispatcher.register(new SimpleTool("list_mods",
                "List installed Bukkit plugins. The protocol field is named loader for compatibility.",
                JsonSupport.objectSchema(), false, false, (args, session) -> listPlugins()));
        dispatcher.register(new SimpleTool("find_players",
                "Find online players by case-insensitive name substring.",
                requiredSchema("query"), false, false, (args, session) -> findPlayers(args)));
        dispatcher.register(new SimpleTool("get_server_stats",
                "Return server version, player count, memory, loaded chunks, and measured MSPT/TPS.",
                JsonSupport.objectSchema(), false, false, (args, session) -> serverStats(context)));
        dispatcher.register(new SimpleTool("server_diagnose",
                "Return a bounded server health snapshot using Bukkit-safe diagnostics.",
                JsonSupport.objectSchema(), false, false, (args, session) -> diagnose(context)));
        dispatcher.register(new SimpleTool("get_recent_events",
                "Read the pull-mode event ring buffer.",
                JsonSupport.objectSchema(), false, false, (args, session) -> recentEvents(context, args)));
        dispatcher.register(new SimpleTool("subscribe_events",
                "Subscribe a WebSocket client to event push topics.",
                requiredSchema("topics"), false, false, (args, session) -> subscribe(context, args, session)));
        dispatcher.register(new SimpleTool("unsubscribe_events",
                "Remove a WebSocket client's event push subscriptions.",
                JsonSupport.objectSchema(), false, false, (args, session) -> unsubscribe(context, args, session)));
        dispatcher.register(new SimpleTool("read_server_file",
                "Read a bounded UTF-8 or base64 file under the server root.",
                requiredSchema("path"), false, true, (args, session) -> readFile(context, args)));
        dispatcher.register(new SimpleTool("list_dir",
                "List a bounded directory under the server root.",
                requiredSchema("path"), false, true, (args, session) -> listDir(context, args)));
        dispatcher.register(new SimpleTool("write_config_file",
                "Atomically write an allowlisted server-root-relative file with a backup.",
                requiredSchema("path", "content"), true, true, (args, session) -> writeFile(context, args)));
        dispatcher.register(new SimpleTool("thread_dump",
                "Return a bounded JVM thread dump.",
                JsonSupport.objectSchema(), false, true, (args, session) -> threadDump(args)));
        dispatcher.register(new SimpleTool("tick_profile",
                "Return measured MSPT percentiles and TPS.",
                JsonSupport.objectSchema(), false, false, (args, session) -> tickProfile(context)));
        dispatcher.register(new SimpleTool("tick_incidents",
                "Return timing-only tick incidents above the 50ms tick budget.",
                JsonSupport.objectSchema(), false, false, (args, session) -> tickIncidents(context)));
        dispatcher.register(new SimpleTool("get_scoreboard",
                "Read scoreboard objective names and online player scores.",
                JsonSupport.objectSchema(), false, false, (args, session) -> scoreboard(args)));

        dispatcher.register(new SimpleTool("broadcast", "Broadcast a message to online players.",
                requiredSchema("message"), true, false, (args, session) -> broadcast(args)));
        dispatcher.register(new SimpleTool("run_console_command",
                "Execute one server command as the console. The Bukkit API does not expose captured output.",
                requiredSchema("command"), true, false, (args, session) -> runCommand(args)));
        dispatcher.register(new SimpleTool("set_block", "Set one block and return an undo snapshot id.",
                requiredSchema("x", "y", "z", "block"), true, false,
                (args, session) -> setBlock(context, args)));
        dispatcher.register(new SimpleTool("fill_blocks", "Fill a bounded cuboid and return an undo snapshot id.",
                requiredSchema("min", "max", "block"), true, false,
                (args, session) -> fillBlocks(context, args)));
        dispatcher.register(new SimpleTool("set_blocks", "Set a bounded list of blocks and return an undo snapshot id.",
                requiredSchema("blocks"), true, false,
                (args, session) -> setBlocks(context, args)));
        dispatcher.register(new SimpleTool("undo_blocks", "Restore a previously returned block snapshot.",
                requiredSchema("snapshot"), true, false,
                (args, session) -> undoBlocks(context, args)));
        dispatcher.register(new SimpleTool("teleport", "Teleport an online player.",
                requiredSchema("name", "x", "y", "z"), true, false,
                (args, session) -> teleport(args)));
        dispatcher.register(new SimpleTool("give_item", "Give an item to an online player.",
                requiredSchema("name", "item"), true, false,
                (args, session) -> giveItem(args)));
        dispatcher.register(new SimpleTool("set_gamemode", "Change an online player's game mode.",
                requiredSchema("name", "gamemode"), true, false,
                (args, session) -> setGamemode(args)));
        dispatcher.register(new SimpleTool("apply_effect", "Apply a potion effect to an online player.",
                requiredSchema("name", "effect"), true, false,
                (args, session) -> applyEffect(args)));
        dispatcher.register(new SimpleTool("spawn_entity", "Spawn one entity in a loaded world.",
                requiredSchema("entity"), true, false,
                (args, session) -> spawnEntity(args)));
        dispatcher.register(new SimpleTool("remove_entities", "Remove filtered non-player entities in a loaded world.",
                JsonSupport.objectSchema(), true, false,
                (args, session) -> removeEntities(args)));
        dispatcher.register(new SimpleTool("set_world_spawn", "Set a world's spawn location.",
                requiredSchema("x", "y", "z"), true, false,
                (args, session) -> setWorldSpawn(args)));
        dispatcher.register(new SimpleTool("set_world_border", "Set a world's border center and size.",
                requiredSchema("size"), true, false,
                (args, session) -> setWorldBorder(args)));
        dispatcher.register(new SimpleTool("save_world", "Save one loaded world or all loaded worlds.",
                JsonSupport.objectSchema(), true, false,
                (args, session) -> saveWorld(args)));
    }

    private static JsonObject requiredSchema(String... required) {
        JsonObject schema = JsonSupport.schema(false, required);
        for (String key : required) {
            JsonObject property = new JsonObject();
            if (key.equals("x") || key.equals("y") || key.equals("z") || key.equals("count")
                    || key.equals("duration") || key.equals("duration_seconds") || key.equals("amplifier")) {
                property.addProperty("type", "number");
            } else if (key.equals("blocks")) {
                property.addProperty("type", "array");
                JsonObject item = new JsonObject();
                item.addProperty("type", "object");
                property.add("items", item);
            } else if (key.equals("topics")) {
                property.addProperty("type", "array");
                JsonObject item = new JsonObject();
                item.addProperty("type", "string");
                property.add("items", item);
            } else if (key.equals("min") || key.equals("max")) {
                property.addProperty("type", "object");
            } else {
                property.addProperty("type", "string");
            }
            schema.getAsJsonObject("properties").add(key, property);
        }
        return schema;
    }

    private static JsonObject positionSchema(boolean withBlock) {
        JsonObject schema = JsonSupport.schema(false, "x", "y", "z");
        JsonSupport.integerProperty(schema, "x", "Block x coordinate");
        JsonSupport.integerProperty(schema, "y", "Block y coordinate");
        JsonSupport.integerProperty(schema, "z", "Block z coordinate");
        JsonSupport.stringProperty(schema, "dim", "World name or minecraft dimension id");
        if (withBlock) JsonSupport.stringProperty(schema, "block", "Bukkit material or block data");
        return schema;
    }

    private static final class Context {
        private final JavaPlugin plugin;
        private final SpigotConfig.Snapshot config;
        private final EventBuffer events;
        private final TickMonitor ticks;
        private final ModernCompatibility.RuntimeInfo platform;
        private final Path root;
        private final long startedAt = System.currentTimeMillis();
        private final AtomicLong snapshotSequence = new AtomicLong(1);
        private final Map<String, List<BlockChange>> snapshots = new LinkedHashMap<>();

        private Context(JavaPlugin plugin, SpigotConfig.Snapshot config, EventBuffer events, TickMonitor ticks,
                        ModernCompatibility.RuntimeInfo platform) {
            this.plugin = plugin;
            this.config = config;
            this.events = events;
            this.ticks = ticks;
            this.platform = platform;
            this.root = SpigotConfig.serverRoot(plugin).toAbsolutePath().normalize();
        }
    }

    private record BlockChange(World world, int x, int y, int z, String oldBlockData) {}
    private record BlockEdit(World world, int x, int y, int z, BlockData data) {}

    private static JsonObject whoami(Context context) {
        JsonObject result = new JsonObject();
        result.addProperty("authenticated", true);
        result.addProperty("token_tier", RequestContext.tier().name().toLowerCase(Locale.ROOT));
        result.addProperty("approval_required_for_guest_writes", context.config.approvalEnabled());
        result.addProperty("server_root", context.root.toString());
        result.addProperty("write_allow_count", context.config.writeAllow().size());
        result.addProperty("write_deny_count", context.config.writeDeny().size());
        result.addProperty("in_game_agent_requests", false);
        result.addProperty("agent_command_registered", false);
        result.add("platform", context.platform.toJson());
        JsonArray tools = new JsonArray();
        tools.add("mcp_http");
        tools.add("websocket_v0");
        result.add("transports", tools);
        return result;
    }

    private static JsonObject listPlayers() {
        JsonArray players = new JsonArray();
        for (Player player : Bukkit.getOnlinePlayers()) {
            JsonObject object = new JsonObject();
            object.addProperty("name", player.getName());
            object.addProperty("uuid", player.getUniqueId().toString());
            object.addProperty("ping", player.getPing());
            object.addProperty("dim", player.getWorld().getKey().toString());
            players.add(object);
        }
        JsonObject result = new JsonObject();
        result.add("players", players);
        result.addProperty("count", players.size());
        result.addProperty("max_players", Bukkit.getMaxPlayers());
        return result;
    }

    private static JsonObject playerInfo(JsonObject args) throws ToolException {
        Player player = player(args);
        Location location = player.getLocation();
        JsonObject result = new JsonObject();
        result.addProperty("name", player.getName());
        result.addProperty("uuid", player.getUniqueId().toString());
        result.add("pos", position(location));
        result.addProperty("x", location.getX());
        result.addProperty("y", location.getY());
        result.addProperty("z", location.getZ());
        result.addProperty("dim", player.getWorld().getKey().toString());
        result.addProperty("yaw", location.getYaw());
        result.addProperty("pitch", location.getPitch());
        result.addProperty("health", player.getHealth());
        result.addProperty("max_health", player.getMaxHealth());
        result.addProperty("food", player.getFoodLevel());
        result.addProperty("saturation", player.getSaturation());
        result.addProperty("xp_level", player.getLevel());
        result.addProperty("xp_progress", player.getExp());
        result.addProperty("gamemode", player.getGameMode().name().toLowerCase(Locale.ROOT));
        result.addProperty("ping", player.getPing());
        result.addProperty("op", player.isOp());
        JsonArray effects = new JsonArray();
        for (PotionEffect effect : player.getActivePotionEffects()) {
            JsonObject value = new JsonObject();
            value.addProperty("effect", effect.getType().getName().toLowerCase(Locale.ROOT));
            value.addProperty("duration_ticks", effect.getDuration());
            value.addProperty("amplifier", effect.getAmplifier());
            effects.add(value);
        }
        result.add("effects", effects);
        return result;
    }

    private static JsonObject playerInventory(JsonObject args) throws ToolException {
        Player player = player(args);
        JsonArray items = new JsonArray();
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.getType().isAir()) continue;
            JsonObject value = item(item);
            value.addProperty("slot", slot);
            items.add(value);
        }
        JsonObject result = new JsonObject();
        result.addProperty("player", player.getName());
        result.add("items", items);
        return result;
    }

    private static JsonObject worldInfo() {
        JsonArray worlds = new JsonArray();
        for (World world : Bukkit.getWorlds()) {
            JsonObject value = new JsonObject();
            value.addProperty("name", world.getName());
            value.addProperty("dim", world.getKey().toString());
            value.addProperty("environment", world.getEnvironment().name().toLowerCase(Locale.ROOT));
            value.addProperty("time", world.getTime());
            value.addProperty("full_time", world.getFullTime());
            value.addProperty("storm", world.hasStorm());
            value.addProperty("thunder", world.isThundering());
            value.addProperty("difficulty", world.getDifficulty().name().toLowerCase(Locale.ROOT));
            value.addProperty("min_height", world.getMinHeight());
            value.addProperty("max_height", world.getMaxHeight());
            value.add("spawn", position(world.getSpawnLocation()));
            value.add("border", border(world.getWorldBorder()));
            worlds.add(value);
        }
        JsonObject result = new JsonObject();
        result.add("worlds", worlds);
        result.addProperty("count", worlds.size());
        return result;
    }

    private static JsonObject getBlock(JsonObject args) throws ToolException {
        int x = JsonSupport.requiredInt(args, "x");
        int y = JsonSupport.requiredInt(args, "y");
        int z = JsonSupport.requiredInt(args, "z");
        World world = world(JsonSupport.optionalString(args, "dim", null));
        return block(world.getBlockAt(x, y, z));
    }

    private static JsonObject getBlocksRegion(JsonObject args) throws ToolException {
        World world = world(JsonSupport.optionalString(args, "dim", null));
        Box box = box(args);
        if (box.volume() > MAX_REGION_VOLUME) {
            throw new ToolException("INVALID_ARGS", "Region volume " + box.volume()
                    + " exceeds " + MAX_REGION_VOLUME + " blocks");
        }
        Map<String, Integer> paletteIds = new LinkedHashMap<>();
        JsonArray entries = new JsonArray();
        for (int y = box.minY; y <= box.maxY; y++) {
            for (int z = box.minZ; z <= box.maxZ; z++) {
                for (int x = box.minX; x <= box.maxX; x++) {
                    Block block = world.getBlockAt(x, y, z);
                    String state = block.getBlockData().getAsString();
                    int id = paletteIds.computeIfAbsent(state, key -> paletteIds.size());
                    JsonObject entry = new JsonObject();
                    entry.addProperty("x", x);
                    entry.addProperty("y", y);
                    entry.addProperty("z", z);
                    entry.addProperty("palette", id);
                    entries.add(entry);
                }
            }
        }
        JsonArray palette = new JsonArray();
        paletteIds.keySet().forEach(palette::add);
        JsonObject result = new JsonObject();
        result.addProperty("dim", world.getKey().toString());
        result.add("min", point(box.minX, box.minY, box.minZ));
        result.add("max", point(box.maxX, box.maxY, box.maxZ));
        result.add("palette", palette);
        result.add("blocks", entries);
        result.addProperty("count", entries.size());
        return result;
    }

    private static JsonObject getBiome(JsonObject args) throws ToolException {
        int x = JsonSupport.requiredInt(args, "x");
        int z = JsonSupport.requiredInt(args, "z");
        World world = world(JsonSupport.optionalString(args, "dim", null));
        JsonObject result = new JsonObject();
        result.addProperty("dim", world.getKey().toString());
        result.addProperty("x", x);
        result.addProperty("z", z);
        result.addProperty("biome", world.getBiome(x, z).getKey().toString());
        return result;
    }

    private static JsonObject raycast(JsonObject args) throws ToolException {
        Player player = player(args);
        int maxDistance = Math.max(1, Math.min(64, JsonSupport.optionalInt(args, "max_distance", 5)));
        Block target = player.getTargetBlockExact(maxDistance);
        JsonObject result = new JsonObject();
        result.addProperty("player", player.getName());
        result.addProperty("max_distance", maxDistance);
        if (target == null) {
            result.addProperty("hit", false);
        } else {
            result.addProperty("hit", true);
            result.add("block", block(target));
        }
        return result;
    }

    private static JsonObject listEntitiesNear(JsonObject args) throws ToolException {
        World world = world(JsonSupport.optionalString(args, "dim", null));
        Location center;
        String playerName = JsonSupport.optionalString(args, "name", null);
        if (playerName != null && !playerName.isBlank()) {
            center = player(args).getLocation();
        } else {
            center = new Location(world, JsonSupport.optionalDouble(args, "x", 0),
                    JsonSupport.optionalDouble(args, "y", 0), JsonSupport.optionalDouble(args, "z", 0));
        }
        double radius = Math.max(1, Math.min(64, JsonSupport.optionalDouble(args, "radius", 16)));
        JsonArray entities = new JsonArray();
        for (Entity entity : world.getNearbyEntities(center, radius, radius, radius)) {
            JsonObject value = entity(entity);
            value.addProperty("distance", entity.getLocation().distance(center));
            entities.add(value);
        }
        JsonObject result = new JsonObject();
        result.add("center", position(center));
        result.addProperty("radius", radius);
        result.add("entities", entities);
        result.addProperty("count", entities.size());
        return result;
    }

    private static JsonObject listDimensions() {
        JsonArray dimensions = new JsonArray();
        for (World world : Bukkit.getWorlds()) {
            JsonObject value = new JsonObject();
            value.addProperty("name", world.getName());
            value.addProperty("dim", world.getKey().toString());
            value.addProperty("environment", world.getEnvironment().name().toLowerCase(Locale.ROOT));
            value.addProperty("players", world.getPlayers().size());
            dimensions.add(value);
        }
        JsonObject result = new JsonObject();
        result.add("dimensions", dimensions);
        result.addProperty("count", dimensions.size());
        return result;
    }

    private static JsonObject listPlugins() {
        JsonArray plugins = new JsonArray();
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            JsonObject value = new JsonObject();
            value.addProperty("mod_id", plugin.getName());
            value.addProperty("display_name", plugin.getDescription().getName());
            value.addProperty("version", plugin.getDescription().getVersion());
            value.addProperty("description", plugin.getDescription().getDescription());
            value.addProperty("enabled", plugin.isEnabled());
            plugins.add(value);
        }
        JsonObject result = new JsonObject();
        result.add("mods", plugins);
        result.addProperty("count", plugins.size());
        result.addProperty("loader", "spigot");
        return result;
    }

    private static JsonObject findPlayers(JsonObject args) throws ToolException {
        String query = JsonSupport.requiredString(args, "query").toLowerCase(Locale.ROOT);
        JsonArray players = new JsonArray();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.getName().toLowerCase(Locale.ROOT).contains(query)) continue;
            JsonObject value = new JsonObject();
            value.addProperty("name", player.getName());
            value.addProperty("uuid", player.getUniqueId().toString());
            value.addProperty("ping", player.getPing());
            players.add(value);
        }
        JsonObject result = new JsonObject();
        result.addProperty("query", query);
        result.add("players", players);
        result.addProperty("count", players.size());
        return result;
    }

    private static JsonObject serverStats(Context context) {
        TickMonitor.JsonStats timing = context.ticks.stats();
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        int chunks = Bukkit.getWorlds().stream().mapToInt(world -> world.getLoadedChunks().length).sum();
        JsonObject result = new JsonObject();
        result.addProperty("version", Bukkit.getVersion());
        result.addProperty("bukkit_version", Bukkit.getBukkitVersion());
        result.add("platform", context.platform.toJson());
        result.addProperty("tps", timing.tps());
        result.addProperty("mspt", timing.averageMspt());
        result.addProperty("max_mspt", timing.maxMspt());
        result.addProperty("mem_used_mb", used / (1024 * 1024));
        result.addProperty("mem_max_mb", runtime.maxMemory() / (1024 * 1024));
        result.addProperty("loaded_chunks", chunks);
        result.addProperty("online", Bukkit.getOnlinePlayers().size());
        result.addProperty("max_players", Bukkit.getMaxPlayers());
        result.addProperty("uptime_ms", System.currentTimeMillis() - context.startedAt);
        return result;
    }

    private static JsonObject diagnose(Context context) {
        TickMonitor.JsonStats timing = context.ticks.stats();
        JsonObject result = new JsonObject();
        result.addProperty("complete", true);
        result.add("server_stats", serverStats(context));
        result.add("tick_profile", tickProfile(context));
        result.add("tick_incidents", tickIncidents(context));
        result.add("world", worldInfo());
        result.add("players", listPlayers());
        result.add("mods", listPlugins());
        result.add("threads", threadDump(new JsonObject()));
        JsonArray diagnosis = new JsonArray();
        if (timing.p95Mspt() > 50) diagnosis.add("Recent tick samples show sustained tick time above 50ms.");
        if (timing.maxMspt() > 200) diagnosis.add("At least one sampled tick exceeded 200ms.");
        if (diagnosis.size() == 0) diagnosis.add("No timing threshold exceeded in the local sample window.");
        result.add("diagnosis", diagnosis);
        result.addProperty("note", "Spigot exposes no Forge mod list or server crash ledger; diagnostics use Bukkit and JVM APIs.");
        return result;
    }

    private static JsonObject recentEvents(Context context, JsonObject args) {
        long since = optionalLong(args, "since_seq", 0L);
        int limit = Math.max(1, Math.min(500, JsonSupport.optionalInt(args, "limit", 50)));
        return context.events.read(since, limit, JsonSupport.optionalStringList(args, "topics"));
    }

    private static JsonObject subscribe(Context context, JsonObject args, ClientSession session) throws ToolException {
        if (session == null) throw new ToolException("INVALID_ARGS", "subscribe_events requires WebSocket transport");
        List<String> topics = JsonSupport.optionalStringList(args, "topics");
        if (topics.isEmpty()) throw new ToolException("INVALID_ARGS", "topics must not be empty");
        context.events.subscribe(session, topics);
        JsonObject result = new JsonObject();
        JsonArray values = new JsonArray();
        topics.forEach(values::add);
        result.add("subscribed", values);
        return result;
    }

    private static JsonObject unsubscribe(Context context, JsonObject args, ClientSession session) throws ToolException {
        if (session == null) throw new ToolException("INVALID_ARGS", "unsubscribe_events requires WebSocket transport");
        List<String> topics = JsonSupport.optionalStringList(args, "topics");
        context.events.unsubscribe(session, topics);
        JsonObject result = new JsonObject();
        JsonArray values = new JsonArray();
        topics.forEach(values::add);
        result.add("unsubscribed", values);
        return result;
    }

    private static JsonObject readFile(Context context, JsonObject args) throws ToolException {
        Path path = safePath(context, JsonSupport.requiredString(args, "path"));
        int offset = Math.max(0, JsonSupport.optionalInt(args, "offset", 0));
        int maxBytes = Math.max(1, Math.min(MAX_FILE_BYTES,
                JsonSupport.optionalInt(args, "max_bytes", 256 * 1024)));
        try {
            if (!Files.isRegularFile(path)) throw new ToolException("INVALID_ARGS", "Not a regular file: " + relative(context, path));
            long size = Files.size(path);
            if (offset > size) offset = (int) Math.min(Integer.MAX_VALUE, size);
            byte[] bytes = new byte[Math.min(maxBytes, (int) Math.min(Integer.MAX_VALUE, size - offset))];
            int read = 0;
            try (var input = Files.newInputStream(path)) {
                long skipped = input.skip(offset);
                while (skipped < offset) {
                    long extra = input.skip(offset - skipped);
                    if (extra <= 0) break;
                    skipped += extra;
                }
                while (read < bytes.length) {
                    int count = input.read(bytes, read, bytes.length - read);
                    if (count < 0) break;
                    read += count;
                }
            }
            if (read != bytes.length) bytes = java.util.Arrays.copyOf(bytes, read);
            long end = (long) offset + bytes.length;
            JsonObject result = new JsonObject();
            result.addProperty("path", relative(context, path));
            result.addProperty("size", size);
            result.addProperty("offset", offset);
            result.addProperty("bytes_read", bytes.length);
            result.addProperty("truncated", end < size);
            if (looksText(bytes)) {
                result.addProperty("encoding", "utf-8");
                result.addProperty("content", new String(bytes, StandardCharsets.UTF_8));
            } else {
                result.addProperty("encoding", "base64");
                result.addProperty("content", Base64.getEncoder().encodeToString(bytes));
            }
            return result;
        } catch (IOException e) {
            throw new ToolException("INTERNAL_ERROR", "Failed to read file: " + e.getMessage());
        }
    }

    private static JsonObject listDir(Context context, JsonObject args) throws ToolException {
        Path path = safePath(context, JsonSupport.requiredString(args, "path"));
        int maxEntries = Math.max(1, Math.min(1000, JsonSupport.optionalInt(args, "max_entries", 500)));
        if (!Files.isDirectory(path)) throw new ToolException("INVALID_ARGS", "Not a directory: " + relative(context, path));
        JsonArray entries = new JsonArray();
        int total = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
            for (Path child : stream) {
                total++;
                if (entries.size() >= maxEntries) continue;
                JsonObject value = new JsonObject();
                value.addProperty("name", child.getFileName().toString());
                value.addProperty("is_dir", Files.isDirectory(child));
                value.addProperty("size", Files.isRegularFile(child) ? Files.size(child) : 0L);
                value.addProperty("mtime_ms", Files.getLastModifiedTime(child).toMillis());
                entries.add(value);
            }
        } catch (IOException e) {
            throw new ToolException("INTERNAL_ERROR", "Failed to list directory: " + e.getMessage());
        }
        JsonObject result = new JsonObject();
        result.addProperty("path", relative(context, path));
        result.add("entries", entries);
        result.addProperty("returned", entries.size());
        result.addProperty("total", total);
        result.addProperty("truncated", total > entries.size());
        return result;
    }

    private static JsonObject writeFile(Context context, JsonObject args) throws ToolException {
        Path path = safePath(context, JsonSupport.requiredString(args, "path"));
        String relative = relative(context, path);
        if (!allowed(context.config.writeAllow(), relative)) {
            throw new ToolException("INVALID_ARGS", "Path does not match write_allow: " + relative);
        }
        String denied = matchingRule(context.config.writeDeny(), relative);
        if (denied != null) throw new ToolException("INVALID_ARGS", "Path matches write_deny pattern: " + denied);
        if (relative.startsWith("config/agent-link/")) {
            throw new ToolException("INVALID_ARGS", "Agent Link security files are not writable through this tool");
        }
        String content = JsonSupport.requiredString(args, "content");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) throw new ToolException("INVALID_ARGS", "content exceeds 4 MiB");
        boolean overwrite = JsonSupport.optionalBoolean(args, "overwrite", false);
        try {
            Files.createDirectories(path.getParent());
            String backup = null;
            if (Files.exists(path)) {
                if (!overwrite) throw new ToolException("INVALID_ARGS", "File exists; set overwrite=true");
                Path backupPath = context.root.resolve("config/.agent-link-backup")
                        .resolve(relative.replace('/', '_') + "." + System.currentTimeMillis() + ".bak");
                Files.createDirectories(backupPath.getParent());
                Files.copy(path, backupPath, StandardCopyOption.REPLACE_EXISTING);
                backup = relative(context, backupPath);
            }
            Path temp = path.resolveSibling(path.getFileName() + ".agent-link.tmp");
            Files.write(temp, bytes);
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            JsonObject result = new JsonObject();
            result.addProperty("path", relative);
            result.addProperty("bytes_written", bytes.length);
            result.addProperty("created", backup == null);
            if (backup != null) result.addProperty("backup", backup);
            return result;
        } catch (IOException e) {
            throw new ToolException("INTERNAL_ERROR", "Failed to write file: " + e.getMessage());
        }
    }

    private static JsonObject threadDump(JsonObject args) {
        int maxFrames = Math.max(1, Math.min(100, JsonSupport.optionalInt(args, "max_frames", 30)));
        boolean onlyServer = JsonSupport.optionalBoolean(args, "only_server", false);
        JsonArray threads = new JsonArray();
        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            Thread thread = entry.getKey();
            if (onlyServer && !thread.getName().toLowerCase(Locale.ROOT).contains("server")) continue;
            JsonObject value = new JsonObject();
            value.addProperty("id", thread.getId());
            value.addProperty("name", thread.getName());
            value.addProperty("state", thread.getState().name());
            JsonArray stack = new JsonArray();
            StackTraceElement[] frames = entry.getValue();
            for (int i = 0; i < Math.min(maxFrames, frames.length); i++) stack.add(frames[i].toString());
            value.add("stack", stack);
            value.addProperty("stack_truncated", frames.length > maxFrames);
            threads.add(value);
        }
        JsonObject result = new JsonObject();
        result.add("threads", threads);
        result.addProperty("count", threads.size());
        return result;
    }

    private static JsonObject tickProfile(Context context) {
        TickMonitor.JsonStats timing = context.ticks.stats();
        JsonObject result = new JsonObject();
        result.addProperty("samples", timing.windowTicks());
        result.addProperty("avg_mspt", timing.averageMspt());
        result.addProperty("max_mspt", timing.maxMspt());
        result.addProperty("p50_mspt", timing.p50Mspt());
        result.addProperty("p95_mspt", timing.p95Mspt());
        result.addProperty("p99_mspt", timing.p99Mspt());
        result.addProperty("tps", timing.tps());
        result.addProperty("window_ticks", timing.windowTicks());
        return result;
    }

    private static JsonObject tickIncidents(Context context) {
        TickMonitor.JsonStats timing = context.ticks.stats();
        JsonArray incidents = new JsonArray();
        if (timing.maxMspt() > 50) {
            JsonObject incident = new JsonObject();
            incident.addProperty("duration_ms", timing.maxMspt());
            incident.addProperty("peak_ms", timing.maxMspt());
            incident.addProperty("sample_ticks", 1);
            incidents.add(incident);
        }
        JsonObject result = new JsonObject();
        result.addProperty("threshold_ms", 50);
        result.addProperty("recent_window_ms", 10000);
        result.add("incidents", incidents);
        result.addProperty("count", incidents.size());
        result.addProperty("timing_only", true);
        return result;
    }

    private static JsonObject scoreboard(JsonObject args) {
        org.bukkit.scoreboard.Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        JsonArray objectives = new JsonArray();
        for (org.bukkit.scoreboard.Objective objective : scoreboard.getObjectives()) {
            JsonObject value = new JsonObject();
            value.addProperty("name", objective.getName());
            value.addProperty("display_name", objective.getDisplayName());
            value.addProperty("criteria", objective.getCriteria());
            objectives.add(value);
        }
        JsonObject result = new JsonObject();
        result.add("objectives", objectives);
        result.addProperty("count", objectives.size());
        return result;
    }

    private static JsonObject broadcast(JsonObject args) throws ToolException {
        String message = JsonSupport.requiredString(args, "message");
        String color = JsonSupport.optionalString(args, "color", null);
        if (color != null && !color.isBlank()) {
            try {
                message = ChatColor.valueOf(color.trim().toUpperCase(Locale.ROOT)) + message;
            } catch (IllegalArgumentException ignored) {
                // Keep the message uncolored when the optional name is unknown.
            }
        }
        message = ChatColor.translateAlternateColorCodes('&', message);
        int recipients = Bukkit.broadcastMessage(message);
        JsonObject result = new JsonObject();
        result.addProperty("sent", true);
        result.addProperty("recipients", recipients);
        return result;
    }

    private static JsonObject runCommand(JsonObject args) throws ToolException {
        String command = JsonSupport.requiredString(args, "command").trim();
        while (command.startsWith("/")) command = command.substring(1);
        if (command.isBlank()) throw new ToolException("INVALID_ARGS", "command is empty");
        boolean accepted = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        JsonObject result = new JsonObject();
        result.addProperty("return_value", accepted ? 1 : 0);
        result.addProperty("accepted", accepted);
        result.addProperty("command", command);
        result.addProperty("output", "Spigot's public command API does not expose captured console output.");
        return result;
    }

    private static JsonObject setBlock(Context context, JsonObject args) throws ToolException {
        World world = world(JsonSupport.optionalString(args, "dim", null));
        int x = JsonSupport.requiredInt(args, "x");
        int y = JsonSupport.requiredInt(args, "y");
        int z = JsonSupport.requiredInt(args, "z");
        String state = JsonSupport.requiredString(args, "block");
        BlockData data = parseBlockData(state);
        Block block = world.getBlockAt(x, y, z);
        String snapshot = saveSnapshot(context, List.of(new BlockChange(world, x, y, z,
                block.getBlockData().getAsString())));
        block.setBlockData(data, false);
        JsonObject result = new JsonObject();
        result.addProperty("changed", true);
        result.addProperty("snapshot", snapshot);
        result.add("block", block(block));
        return result;
    }

    private static JsonObject fillBlocks(Context context, JsonObject args) throws ToolException {
        World world = world(JsonSupport.optionalString(args, "dim", null));
        Box box = box(args);
        if (box.volume() > MAX_WRITE_VOLUME) {
            throw new ToolException("INVALID_ARGS", "Write volume " + box.volume()
                    + " exceeds " + MAX_WRITE_VOLUME + "; use smaller slices");
        }
        BlockData data = parseBlockData(JsonSupport.requiredString(args, "block"));
        List<BlockChange> changes = new ArrayList<>((int) box.volume());
        for (int y = box.minY; y <= box.maxY; y++) {
            for (int z = box.minZ; z <= box.maxZ; z++) {
                for (int x = box.minX; x <= box.maxX; x++) {
                    Block block = world.getBlockAt(x, y, z);
                    changes.add(new BlockChange(world, x, y, z, block.getBlockData().getAsString()));
                    block.setBlockData(data, false);
                }
            }
        }
        JsonObject result = new JsonObject();
        result.addProperty("changed", changes.size());
        result.addProperty("snapshot", saveSnapshot(context, changes));
        return result;
    }

    private static JsonObject setBlocks(Context context, JsonObject args) throws ToolException {
        JsonElement raw = args.get("blocks");
        if (raw == null || !raw.isJsonArray() || raw.getAsJsonArray().isEmpty()) {
            throw new ToolException("INVALID_ARGS", "blocks must be a non-empty array");
        }
        if (raw.getAsJsonArray().size() > MAX_WRITE_VOLUME) {
            throw new ToolException("INVALID_ARGS", "blocks exceeds " + MAX_WRITE_VOLUME + " entries");
        }
        List<BlockEdit> edits = new ArrayList<>();
        for (JsonElement item : raw.getAsJsonArray()) {
            if (!item.isJsonObject()) throw new ToolException("INVALID_ARGS", "each blocks entry must be an object");
            JsonObject value = item.getAsJsonObject();
            World world = world(JsonSupport.optionalString(value, "dim", null));
            int x = JsonSupport.requiredInt(value, "x");
            int y = JsonSupport.requiredInt(value, "y");
            int z = JsonSupport.requiredInt(value, "z");
            BlockData data = parseBlockData(JsonSupport.requiredString(value, "block"));
            edits.add(new BlockEdit(world, x, y, z, data));
        }
        List<BlockChange> changes = new ArrayList<>();
        for (BlockEdit edit : edits) {
            World world = edit.world();
            int x = edit.x();
            int y = edit.y();
            int z = edit.z();
            Block block = world.getBlockAt(x, y, z);
            changes.add(new BlockChange(world, x, y, z, block.getBlockData().getAsString()));
            block.setBlockData(edit.data(), false);
        }
        JsonObject result = new JsonObject();
        result.addProperty("changed", changes.size());
        result.addProperty("snapshot", saveSnapshot(context, changes));
        return result;
    }

    private static JsonObject undoBlocks(Context context, JsonObject args) throws ToolException {
        String id = JsonSupport.requiredString(args, "snapshot");
        List<BlockChange> changes;
        synchronized (context.snapshots) {
            changes = context.snapshots.remove(id);
        }
        if (changes == null) throw new ToolException("INVALID_ARGS", "Unknown block snapshot: " + id);
        for (BlockChange change : changes) {
            change.world().getBlockAt(change.x(), change.y(), change.z())
                    .setBlockData(Bukkit.createBlockData(change.oldBlockData()), false);
        }
        JsonObject result = new JsonObject();
        result.addProperty("restored", changes.size());
        result.addProperty("snapshot", id);
        return result;
    }

    private static JsonObject teleport(JsonObject args) throws ToolException {
        Player player = player(args);
        World world = world(JsonSupport.optionalString(args, "dim", null));
        double x = requiredCoordinate(args, "x");
        double y = requiredCoordinate(args, "y");
        double z = requiredCoordinate(args, "z");
        float yaw = (float) JsonSupport.optionalDouble(args, "yaw", player.getLocation().getYaw());
        float pitch = (float) JsonSupport.optionalDouble(args, "pitch", player.getLocation().getPitch());
        boolean moved = player.teleport(new Location(world, x, y, z, yaw, pitch));
        JsonObject result = new JsonObject();
        result.addProperty("teleported", moved);
        result.addProperty("name", player.getName());
        return result;
    }

    private static JsonObject giveItem(JsonObject args) throws ToolException {
        Player player = player(args);
        Material material = material(JsonSupport.requiredString(args, "item"));
        int amount = Math.max(1, Math.min(material.getMaxStackSize() * 64,
                JsonSupport.optionalInt(args, "count", 1)));
        ItemStack stack = new ItemStack(material, amount);
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(stack);
        JsonObject result = new JsonObject();
        result.addProperty("given", amount);
        result.addProperty("item", material.getKey().toString());
        int leftover = leftovers.values().stream().mapToInt(ItemStack::getAmount).sum();
        result.addProperty("leftover", leftover);
        return result;
    }

    private static JsonObject setGamemode(JsonObject args) throws ToolException {
        Player player = player(args);
        String raw = JsonSupport.requiredString(args, "gamemode").trim().toUpperCase(Locale.ROOT);
        GameMode mode;
        try {
            mode = GameMode.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new ToolException("INVALID_ARGS", "Unknown gamemode: " + raw);
        }
        player.setGameMode(mode);
        JsonObject result = new JsonObject();
        result.addProperty("name", player.getName());
        result.addProperty("gamemode", mode.name().toLowerCase(Locale.ROOT));
        return result;
    }

    private static JsonObject applyEffect(JsonObject args) throws ToolException {
        Player player = player(args);
        String raw = JsonSupport.requiredString(args, "effect").toUpperCase(Locale.ROOT);
        PotionEffectType type = PotionEffectType.getByName(raw.replace("MINECRAFT:", ""));
        if (type == null) throw new ToolException("INVALID_ARGS", "Unknown effect: " + raw);
        int duration = Math.max(1, Math.min(20 * 60 * 60,
                JsonSupport.optionalInt(args, "duration_seconds", 30)));
        int amplifier = Math.max(0, Math.min(255, JsonSupport.optionalInt(args, "amplifier", 0)));
        player.addPotionEffect(new PotionEffect(type, duration * 20, amplifier));
        JsonObject result = new JsonObject();
        result.addProperty("applied", true);
        result.addProperty("effect", type.getName().toLowerCase(Locale.ROOT));
        result.addProperty("duration_seconds", duration);
        result.addProperty("amplifier", amplifier);
        return result;
    }

    private static JsonObject spawnEntity(JsonObject args) throws ToolException {
        World world = world(JsonSupport.optionalString(args, "dim", null));
        EntityType type = entityType(JsonSupport.requiredString(args, "entity"));
        double x = JsonSupport.optionalDouble(args, "x", world.getSpawnLocation().getX());
        double y = JsonSupport.optionalDouble(args, "y", world.getSpawnLocation().getY());
        double z = JsonSupport.optionalDouble(args, "z", world.getSpawnLocation().getZ());
        Entity entity = world.spawnEntity(new Location(world, x, y, z), type);
        JsonObject result = entity(entity);
        result.addProperty("spawned", true);
        return result;
    }

    private static JsonObject removeEntities(JsonObject args) throws ToolException {
        World world = world(JsonSupport.optionalString(args, "dim", null));
        double x = JsonSupport.optionalDouble(args, "x", world.getSpawnLocation().getX());
        double y = JsonSupport.optionalDouble(args, "y", world.getSpawnLocation().getY());
        double z = JsonSupport.optionalDouble(args, "z", world.getSpawnLocation().getZ());
        double radius = Math.max(1, Math.min(64, JsonSupport.optionalDouble(args, "radius", 16)));
        String typeFilter = JsonSupport.optionalString(args, "type", null);
        EntityType type = typeFilter == null ? null : entityType(typeFilter);
        boolean dryRun = JsonSupport.optionalBoolean(args, "dry_run", true);
        List<Entity> matched = new ArrayList<>();
        for (Entity entity : world.getNearbyEntities(new Location(world, x, y, z), radius, radius, radius)) {
            if (entity instanceof Player) continue;
            if (type != null && entity.getType() != type) continue;
            matched.add(entity);
        }
        if (!dryRun) matched.forEach(Entity::remove);
        JsonObject result = new JsonObject();
        result.addProperty("dry_run", dryRun);
        result.addProperty("matched", matched.size());
        result.addProperty("removed", dryRun ? 0 : matched.size());
        return result;
    }

    private static JsonObject setWorldSpawn(JsonObject args) throws ToolException {
        World world = world(JsonSupport.optionalString(args, "dim", null));
        int x = JsonSupport.requiredInt(args, "x");
        int y = JsonSupport.requiredInt(args, "y");
        int z = JsonSupport.requiredInt(args, "z");
        float yaw = (float) JsonSupport.optionalDouble(args, "yaw", 0);
        float pitch = (float) JsonSupport.optionalDouble(args, "pitch", 0);
        boolean changed = world.setSpawnLocation(x, y, z, yaw);
        JsonObject result = new JsonObject();
        result.addProperty("changed", changed);
        result.add("spawn", position(new Location(world, x, y, z, yaw, pitch)));
        return result;
    }

    private static JsonObject setWorldBorder(JsonObject args) throws ToolException {
        World world = world(JsonSupport.optionalString(args, "dim", null));
        double size = JsonSupport.optionalDouble(args, "size", -1);
        if (size <= 0 || size > 59_999_968) throw new ToolException("INVALID_ARGS", "size must be between 0 and 59999968");
        WorldBorder border = world.getWorldBorder();
        double x = JsonSupport.optionalDouble(args, "x", border.getCenter().getX());
        double z = JsonSupport.optionalDouble(args, "z", border.getCenter().getZ());
        border.setCenter(x, z);
        border.setSize(size);
        JsonObject result = new JsonObject();
        result.add("border", border(border));
        return result;
    }

    private static JsonObject saveWorld(JsonObject args) throws ToolException {
        String name = JsonSupport.optionalString(args, "dim", null);
        if (name == null || name.isBlank() || name.equalsIgnoreCase("all")) {
            for (World world : Bukkit.getWorlds()) world.save();
        } else {
            world(name).save();
        }
        JsonObject result = new JsonObject();
        result.addProperty("saved", true);
        result.addProperty("worlds", name == null || name.isBlank() || name.equalsIgnoreCase("all")
                ? Bukkit.getWorlds().size() : 1);
        return result;
    }

    private static Player player(JsonObject args) throws ToolException {
        String name = JsonSupport.requiredString(args, "name");
        Player exact = Bukkit.getPlayerExact(name);
        if (exact != null) return exact;
        for (Player candidate : Bukkit.getOnlinePlayers()) {
            if (candidate.getName().equalsIgnoreCase(name)) return candidate;
        }
        throw new ToolException("INVALID_ARGS", "Online player not found: " + name);
    }

    private static World world(String name) throws ToolException {
        if (Bukkit.getWorlds().isEmpty()) throw new ToolException("INTERNAL_ERROR", "No loaded worlds");
        if (name == null || name.isBlank()) {
            World overworld = Bukkit.getWorld("world");
            return overworld == null ? Bukkit.getWorlds().get(0) : overworld;
        }
        String normalized = name.trim();
        if (normalized.startsWith("minecraft:")) normalized = normalized.substring("minecraft:".length());
        for (World candidate : Bukkit.getWorlds()) {
            if (candidate.getName().equalsIgnoreCase(name)
                    || candidate.getName().equalsIgnoreCase(normalized)
                    || candidate.getKey().toString().equalsIgnoreCase(name)) return candidate;
        }
        throw new ToolException("INVALID_ARGS", "Loaded world not found: " + name);
    }

    private static Material material(String raw) throws ToolException {
        String normalized = raw.trim();
        Material material = Material.matchMaterial(normalized);
        if (material == null && normalized.contains(":")) {
            material = Material.matchMaterial(normalized.substring(normalized.indexOf(':') + 1));
        }
        if (material == null || material.isAir()) throw new ToolException("INVALID_ARGS", "Unknown or unusable material: " + raw);
        return material;
    }

    private static BlockData parseBlockData(String raw) throws ToolException {
        try {
            return Bukkit.createBlockData(raw.trim());
        } catch (IllegalArgumentException e) {
            return Bukkit.createBlockData(material(raw));
        }
    }

    private static EntityType entityType(String raw) throws ToolException {
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (normalized.contains(":")) normalized = normalized.substring(normalized.indexOf(':') + 1);
        EntityType type = EntityType.fromName(normalized);
        if (type == null || type == EntityType.PLAYER) throw new ToolException("INVALID_ARGS", "Unknown or disallowed entity type: " + raw);
        return type;
    }

    private static double requiredCoordinate(JsonObject args, String key) throws ToolException {
        JsonElement value = args.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new ToolException("INVALID_ARGS", "Missing numeric arg: " + key);
        }
        return value.getAsDouble();
    }

    private static Box box(JsonObject args) throws ToolException {
        JsonSupport.Position min = JsonSupport.requiredPosition(args, "min");
        JsonSupport.Position max = JsonSupport.requiredPosition(args, "max");
        return new Box(Math.min(min.x(), max.x()), Math.min(min.y(), max.y()), Math.min(min.z(), max.z()),
                Math.max(min.x(), max.x()), Math.max(min.y(), max.y()), Math.max(min.z(), max.z()));
    }

    private static String saveSnapshot(Context context, List<BlockChange> changes) {
        String id = "snapshot-" + context.snapshotSequence.getAndIncrement();
        synchronized (context.snapshots) {
            context.snapshots.put(id, List.copyOf(changes));
            while (context.snapshots.size() > 32) {
                String first = context.snapshots.keySet().iterator().next();
                context.snapshots.remove(first);
            }
        }
        return id;
    }

    private static JsonObject block(Block block) {
        JsonObject result = new JsonObject();
        result.addProperty("x", block.getX());
        result.addProperty("y", block.getY());
        result.addProperty("z", block.getZ());
        result.addProperty("id", block.getType().getKey().toString());
        result.addProperty("block_data", block.getBlockData().getAsString());
        result.addProperty("light", block.getLightLevel());
        result.addProperty("sky_light", block.getLightFromSky());
        result.addProperty("block_light", block.getLightFromBlocks());
        result.addProperty("biome", block.getBiome().getKey().toString());
        return result;
    }

    private static JsonObject entity(Entity entity) {
        JsonObject result = new JsonObject();
        result.addProperty("uuid", entity.getUniqueId().toString());
        result.addProperty("type", entity.getType().getKey().toString());
        result.addProperty("name", entity.getCustomName());
        result.addProperty("world", entity.getWorld().getKey().toString());
        result.add("pos", position(entity.getLocation()));
        return result;
    }

    private static JsonObject item(ItemStack item) {
        JsonObject result = new JsonObject();
        result.addProperty("id", item.getType().getKey().toString());
        result.addProperty("count", item.getAmount());
        result.addProperty("max_stack", item.getMaxStackSize());
        if (item.getItemMeta() != null && item.getItemMeta().hasDisplayName()) {
            result.addProperty("custom_name", item.getItemMeta().getDisplayName());
        }
        return result;
    }

    private static JsonObject position(Location location) {
        JsonObject result = new JsonObject();
        result.addProperty("x", location.getX());
        result.addProperty("y", location.getY());
        result.addProperty("z", location.getZ());
        return result;
    }

    private static JsonObject point(int x, int y, int z) {
        JsonObject result = new JsonObject();
        result.addProperty("x", x);
        result.addProperty("y", y);
        result.addProperty("z", z);
        return result;
    }

    private static JsonObject border(WorldBorder border) {
        JsonObject result = new JsonObject();
        result.addProperty("x", border.getCenter().getX());
        result.addProperty("z", border.getCenter().getZ());
        result.addProperty("size", border.getSize());
        result.addProperty("damage_buffer", border.getDamageBuffer());
        result.addProperty("damage_amount", border.getDamageAmount());
        return result;
    }

    private static Path safePath(Context context, String raw) throws ToolException {
        if (raw == null || raw.isBlank()) throw new ToolException("INVALID_ARGS", "path is empty");
        Path input = Path.of(raw);
        if (input.isAbsolute()) throw new ToolException("INVALID_ARGS", "absolute paths are not allowed");
        Path normalized = context.root.resolve(input).normalize();
        if (!normalized.startsWith(context.root)) throw new ToolException("INVALID_ARGS", "path escapes server root");
        try {
            if (Files.exists(normalized)) {
                Path realRoot = context.root.toRealPath();
                Path realPath = normalized.toRealPath();
                if (!realPath.startsWith(realRoot)) throw new ToolException("INVALID_ARGS", "symlink escapes server root");
            }
        } catch (IOException e) {
            throw new ToolException("INVALID_ARGS", "cannot resolve path: " + e.getMessage());
        }
        return normalized;
    }

    private static String relative(Context context, Path path) {
        return context.root.relativize(path).toString().replace('\\', '/');
    }

    private static boolean allowed(List<String> patterns, String relative) {
        return matchingRule(patterns, relative) != null;
    }

    private static String matchingRule(List<String> patterns, String relative) {
        for (String pattern : patterns) {
            if (pattern == null || pattern.isBlank()) continue;
            String normalized = pattern.replace('\\', '/');
            if (normalized.equals("**") || globMatches(normalized, relative)) return pattern;
        }
        return null;
    }

    private static boolean globMatches(String pattern, String relative) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            char character = pattern.charAt(i);
            if (character == '*' && i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                regex.append(".*");
                i++;
            } else if (character == '*') {
                regex.append("[^/]*");
            } else if (character == '?') {
                regex.append("[^/]");
            } else {
                if ("\\.[]{}()+-^$|".indexOf(character) >= 0) regex.append('\\');
                regex.append(character);
            }
        }
        return relative.matches(regex.append('$').toString());
    }

    private static boolean looksText(byte[] bytes) {
        int control = 0;
        for (byte value : bytes) {
            int c = value & 0xff;
            if (c == 0) return false;
            if (c < 9 || (c > 13 && c < 32)) control++;
        }
        return control <= Math.max(1, bytes.length / 100);
    }

    private static long optionalLong(JsonObject args, String key, long fallback) {
        try {
            JsonElement value = args == null ? null : args.get(key);
            return value == null || value.isJsonNull() ? fallback : value.getAsLong();
        } catch (Exception e) {
            return fallback;
        }
    }

    private record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        private long volume() {
            return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        }
    }
}

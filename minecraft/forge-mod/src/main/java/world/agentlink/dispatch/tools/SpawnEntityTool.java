package world.agentlink.dispatch.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.Mob;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolArgs;
import world.agentlink.dispatch.ToolException;
import world.agentlink.nbt.NbtJson;
import world.agentlink.sandbox.BuildZones;
import world.agentlink.transport.ClientSession;

/**
 * Spawn entities at a position, with optional NBT.
 *
 * <p>Rounds out the write surface: the agent could already read and modify entities but not create
 * them. Practical uses are decoration (armour stands, item frames, paintings for a build), and
 * setting up a scenario for a player. NBT support means a named, equipped, no-AI armour stand is one
 * call rather than spawn-then-patch.
 *
 * <h2>Guardrails</h2>
 * <ul>
 *   <li>Count is capped at {@link #MAX_COUNT}. A summon loop is the easiest way for an agent to
 *       accidentally wreck a server's entity budget, and the cap is far above any legitimate
 *       decorating need.</li>
 *   <li>Build zones apply — a spawn inside the agent's plot skips approval, one outside it does not.</li>
 *   <li>{@code minecraft:player} is refused: it isn't summonable and the failure mode is a broken
 *       entity rather than an error.</li>
 * </ul>
 */
public class SpawnEntityTool implements Tool {

    private static final int MAX_COUNT = 64;

    private final MinecraftServer mc;

    public SpawnEntityTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "spawn_entity";
    }

    @Override
    public void declareScope(JsonObject args) {
        try {
            ToolArgs.DoublePos p = ToolArgs.requireDoublePos(args, "pos");
            ToolArgs.IntPos block = new ToolArgs.IntPos(
                    (int) Math.floor(p.x()), (int) Math.floor(p.y()), (int) Math.floor(p.z()));
            BuildZones.declareScope(args, ToolArgs.optString(args, "dim", Dimensions.DEFAULT),
                    ToolArgs.box(block, block));
        } catch (ToolException ignored) {
        }
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        ServerLevel level = Dimensions.resolve(mc, args);
        String typeId = ToolArgs.requireString(args, "type");
        ToolArgs.DoublePos pos = ToolArgs.requireDoublePos(args, "pos");
        int count = ToolArgs.optIntClamped(args, "count", 1, 1, MAX_COUNT);

        ResourceLocation rl = ResourceLocation.tryParse(typeId.trim());
        if (rl == null) throw new ToolException("INVALID_ARGS", "Invalid entity type: " + typeId);
        if (!BuiltInRegistries.ENTITY_TYPE.containsKey(rl)) {
            throw new ToolException("INVALID_ARGS",
                    "Unknown entity type: " + typeId + " — call list_entity_ids to see valid ids");
        }
        if ("minecraft:player".equals(rl.toString())) {
            throw new ToolException("INVALID_ARGS",
                    "Players cannot be summoned. To create a stand-in, spawn an armor_stand"
                            + " or use a mod-provided fake-player entity.");
        }
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(rl);
        if (type == null) throw new ToolException("INVALID_ARGS", "Unknown entity type: " + typeId);

        // Build the spawn NBT once. Vanilla requires the type id inside the compound when loading
        // an entity from NBT, so we always set it rather than trusting the caller's copy.
        CompoundTag nbt = new CompoundTag();
        String snbt = ToolArgs.optString(args, "nbt", null);
        if (snbt != null && !snbt.isBlank()) nbt = NbtJson.parseSnbtCompound(snbt);
        nbt = nbt.copy();
        nbt.putString("id", rl.toString());

        boolean noAi = ToolArgs.optBool(args, "no_ai", false);
        boolean persistent = ToolArgs.optBool(args, "persistent", false);
        String customName = ToolArgs.optString(args, "custom_name", null);

        // An entity added to an unloaded chunk is written to disk but never enters the level's entity
        // index, so a follow-up get_nbt / modify_entity by UUID reports NOT_FOUND. That reads as a bug
        // in those tools rather than a loading issue, so we say it here where the cause is visible.
        net.minecraft.core.BlockPos blockPos = new net.minecraft.core.BlockPos(
                (int) Math.floor(pos.x()), (int) Math.floor(pos.y()), (int) Math.floor(pos.z()));
        boolean chunkLoaded = level.hasChunkAt(blockPos);

        JsonArray spawned = new JsonArray();
        int failed = 0;
        for (int i = 0; i < count; i++) {
            CompoundTag perEntity = nbt.copy();
            Entity entity = EntityType.loadEntityRecursive(perEntity, level, e -> {
                e.moveTo(pos.x(), pos.y(), pos.z(), e.getYRot(), e.getXRot());
                return e;
            });
            if (entity == null) {
                failed++;
                continue;
            }
            if (customName != null && !customName.isBlank()) {
                entity.setCustomName(net.minecraft.network.chat.Component.literal(customName));
                entity.setCustomNameVisible(ToolArgs.optBool(args, "name_visible", false));
            }
            if (entity instanceof Mob mob) {
                if (noAi) mob.setNoAi(true);
                // Persistence prevents the mob from being despawned by distance — decoration and
                // scenario mobs the agent placed deliberately should not evaporate.
                if (persistent) mob.setPersistenceRequired();
                mob.finalizeSpawn(level, level.getCurrentDifficultyAt(mob.blockPosition()),
                        MobSpawnType.COMMAND, null, null);
            }
            if (!level.addFreshEntity(entity)) {
                failed++;
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("uuid", entity.getUUID().toString());
            o.addProperty("x", entity.getX());
            o.addProperty("y", entity.getY());
            o.addProperty("z", entity.getZ());
            spawned.add(o);
        }

        JsonObject r = new JsonObject();
        r.addProperty("dim", Dimensions.idOf(level));
        r.addProperty("type", rl.toString());
        r.add("pos", pos.toJson());
        r.addProperty("requested", count);
        r.addProperty("spawned", spawned.size());
        r.addProperty("failed", failed);
        r.addProperty("chunk_loaded", chunkLoaded);
        r.add("entities", spawned);
        if (failed > 0) {
            r.addProperty("note", failed + " entity/entities could not be created — check that the"
                    + " NBT matches this entity type, and that the chunk is loaded.");
        } else if (!chunkLoaded) {
            r.addProperty("warning", "The target chunk is not loaded. The entity was created, but it"
                    + " is not in the live entity index, so get_nbt / modify_entity / remove_entities"
                    + " by UUID will report NOT_FOUND until something loads that chunk. Call"
                    + " force_load_chunks over your work area first, and release it when finished.");
        }
        return r;
    }
}

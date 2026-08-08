package world.agentlink.api;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Native entity creation with optional NBT and common mob initialization controls. */
public final class AgentEntitySpawnApi {

    public static final int MAX_COUNT = 64;
    public record SpawnResult(List<Entity> entities, int failed) {
        public SpawnResult {
            entities = List.copyOf(entities == null ? List.of() : entities);
        }
    }

    AgentEntitySpawnApi() {}

    public SpawnResult spawn(ServerLevel level, ResourceLocation typeId,
                             double x, double y, double z, int count, CompoundTag nbt,
                             String customName, boolean nameVisible, boolean noAi,
                             boolean persistent) throws AgentApiException {
        if (level == null || typeId == null) {
            throw new AgentApiException("INVALID_ARGS", "level and typeId are required");
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new AgentApiException("INVALID_ARGS", "spawn coordinates must be finite");
        }
        if (count < 1 || count > MAX_COUNT) {
            throw new AgentApiException("INVALID_ARGS", "count must be 1.." + MAX_COUNT);
        }
        if (!BuiltInRegistries.ENTITY_TYPE.containsKey(typeId)
                || "minecraft:player".equals(typeId.toString())) {
            throw new AgentApiException("INVALID_ARGS", "Entity type cannot be spawned: " + typeId);
        }
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(typeId);
        if (type == null) throw new AgentApiException("INVALID_ARGS", "Unknown entity type: " + typeId);

        CompoundTag template = nbt == null ? new CompoundTag() : nbt.copy();
        template.putString("id", typeId.toString());
        List<Entity> spawned = new ArrayList<>();
        int failed = 0;
        for (int i = 0; i < count; i++) {
            Entity entity = EntityType.loadEntityRecursive(template.copy(), level, value -> {
                value.moveTo(x, y, z, value.getYRot(), value.getXRot());
                return value;
            });
            if (entity == null) {
                failed++;
                continue;
            }
            if (customName != null && !customName.isBlank()) {
                entity.setCustomName(Component.literal(customName));
                entity.setCustomNameVisible(nameVisible);
            }
            if (entity instanceof Mob mob) {
                if (noAi) mob.setNoAi(true);
                if (persistent) mob.setPersistenceRequired();
                mob.finalizeSpawn(level, level.getCurrentDifficultyAt(mob.blockPosition()),
                        MobSpawnType.COMMAND, null, null);
            }
            if (!level.addFreshEntity(entity)) {
                failed++;
                continue;
            }
            spawned.add(entity);
        }
        return new SpawnResult(spawned, failed);
    }
}

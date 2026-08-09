package world.agentlink.api;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.UUID;

/** Loaded-entity lookup and typed controls that complement {@code set_nbt}. */
public final class AgentEntityApi {

    public record TeleportResult(String fromDimension, double fromX, double fromY, double fromZ,
                                 String dimension, double x, double y, double z,
                                 float yaw, float pitch, boolean crossDimension) {}

    AgentEntityApi() {}

    public Entity resolve(MinecraftServer server, String name, UUID uuid) throws AgentApiException {
        if (server == null) throw new AgentApiException("SERVER_UNAVAILABLE", "server is not running");
        if (name != null && !name.isBlank()) return AgentLinkApi.players().online(server, name);
        if (uuid == null) throw new AgentApiException("INVALID_ARGS", "name or uuid is required");
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(uuid);
            if (entity != null) return entity;
        }
        throw new AgentApiException("NOT_FOUND", "No loaded entity with UUID " + uuid
                + ". Force-load its chunk before operating on it.");
    }

    public boolean mount(Entity rider, Entity vehicle) throws AgentApiException {
        if (rider == null || vehicle == null) throw new AgentApiException("INVALID_ARGS", "rider and vehicle are required");
        if (rider == vehicle) throw new AgentApiException("INVALID_ARGS", "rider and vehicle must differ");
        return rider.startRiding(vehicle, true);
    }

    public void dismount(Entity rider) throws AgentApiException {
        if (rider == null) throw new AgentApiException("INVALID_ARGS", "entity is required");
        rider.stopRiding();
    }

    public boolean damage(Entity entity, float amount, String cause) throws AgentApiException {
        if (!(entity instanceof LivingEntity living)) {
            throw new AgentApiException("INVALID_ARGS", "damage only applies to living entities");
        }
        if (!Float.isFinite(amount) || amount <= 0) {
            throw new AgentApiException("INVALID_ARGS", "amount must be a finite number > 0");
        }
        DamageSource source = damageSource(living, cause);
        return living.hurt(source, amount);
    }

    public void heal(Entity entity, float amount) throws AgentApiException {
        if (!(entity instanceof LivingEntity living)) {
            throw new AgentApiException("INVALID_ARGS", "heal only applies to living entities");
        }
        if (!Float.isFinite(amount) || amount <= 0) {
            throw new AgentApiException("INVALID_ARGS", "amount must be a finite number > 0");
        }
        living.heal(amount);
    }

    public void setHealth(Entity entity, float health) throws AgentApiException {
        if (!(entity instanceof LivingEntity living)) {
            throw new AgentApiException("INVALID_ARGS", "health only applies to living entities");
        }
        if (!Float.isFinite(health) || health <= 0 || health > living.getMaxHealth()) {
            throw new AgentApiException("INVALID_ARGS", "health must be > 0 and <= " + living.getMaxHealth());
        }
        living.setHealth(health);
    }

    public void setFireSeconds(Entity entity, int seconds) throws AgentApiException {
        requireEntity(entity);
        if (seconds < 0 || seconds > 3600) {
            throw new AgentApiException("INVALID_ARGS", "fire_seconds must be 0..3600");
        }
        if (seconds == 0) entity.clearFire(); else entity.igniteForSeconds(seconds);
    }

    public void setCustomName(Entity entity, String name, Boolean visible) throws AgentApiException {
        requireEntity(entity);
        entity.setCustomName(name == null || name.isBlank() ? null : Component.literal(name));
        if (visible != null) entity.setCustomNameVisible(visible);
    }

    public void setCustomNameVisible(Entity entity, boolean visible) throws AgentApiException {
        requireEntity(entity);
        entity.setCustomNameVisible(visible);
    }

    public void setFlags(Entity entity, Boolean silent, Boolean invulnerable,
                         Boolean glowing, Boolean noGravity) throws AgentApiException {
        requireEntity(entity);
        if (silent != null) entity.setSilent(silent);
        if (invulnerable != null) entity.setInvulnerable(invulnerable);
        if (glowing != null) entity.setGlowingTag(glowing);
        if (noGravity != null) entity.setNoGravity(noGravity);
    }

    public void setNoAi(Entity entity, boolean noAi) throws AgentApiException {
        requireEntity(entity);
        if (!(entity instanceof Mob mob)) throw new AgentApiException("INVALID_ARGS", "no_ai only applies to mobs");
        mob.setNoAi(noAi);
    }

    /** Vanilla has a setter for persistence but no symmetric un-setter. */
    public void setPersistent(Entity entity, boolean persistent) throws AgentApiException {
        requireEntity(entity);
        if (!(entity instanceof Mob mob)) {
            throw new AgentApiException("INVALID_ARGS", "persistent only applies to mobs");
        }
        if (!persistent) {
            throw new AgentApiException("UNSUPPORTED",
                    "vanilla has no persistent=false setter; use NBT for that operation");
        }
        mob.setPersistenceRequired();
    }

    public void kill(Entity entity) throws AgentApiException {
        if (entity == null) throw new AgentApiException("INVALID_ARGS", "entity is required");
        entity.kill();
    }

    public void discard(Entity entity) throws AgentApiException {
        if (entity == null) throw new AgentApiException("INVALID_ARGS", "entity is required");
        entity.discard();
    }

    public void setVelocity(Entity entity, double x, double y, double z) throws AgentApiException {
        requireEntity(entity);
        requireFinite(x, "x"); requireFinite(y, "y"); requireFinite(z, "z");
        entity.setDeltaMovement(x, y, z);
        entity.hasImpulse = true;
    }

    public void setRotation(Entity entity, float yaw, float pitch) throws AgentApiException {
        requireEntity(entity);
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new AgentApiException("INVALID_ARGS", "yaw and pitch must be finite");
        }
        entity.setYRot(yaw);
        entity.setXRot(pitch);
        entity.setYHeadRot(yaw);
    }

    /** Teleport a loaded entity, including a player crossing dimensions. */
    public TeleportResult teleport(Entity entity, ServerLevel targetLevel, double x, double y, double z,
                                   float yaw, float pitch) throws AgentApiException {
        if (entity == null || targetLevel == null) {
            throw new AgentApiException("INVALID_ARGS", "entity and targetLevel are required");
        }
        requireFinite(x, "x"); requireFinite(y, "y"); requireFinite(z, "z");
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new AgentApiException("INVALID_ARGS", "yaw and pitch must be finite");
        }
        String fromDimension = entity.level().dimension().location().toString();
        double fromX = entity.getX(), fromY = entity.getY(), fromZ = entity.getZ();
        boolean crossDimension = entity.level() != targetLevel;
        Entity moved = entity;
        if (entity instanceof ServerPlayer player) {
            player.teleportTo(targetLevel, x, y, z, Collections.emptySet(), yaw, pitch);
        } else {
            if (crossDimension) {
                Entity changed = entity.changeDimension(new DimensionTransition(
                        targetLevel, new Vec3(x, y, z), Vec3.ZERO, yaw, pitch,
                        DimensionTransition.DO_NOTHING));
                if (changed != null) moved = changed;
            }
            moved.teleportTo(x, y, z);
            moved.setYRot(yaw);
            moved.setXRot(pitch);
            moved.setYHeadRot(yaw);
        }
        return new TeleportResult(fromDimension, fromX, fromY, fromZ,
                targetLevel.dimension().location().toString(), moved.getX(), moved.getY(), moved.getZ(),
                moved.getYRot(), moved.getXRot(), crossDimension);
    }

    public void setEquipment(Entity entity, String slotName, String itemSpec, int count)
            throws AgentApiException {
        requireEntity(entity);
        if (!(entity instanceof LivingEntity living)) {
            throw new AgentApiException("INVALID_ARGS", "equipment only applies to living entities");
        }
        EquipmentSlot slot = EquipmentSlot.byName(slotName == null ? "" : slotName.trim().toLowerCase());
        if (slot == null) throw new AgentApiException("INVALID_ARGS",
                "Unknown equipment slot: " + slotName + " (head, chest, legs, feet, mainhand, offhand)");
        ItemStack stack = AgentLinkApi.items().parse(itemSpec, count);
        living.setItemSlot(slot, stack);
    }

    public double setAttribute(Entity entity, String attributeId, double baseValue)
            throws AgentApiException {
        requireEntity(entity);
        if (!(entity instanceof LivingEntity living)) {
            throw new AgentApiException("INVALID_ARGS", "attributes only apply to living entities");
        }
        if (!Double.isFinite(baseValue)) throw new AgentApiException("INVALID_ARGS", "base_value must be finite");
        ResourceLocation id = ResourceLocation.tryParse(attributeId == null ? "" : attributeId.trim());
        if (id == null || !BuiltInRegistries.ATTRIBUTE.containsKey(id)) {
            throw new AgentApiException("INVALID_ARGS", "Unknown attribute: " + attributeId);
        }
        var attributeHolder = BuiltInRegistries.ATTRIBUTE.getHolder(id);
        if (attributeHolder.isEmpty()) {
            throw new AgentApiException("INVALID_ARGS", "Unknown attribute: " + attributeId);
        }
        Holder<Attribute> attribute = attributeHolder.get();
        AttributeInstance instance = living.getAttribute(attribute);
        if (instance == null) throw new AgentApiException("INVALID_ARGS",
                "Entity does not expose attribute: " + attributeId);
        instance.setBaseValue(baseValue);
        return instance.getValue();
    }

    private static DamageSource damageSource(LivingEntity living, String cause) throws AgentApiException {
        String value = cause == null || cause.isBlank() ? "generic" : cause.trim().toLowerCase();
        return switch (value) {
            case "generic" -> living.damageSources().generic();
            case "magic" -> living.damageSources().magic();
            case "fire", "in_fire" -> living.damageSources().inFire();
            case "on_fire" -> living.damageSources().onFire();
            case "lava" -> living.damageSources().lava();
            case "fall" -> living.damageSources().fall();
            case "drown" -> living.damageSources().drown();
            case "starve" -> living.damageSources().starve();
            case "wither" -> living.damageSources().wither();
            case "freeze" -> living.damageSources().freeze();
            default -> throw new AgentApiException("INVALID_ARGS",
                    "Unknown damage cause: " + cause + " (generic, magic, fire, lava, fall, drown, starve, wither, freeze)");
        };
    }

    private static void requireFinite(double value, String name) throws AgentApiException {
        if (!Double.isFinite(value)) throw new AgentApiException("INVALID_ARGS", name + " must be finite");
    }

    private static void requireEntity(Entity entity) throws AgentApiException {
        if (entity == null) throw new AgentApiException("NOT_FOUND", "entity is required");
    }
}

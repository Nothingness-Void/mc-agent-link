package world.agentlink.api;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.List;

/** Typed status-effect lookup, application, and removal for living entities. */
public final class AgentEffectApi {

    public static final int MAX_SECONDS = 1_000_000;
    public static final int MAX_AMPLIFIER = 255;

    public record EffectState(String id, int amplifier, int durationTicks) {}

    AgentEffectApi() {}

    public MobEffect resolve(String id) throws AgentApiException {
        ResourceLocation resource = ResourceLocation.tryParse(id == null ? "" : id.trim());
        if (resource == null || !BuiltInRegistries.MOB_EFFECT.containsKey(resource)) {
            throw new AgentApiException("INVALID_ARGS", "Unknown effect: " + id);
        }
        return BuiltInRegistries.MOB_EFFECT.get(resource);
    }

    public List<EffectState> active(LivingEntity entity) throws AgentApiException {
        requireEntity(entity);
        List<EffectState> result = new ArrayList<>();
        for (MobEffectInstance instance : entity.getActiveEffects()) {
            ResourceLocation id = BuiltInRegistries.MOB_EFFECT.getKey(instance.getEffect());
            result.add(new EffectState(id == null ? "unknown" : id.toString(),
                    instance.getAmplifier(), instance.getDuration()));
        }
        return List.copyOf(result);
    }

    public boolean apply(LivingEntity entity, String effectId, int seconds, int amplifier,
                         boolean ambient, boolean showParticles, boolean showIcon)
            throws AgentApiException {
        requireEntity(entity);
        MobEffect effect = resolve(effectId);
        if (seconds < 1 || seconds > MAX_SECONDS) {
            throw new AgentApiException("INVALID_ARGS", "seconds must be 1.." + MAX_SECONDS);
        }
        if (amplifier < 0 || amplifier > MAX_AMPLIFIER) {
            throw new AgentApiException("INVALID_ARGS", "amplifier must be 0.." + MAX_AMPLIFIER);
        }
        int ticks = effect.isInstantenous() ? 1 : seconds * 20;
        return entity.addEffect(new MobEffectInstance(effect, ticks, amplifier,
                ambient, showParticles, showIcon));
    }

    public boolean clear(LivingEntity entity, String effectId) throws AgentApiException {
        requireEntity(entity);
        if (effectId == null || effectId.isBlank()) {
            throw new AgentApiException("INVALID_ARGS", "effectId is required; use clearAll for every effect");
        }
        return entity.removeEffect(resolve(effectId));
    }

    public int clearAll(LivingEntity entity) throws AgentApiException {
        requireEntity(entity);
        int count = entity.getActiveEffects().size();
        entity.removeAllEffects();
        return count;
    }

    private static void requireEntity(LivingEntity entity) throws AgentApiException {
        if (entity == null) throw new AgentApiException("INVALID_ARGS", "living entity is required");
    }
}

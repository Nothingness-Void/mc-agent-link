package world.agentlink.dispatch.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolArgs;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

/**
 * Apply or clear a status effect on a player or mob.
 *
 * <p>The practical use is protecting a subject while the agent works — night vision and fire
 * resistance while building in the nether, slow falling before a long teleport, or clearing a
 * lingering debuff the agent caused. {@code /effect} could do it, but only through
 * arbitrary-console permission, and it reports nothing structured about what was already active.
 *
 * <p>{@code mode:"clear"} removes one effect, or all of them when {@code effect} is omitted.
 */
public class ApplyEffectTool implements Tool {

    /** Vanilla's own ceiling for /effect durations, in seconds. */
    private static final int MAX_SECONDS = 1_000_000;
    private static final int MAX_AMPLIFIER = 255;

    private final MinecraftServer mc;
    private final GetNbtTool resolver;

    public ApplyEffectTool(MinecraftServer mc) {
        this.mc = mc;
        this.resolver = new GetNbtTool(mc);
    }

    @Override
    public String name() {
        return "apply_effect";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        Entity entity = resolveSubject(args);
        if (!(entity instanceof LivingEntity living)) {
            throw new ToolException("INVALID_ARGS",
                    "Status effects only apply to living entities; "
                            + entity.getType().builtInRegistryHolder().key().location() + " is not one");
        }

        String mode = ToolArgs.optEnum(args, "mode", "apply");
        JsonObject r = new JsonObject();
        r.addProperty("uuid", living.getUUID().toString());
        r.addProperty("type", living.getType().builtInRegistryHolder().key().location().toString());
        if (living instanceof net.minecraft.server.level.ServerPlayer sp) {
            r.addProperty("name", sp.getGameProfile().getName());
        }
        r.addProperty("mode", mode);

        if ("clear".equals(mode)) {
            String effectId = ToolArgs.optString(args, "effect", null);
            if (effectId == null || effectId.isBlank()) {
                int removed = living.getActiveEffects().size();
                living.removeAllEffects();
                r.addProperty("cleared_all", true);
                r.addProperty("effects_removed", removed);
            } else {
                MobEffect effect = resolveEffect(effectId);
                boolean had = living.hasEffect(effect);
                living.removeEffect(effect);
                r.addProperty("effect", effectId);
                r.addProperty("effects_removed", had ? 1 : 0);
                if (!had) r.addProperty("note", "That effect was not active.");
            }
            r.add("active_effects", activeEffects(living));
            return r;
        }
        if (!"apply".equals(mode)) {
            throw new ToolException("INVALID_ARGS", "mode must be \"apply\" or \"clear\"");
        }

        String effectId = ToolArgs.requireString(args, "effect");
        MobEffect effect = resolveEffect(effectId);
        int seconds = ToolArgs.optIntClamped(args, "seconds", 30, 1, MAX_SECONDS);
        int amplifier = ToolArgs.optIntClamped(args, "amplifier", 0, 0, MAX_AMPLIFIER);
        boolean ambient = ToolArgs.optBool(args, "ambient", false);
        boolean showParticles = ToolArgs.optBool(args, "show_particles", true);
        boolean showIcon = ToolArgs.optBool(args, "show_icon", true);

        // Instant effects (harming, healing) ignore duration; ticks would just be wasted.
        int ticks = effect.isInstantenous() ? 1 : seconds * 20;
        boolean applied = living.addEffect(
                new MobEffectInstance(effect, ticks, amplifier, ambient, showParticles, showIcon));

        r.addProperty("effect", effectId);
        r.addProperty("seconds", effect.isInstantenous() ? 0 : seconds);
        r.addProperty("amplifier", amplifier);
        r.addProperty("instantaneous", effect.isInstantenous());
        r.addProperty("applied", applied);
        if (!applied) {
            r.addProperty("note", "Rejected — the target is immune to this effect, or an existing"
                    + " instance is stronger or longer-lasting.");
        }
        r.add("active_effects", activeEffects(living));
        return r;
    }

    private static JsonArray activeEffects(LivingEntity living) {
        JsonArray arr = new JsonArray();
        for (MobEffectInstance inst : living.getActiveEffects()) {
            JsonObject o = new JsonObject();
            ResourceLocation id = BuiltInRegistries.MOB_EFFECT.getKey(inst.getEffect());
            o.addProperty("effect", id == null ? "unknown" : id.toString());
            o.addProperty("amplifier", inst.getAmplifier());
            o.addProperty("duration_ticks", inst.getDuration());
            o.addProperty("duration_seconds", inst.getDuration() / 20);
            arr.add(o);
        }
        return arr;
    }

    private MobEffect resolveEffect(String id) throws ToolException {
        ResourceLocation rl = ResourceLocation.tryParse(id.trim());
        if (rl == null) throw new ToolException("INVALID_ARGS", "Invalid effect id: " + id);
        MobEffect effect = BuiltInRegistries.MOB_EFFECT.get(rl);
        if (effect == null) {
            throw new ToolException("INVALID_ARGS",
                    "Unknown effect: " + id + " (e.g. minecraft:night_vision, minecraft:fire_resistance)");
        }
        return effect;
    }

    private Entity resolveSubject(JsonObject args) throws ToolException {
        String name = ToolArgs.optString(args, "name", null);
        if (name != null && !name.isBlank()) return resolver.requirePlayer(name);
        String uuid = ToolArgs.optString(args, "uuid", null);
        if (uuid != null && !uuid.isBlank()) return resolver.resolveEntity(args);
        throw new ToolException("INVALID_ARGS", "Provide `name` (a player) or `uuid` (a loaded entity)");
    }
}

package world.agentlink.dispatch.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import world.agentlink.api.AgentApiException;
import world.agentlink.api.AgentEffectApi;
import world.agentlink.api.AgentLinkApi;
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
    private static final int MAX_SECONDS = AgentEffectApi.MAX_SECONDS;
    private static final int MAX_AMPLIFIER = AgentEffectApi.MAX_AMPLIFIER;

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
                int removed = clearAll(living);
                r.addProperty("cleared_all", true);
                r.addProperty("effects_removed", removed);
            } else {
                Holder<MobEffect> effect = resolveEffect(effectId);
                boolean had = living.hasEffect(effect);
                boolean removed = clear(living, effectId);
                r.addProperty("effect", effectId);
                r.addProperty("effects_removed", removed ? 1 : 0);
                if (!had) r.addProperty("note", "That effect was not active.");
            }
            r.add("active_effects", activeEffects(living));
            return r;
        }
        if (!"apply".equals(mode)) {
            throw new ToolException("INVALID_ARGS", "mode must be \"apply\" or \"clear\"");
        }

        String effectId = ToolArgs.requireString(args, "effect");
        Holder<MobEffect> effect = resolveEffect(effectId);
        int seconds = ToolArgs.optIntClamped(args, "seconds", 30, 1, MAX_SECONDS);
        int amplifier = ToolArgs.optIntClamped(args, "amplifier", 0, 0, MAX_AMPLIFIER);
        boolean ambient = ToolArgs.optBool(args, "ambient", false);
        boolean showParticles = ToolArgs.optBool(args, "show_particles", true);
        boolean showIcon = ToolArgs.optBool(args, "show_icon", true);

        // Instant effects (harming, healing) ignore duration; ticks would just be wasted.
        boolean applied = apply(living, effectId, seconds, amplifier, ambient, showParticles, showIcon);

        r.addProperty("effect", effectId);
        r.addProperty("seconds", effect.value().isInstantenous() ? 0 : seconds);
        r.addProperty("amplifier", amplifier);
        r.addProperty("instantaneous", effect.value().isInstantenous());
        r.addProperty("applied", applied);
        if (!applied) {
            r.addProperty("note", "Rejected — the target is immune to this effect, or an existing"
                    + " instance is stronger or longer-lasting.");
        }
        r.add("active_effects", activeEffects(living));
        return r;
    }

    private static JsonArray activeEffects(LivingEntity living) throws ToolException {
        JsonArray arr = new JsonArray();
        try {
            for (AgentEffectApi.EffectState state : AgentLinkApi.effects().active(living)) {
                JsonObject o = new JsonObject();
                o.addProperty("effect", state.id());
                o.addProperty("amplifier", state.amplifier());
                o.addProperty("duration_ticks", state.durationTicks());
                o.addProperty("duration_seconds", state.durationTicks() / 20);
                arr.add(o);
            }
        } catch (AgentApiException ex) {
            throw new ToolException(ex.code(), ex.getMessage());
        }
        return arr;
    }

    private Holder<MobEffect> resolveEffect(String id) throws ToolException {
        try {
            return AgentLinkApi.effects().resolve(id);
        } catch (AgentApiException ex) {
            throw new ToolException(ex.code(), ex.getMessage());
        }
    }

    private static boolean apply(LivingEntity entity, String effectId, int seconds, int amplifier,
                                 boolean ambient, boolean showParticles, boolean showIcon)
            throws ToolException {
        try {
            return AgentLinkApi.effects().apply(entity, effectId, seconds, amplifier,
                    ambient, showParticles, showIcon);
        } catch (AgentApiException ex) {
            throw new ToolException(ex.code(), ex.getMessage());
        }
    }

    private static boolean clear(LivingEntity entity, String effectId) throws ToolException {
        try {
            return AgentLinkApi.effects().clear(entity, effectId);
        } catch (AgentApiException ex) {
            throw new ToolException(ex.code(), ex.getMessage());
        }
    }

    private static int clearAll(LivingEntity entity) throws ToolException {
        try {
            return AgentLinkApi.effects().clearAll(entity);
        } catch (AgentApiException ex) {
            throw new ToolException(ex.code(), ex.getMessage());
        }
    }

    private Entity resolveSubject(JsonObject args) throws ToolException {
        String name = ToolArgs.optString(args, "name", null);
        if (name != null && !name.isBlank()) return resolver.requirePlayer(name);
        String uuid = ToolArgs.optString(args, "uuid", null);
        if (uuid != null && !uuid.isBlank()) return resolver.resolveEntity(args);
        throw new ToolException("INVALID_ARGS", "Provide `name` (a player) or `uuid` (a loaded entity)");
    }
}

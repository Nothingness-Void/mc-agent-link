package world.agentlink.dispatch.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolArgs;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

/**
 * Change the common, safe attributes of an existing entity.
 *
 * <p>{@code set_nbt} can do all of this, but it requires the agent to know each entity type's NBT
 * key names and their exact tag types ({@code NoAI:1b}, not {@code NoAI:1}) — and a mistake there
 * produces a silently broken entity rather than an error. This tool covers the handful of things
 * that are actually asked for, with validation and typed arguments. {@code set_nbt} remains the
 * escape hatch for everything else.
 *
 * <p>Every field is optional; only what you pass changes. The response reports before/after for each
 * one, so a no-op is visible rather than assumed.
 */
public class ModifyEntityTool implements Tool {

    private final MinecraftServer mc;
    private final GetNbtTool resolver;

    public ModifyEntityTool(MinecraftServer mc) {
        this.mc = mc;
        this.resolver = new GetNbtTool(mc);
    }

    @Override
    public String name() {
        return "modify_entity";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        Entity entity = resolveSubject(args);
        JsonArray applied = new JsonArray();

        if (args.has("custom_name")) {
            String before = entity.hasCustomName() ? entity.getCustomName().getString() : null;
            String name = ToolArgs.optString(args, "custom_name", "");
            if (name.isEmpty()) {
                entity.setCustomName(null);
            } else {
                entity.setCustomName(Component.literal(name));
            }
            applied.add(change("custom_name", before, name.isEmpty() ? null : name));
        }
        if (args.has("name_visible")) {
            boolean before = entity.isCustomNameVisible();
            boolean value = ToolArgs.optBool(args, "name_visible", before);
            entity.setCustomNameVisible(value);
            applied.add(change("name_visible", before, value));
        }
        if (args.has("silent")) {
            boolean before = entity.isSilent();
            boolean value = ToolArgs.optBool(args, "silent", before);
            entity.setSilent(value);
            applied.add(change("silent", before, value));
        }
        if (args.has("invulnerable")) {
            boolean before = entity.isInvulnerable();
            boolean value = ToolArgs.optBool(args, "invulnerable", before);
            entity.setInvulnerable(value);
            applied.add(change("invulnerable", before, value));
        }
        if (args.has("glowing")) {
            boolean before = entity.isCurrentlyGlowing();
            boolean value = ToolArgs.optBool(args, "glowing", before);
            entity.setGlowingTag(value);
            applied.add(change("glowing", before, value));
        }
        if (args.has("no_gravity")) {
            boolean before = entity.isNoGravity();
            boolean value = ToolArgs.optBool(args, "no_gravity", before);
            entity.setNoGravity(value);
            applied.add(change("no_gravity", before, value));
        }
        if (args.has("fire_seconds")) {
            int seconds = ToolArgs.optIntClamped(args, "fire_seconds", 0, 0, 3600);
            if (seconds <= 0) {
                entity.clearFire();
            } else {
                entity.setSecondsOnFire(seconds);
            }
            applied.add(change("fire_seconds", null, seconds));
        }

        if (args.has("health")) {
            if (!(entity instanceof LivingEntity living)) {
                throw new ToolException("INVALID_ARGS", "health only applies to living entities");
            }
            float before = living.getHealth();
            float value = (float) ToolArgs.requireDouble(args, "health");
            if (value <= 0) {
                throw new ToolException("INVALID_ARGS",
                        "health must be > 0. To remove an entity use remove_entities.");
            }
            float max = living.getMaxHealth();
            if (value > max) {
                throw new ToolException("INVALID_ARGS",
                        "health " + value + " exceeds this entity's max health " + max
                                + ". Raise the max first via set_nbt on the"
                                + " minecraft:generic.max_health attribute.");
            }
            living.setHealth(value);
            applied.add(change("health", before, value));
        }

        if (args.has("no_ai")) {
            if (!(entity instanceof Mob mob)) {
                throw new ToolException("INVALID_ARGS", "no_ai only applies to mobs");
            }
            boolean before = mob.isNoAi();
            boolean value = ToolArgs.optBool(args, "no_ai", before);
            mob.setNoAi(value);
            applied.add(change("no_ai", before, value));
        }
        if (args.has("persistent")) {
            if (!(entity instanceof Mob mob)) {
                throw new ToolException("INVALID_ARGS", "persistent only applies to mobs");
            }
            boolean value = ToolArgs.optBool(args, "persistent", true);
            if (value) {
                mob.setPersistenceRequired();
                applied.add(change("persistent", false, true));
            } else {
                // Vanilla exposes no un-setter; NBT is the honest route and we say so rather than
                // silently doing nothing.
                throw new ToolException("INVALID_ARGS",
                        "persistent cannot be turned off through this tool (vanilla has no setter)."
                                + " Use set_nbt with {PersistenceRequired:0b}.");
            }
        }

        JsonObject r = new JsonObject();
        r.addProperty("uuid", entity.getUUID().toString());
        r.addProperty("type", entity.getType().builtInRegistryHolder().key().location().toString());
        r.addProperty("dim", entity.level().dimension().location().toString());
        if (entity instanceof net.minecraft.server.level.ServerPlayer sp) {
            r.addProperty("name", sp.getGameProfile().getName());
        }
        r.add("changes", applied);
        r.addProperty("changed_fields", applied.size());
        if (applied.isEmpty()) {
            r.addProperty("note", "No recognized fields were supplied, so nothing changed. Supported:"
                    + " custom_name, name_visible, silent, invulnerable, glowing, no_gravity,"
                    + " fire_seconds, health, no_ai, persistent. For anything else use set_nbt.");
        }
        return r;
    }

    private static JsonObject change(String field, Object before, Object after) {
        JsonObject o = new JsonObject();
        o.addProperty("field", field);
        if (before == null) {
            o.add("before", com.google.gson.JsonNull.INSTANCE);
        } else if (before instanceof Boolean b) {
            o.addProperty("before", b);
        } else if (before instanceof Number n) {
            o.addProperty("before", n);
        } else {
            o.addProperty("before", String.valueOf(before));
        }
        if (after == null) {
            o.add("after", com.google.gson.JsonNull.INSTANCE);
        } else if (after instanceof Boolean b) {
            o.addProperty("after", b);
        } else if (after instanceof Number n) {
            o.addProperty("after", n);
        } else {
            o.addProperty("after", String.valueOf(after));
        }
        return o;
    }

    private Entity resolveSubject(JsonObject args) throws ToolException {
        String name = ToolArgs.optString(args, "name", null);
        if (name != null && !name.isBlank()) return resolver.requirePlayer(name);
        String uuid = ToolArgs.optString(args, "uuid", null);
        if (uuid != null && !uuid.isBlank()) return resolver.resolveEntity(args);
        throw new ToolException("INVALID_ARGS", "Provide `name` (a player) or `uuid` (a loaded entity)");
    }
}

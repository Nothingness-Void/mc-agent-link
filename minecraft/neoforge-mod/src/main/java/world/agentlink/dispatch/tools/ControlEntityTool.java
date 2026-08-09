package world.agentlink.dispatch.tools;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import world.agentlink.api.AgentApiException;
import world.agentlink.api.AgentLinkApi;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolArgs;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

import java.util.UUID;

/** Typed entity relationships, motion, health, equipment, and lifecycle controls. */
public final class ControlEntityTool implements Tool {

    private final MinecraftServer mc;

    public ControlEntityTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "control_entity";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        String action = ToolArgs.requireString(args, "action").trim().toLowerCase();
        try {
            return switch (action) {
                case "mount" -> mount(args);
                case "dismount" -> dismount(subject(args));
                case "damage" -> damage(subject(args), args);
                case "heal" -> heal(subject(args), args);
                case "kill" -> lifecycle(subject(args), args, true);
                case "discard" -> lifecycle(subject(args), args, false);
                case "velocity" -> velocity(subject(args), args);
                case "rotation" -> rotation(subject(args), args);
                case "equipment" -> equipment(subject(args), args);
                case "attribute" -> attribute(subject(args), args);
                default -> throw new ToolException("INVALID_ARGS", "Unknown entity action: " + action);
            };
        } catch (AgentApiException ex) {
            throw new ToolException(ex.code(), ex.getMessage());
        }
    }

    private JsonObject mount(JsonObject args) throws AgentApiException {
        Entity rider = resolve(args, "rider_name", "rider_uuid");
        Entity vehicle = resolve(args, "vehicle_name", "vehicle_uuid");
        boolean mounted = AgentLinkApi.entities().mount(rider, vehicle);
        JsonObject result = base("mount", rider);
        result.addProperty("vehicle_uuid", vehicle.getUUID().toString());
        result.addProperty("mounted", mounted);
        return result;
    }

    private JsonObject dismount(Entity entity) throws AgentApiException {
        AgentLinkApi.entities().dismount(entity);
        return base("dismount", entity);
    }

    private JsonObject damage(Entity entity, JsonObject args) throws AgentApiException, ToolException {
        float before = health(entity);
        boolean hurt = AgentLinkApi.entities().damage(entity,
                (float) ToolArgs.requireDouble(args, "amount"), ToolArgs.optString(args, "cause", "generic"));
        JsonObject result = base("damage", entity);
        result.addProperty("hurt", hurt);
        result.addProperty("health_before", before);
        result.addProperty("health_after", health(entity));
        return result;
    }

    private JsonObject heal(Entity entity, JsonObject args) throws AgentApiException, ToolException {
        float before = health(entity);
        AgentLinkApi.entities().heal(entity, (float) ToolArgs.requireDouble(args, "amount"));
        JsonObject result = base("heal", entity);
        result.addProperty("health_before", before);
        result.addProperty("health_after", health(entity));
        return result;
    }

    private JsonObject lifecycle(Entity entity, JsonObject args, boolean kill) throws AgentApiException, ToolException {
        if (!ToolArgs.optBool(args, "confirmed", false)) {
            throw new ToolException("CONFIRMATION_REQUIRED",
                    "Entity lifecycle action requires confirmed=true");
        }
        if (entity instanceof ServerPlayer && !ToolArgs.optBool(args, "allow_player", false)) {
            throw new ToolException("CONFIRMATION_REQUIRED",
                    "Target is a player; pass allow_player=true and confirmed=true explicitly");
        }
        if (kill) AgentLinkApi.entities().kill(entity); else AgentLinkApi.entities().discard(entity);
        return base(kill ? "kill" : "discard", entity);
    }

    private JsonObject velocity(Entity entity, JsonObject args) throws AgentApiException, ToolException {
        AgentLinkApi.entities().setVelocity(entity,
                ToolArgs.requireDouble(args, "x"), ToolArgs.requireDouble(args, "y"),
                ToolArgs.requireDouble(args, "z"));
        JsonObject result = base("velocity", entity);
        result.addProperty("x", entity.getDeltaMovement().x);
        result.addProperty("y", entity.getDeltaMovement().y);
        result.addProperty("z", entity.getDeltaMovement().z);
        return result;
    }

    private JsonObject rotation(Entity entity, JsonObject args) throws AgentApiException, ToolException {
        float yaw = (float) ToolArgs.requireDouble(args, "yaw");
        float pitch = (float) ToolArgs.requireDouble(args, "pitch");
        AgentLinkApi.entities().setRotation(entity, yaw, pitch);
        JsonObject result = base("rotation", entity);
        result.addProperty("yaw", entity.getYRot());
        result.addProperty("pitch", entity.getXRot());
        return result;
    }

    private JsonObject equipment(Entity entity, JsonObject args) throws AgentApiException, ToolException {
        String slot = ToolArgs.requireString(args, "slot");
        String item = ToolArgs.requireString(args, "item");
        int count = ToolArgs.optInt(args, "count", 1);
        AgentLinkApi.entities().setEquipment(entity, slot, item, count);
        JsonObject result = base("equipment", entity);
        result.addProperty("slot", slot);
        result.addProperty("item", item);
        result.addProperty("count", count);
        return result;
    }

    private JsonObject attribute(Entity entity, JsonObject args) throws AgentApiException, ToolException {
        String id = ToolArgs.requireString(args, "attribute");
        double value = ToolArgs.requireDouble(args, "base_value");
        double effective = AgentLinkApi.entities().setAttribute(entity, id, value);
        JsonObject result = base("attribute", entity);
        result.addProperty("attribute", id);
        result.addProperty("base_value", value);
        result.addProperty("effective_value", effective);
        return result;
    }

    private Entity subject(JsonObject args) throws AgentApiException {
        return resolve(args, "name", "uuid");
    }

    private Entity resolve(JsonObject args, String nameKey, String uuidKey) throws AgentApiException {
        String name = ToolArgs.optString(args, nameKey, null);
        String raw = ToolArgs.optString(args, uuidKey, null);
        UUID uuid = null;
        if (raw != null && !raw.isBlank()) {
            try {
                uuid = UUID.fromString(raw.trim());
            } catch (IllegalArgumentException ex) {
                throw new AgentApiException("INVALID_ARGS", "Invalid UUID: " + raw);
            }
        }
        return AgentLinkApi.entities().resolve(mc, name, uuid);
    }

    private static float health(Entity entity) throws AgentApiException {
        if (!(entity instanceof LivingEntity living)) {
            throw new AgentApiException("INVALID_ARGS", "Target is not a living entity");
        }
        return living.getHealth();
    }

    private static JsonObject base(String action, Entity entity) {
        JsonObject result = new JsonObject();
        result.addProperty("action", action);
        result.addProperty("uuid", entity.getUUID().toString());
        result.addProperty("type", entity.getType().builtInRegistryHolder().key().location().toString());
        result.addProperty("dim", entity.level().dimension().location().toString());
        if (entity instanceof ServerPlayer player) result.addProperty("name", player.getGameProfile().getName());
        return result;
    }
}

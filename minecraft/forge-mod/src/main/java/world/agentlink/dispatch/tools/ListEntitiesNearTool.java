package world.agentlink.dispatch.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

import java.util.HashSet;
import java.util.Set;

/**
 * Lists entities inside a sphere around a center point. Radius capped at 64 (entity scans are
 * O(n) over chunk-scoped lists so a giant box can lag); types filter is optional.
 */
public class ListEntitiesNearTool implements Tool {

    private static final double MAX_RADIUS = 64.0;
    private static final int MAX_RESULTS = 256;

    private final MinecraftServer mc;

    public ListEntitiesNearTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "list_entities_near";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        ServerLevel level = GetBlockTool.resolveDimension(mc, args);
        JsonObject center = requireObj(args, "center");
        double cx = GetBlockTool.requireDouble(center, "x");
        double cy = GetBlockTool.requireDouble(center, "y");
        double cz = GetBlockTool.requireDouble(center, "z");
        double radius = args.has("radius") && !args.get("radius").isJsonNull()
                ? args.get("radius").getAsDouble()
                : 16.0;
        if (radius <= 0) throw new ToolException("INVALID_ARGS", "radius must be positive");
        if (radius > MAX_RADIUS) radius = MAX_RADIUS;
        int limit = args.has("limit") && !args.get("limit").isJsonNull()
                ? args.get("limit").getAsInt()
                : MAX_RESULTS;
        if (limit <= 0) limit = MAX_RESULTS;
        if (limit > MAX_RESULTS) limit = MAX_RESULTS;

        Set<String> typeFilter = parseTypeFilter(args);
        Set<String> idFilter = parseIdFilter(args);

        Vec3 origin = new Vec3(cx, cy, cz);
        AABB box = new AABB(cx - radius, cy - radius, cz - radius, cx + radius, cy + radius, cz + radius);
        double r2 = radius * radius;

        JsonArray entities = new JsonArray();
        int total = 0;
        int truncated = 0;
        for (Entity e : level.getEntities((Entity) null, box, ent -> ent.distanceToSqr(origin) <= r2)) {
            String category = category(e);
            if (typeFilter != null && !typeFilter.contains(category)) continue;
            ResourceLocation rl = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
            String idStr = rl == null ? "minecraft:unknown" : rl.toString();
            if (idFilter != null && !idFilter.contains(idStr)) continue;

            total++;
            if (entities.size() >= limit) {
                truncated++;
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("uuid", e.getUUID().toString());
            o.addProperty("type", idStr);
            o.addProperty("category", category);
            o.addProperty("name", e.getName().getString());
            if (e.hasCustomName()) o.addProperty("custom_name", e.getCustomName().getString());
            JsonArray pos = new JsonArray();
            pos.add(e.getX()); pos.add(e.getY()); pos.add(e.getZ());
            o.add("pos", pos);
            o.addProperty("distance", Math.sqrt(e.distanceToSqr(origin)));
            o.addProperty("yaw", e.getYRot());
            o.addProperty("pitch", e.getXRot());
            o.addProperty("on_ground", e.onGround());
            o.addProperty("in_water", e.isInWater());
            o.addProperty("on_fire", e.isOnFire());
            if (e instanceof LivingEntity le) {
                o.addProperty("health", le.getHealth());
                o.addProperty("max_health", le.getMaxHealth());
            }
            if (e instanceof Player pl) {
                o.addProperty("player_uuid", pl.getUUID().toString());
            }
            o.addProperty("passenger_count", e.getPassengers().size());
            Entity vehicle = e.getVehicle();
            if (vehicle != null) o.addProperty("vehicle_uuid", vehicle.getUUID().toString());
            entities.add(o);
        }

        JsonObject r = new JsonObject();
        r.addProperty("dim", level.dimension().location().toString());
        JsonObject c = new JsonObject();
        c.addProperty("x", cx); c.addProperty("y", cy); c.addProperty("z", cz);
        r.add("center", c);
        r.addProperty("radius", radius);
        r.addProperty("count", total);
        r.addProperty("returned", entities.size());
        r.addProperty("truncated", truncated);
        r.add("entities", entities);
        return r;
    }

    private static String category(Entity e) {
        if (e instanceof Player) return "player";
        if (e instanceof Projectile) return "projectile";
        if (e instanceof Mob) return "mob";
        if (e instanceof net.minecraft.world.entity.item.ItemEntity) return "item";
        if (e instanceof net.minecraft.world.entity.ExperienceOrb) return "xp_orb";
        return "other";
    }

    private static Set<String> parseTypeFilter(JsonObject args) {
        if (!args.has("types") || args.get("types").isJsonNull()) return null;
        JsonElement el = args.get("types");
        if (!el.isJsonArray()) return null;
        Set<String> out = new HashSet<>();
        for (JsonElement v : el.getAsJsonArray()) {
            if (v != null && !v.isJsonNull()) out.add(v.getAsString());
        }
        return out.isEmpty() ? null : out;
    }

    private static Set<String> parseIdFilter(JsonObject args) {
        if (!args.has("ids") || args.get("ids").isJsonNull()) return null;
        JsonElement el = args.get("ids");
        if (!el.isJsonArray()) return null;
        Set<String> out = new HashSet<>();
        for (JsonElement v : el.getAsJsonArray()) {
            if (v != null && !v.isJsonNull()) out.add(v.getAsString());
        }
        return out.isEmpty() ? null : out;
    }

    private static JsonObject requireObj(JsonObject args, String key) throws ToolException {
        if (!args.has(key) || args.get(key).isJsonNull() || !args.get(key).isJsonObject()) {
            throw new ToolException("INVALID_ARGS", "Missing object arg: " + key);
        }
        return args.getAsJsonObject(key);
    }
}

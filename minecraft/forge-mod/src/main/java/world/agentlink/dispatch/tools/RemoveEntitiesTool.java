package world.agentlink.dispatch.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolArgs;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Remove entities — one by UUID, or every match inside a radius.
 *
 * <h2>Why this instead of /kill</h2>
 * Cleanup is one of the most common operational asks: a thousand dropped items lagging a farm, mobs
 * piled up in a corner, leftover armour stands from a build. {@code /kill @e[...]} does it, but it
 * needs arbitrary-console permission, is trivially mistyped into {@code /kill @e} (which removes
 * every player's ride, every item, and every villager on the server), and reports only a count.
 *
 * <p>Here the destructive surface is bounded by construction:
 * <ul>
 *   <li>Players are <b>never</b> removed. There is no flag for it — killing a player through an
 *       entity-removal tool is never the right mechanism.</li>
 *   <li>A radius is required for bulk removal, and capped at {@link #MAX_RADIUS}. There is no
 *       "everything, everywhere" mode.</li>
 *   <li>{@code dry_run} (the default for unfiltered calls) reports what <em>would</em> go, so the
 *       agent can confirm the selection before destroying anything.</li>
 * </ul>
 * {@code discard} removes entities without death drops or effects, which is what "clean up the lag"
 * means; {@code kill} routes through normal death handling so drops and XP happen.
 */
public class RemoveEntitiesTool implements Tool {

    private static final double MAX_RADIUS = 128.0;
    private static final int MAX_REMOVALS = 20_000;

    private final MinecraftServer mc;
    private final GetNbtTool resolver;

    public RemoveEntitiesTool(MinecraftServer mc) {
        this.mc = mc;
        this.resolver = new GetNbtTool(mc);
    }

    @Override
    public String name() {
        return "remove_entities";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        String mode = ToolArgs.optEnum(args, "mode", "discard");
        if (!"discard".equals(mode) && !"kill".equals(mode)) {
            throw new ToolException("INVALID_ARGS",
                    "mode must be \"discard\" (no drops, silent) or \"kill\" (normal death, drops XP/loot)");
        }

        // Single-entity form: unambiguous, so no dry-run default.
        String uuidRaw = ToolArgs.optString(args, "uuid", null);
        if (uuidRaw != null && !uuidRaw.isBlank()) {
            Entity entity = resolver.resolveEntity(args);
            if (entity instanceof Player) {
                throw new ToolException("REFUSED",
                        "Refusing to remove a player. Use run_console_command with an explicit /kick"
                                + " or /kill if that is genuinely intended.");
            }
            boolean dryRun = ToolArgs.optBool(args, "dry_run", false);
            JsonObject r = new JsonObject();
            r.addProperty("mode", mode);
            r.addProperty("dry_run", dryRun);
            r.addProperty("uuid", entity.getUUID().toString());
            r.addProperty("type", entity.getType().builtInRegistryHolder().key().location().toString());
            r.addProperty("dim", entity.level().dimension().location().toString());
            if (!dryRun) apply(entity, mode);
            r.addProperty("removed", dryRun ? 0 : 1);
            return r;
        }

        // Bulk form.
        ServerLevel level = Dimensions.resolve(mc, args);
        ToolArgs.DoublePos center = ToolArgs.requireDoublePos(args, "center");
        double radius = ToolArgs.optDoubleClamped(args, "radius", 16.0, 0.5, MAX_RADIUS);
        Set<String> typeFilter = idSet(ToolArgs.optStringList(args, "types"));
        Set<String> categoryFilter = plainSet(ToolArgs.optStringList(args, "categories"));
        boolean hasFilter = typeFilter != null || categoryFilter != null;
        // Unfiltered bulk removal is the dangerous shape, so it defaults to a preview.
        boolean dryRun = ToolArgs.optBool(args, "dry_run", !hasFilter);
        int limit = ToolArgs.optIntClamped(args, "limit", MAX_REMOVALS, 1, MAX_REMOVALS);

        Vec3 origin = new Vec3(center.x(), center.y(), center.z());
        AABB box = new AABB(
                center.x() - radius, center.y() - radius, center.z() - radius,
                center.x() + radius, center.y() + radius, center.z() + radius);
        double r2 = radius * radius;

        List<Entity> selected = new ArrayList<>();
        Map<String, Integer> byType = new LinkedHashMap<>();
        int skippedPlayers = 0;
        int overLimit = 0;

        for (Entity e : level.getEntities((Entity) null, box, ent -> ent.distanceToSqr(origin) <= r2)) {
            if (e instanceof Player) {
                skippedPlayers++;
                continue;
            }
            ResourceLocation rl = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
            String id = rl == null ? "minecraft:unknown" : rl.toString();
            if (typeFilter != null && !typeFilter.contains(id)) continue;
            if (categoryFilter != null && !categoryFilter.contains(category(e))) continue;
            byType.merge(id, 1, Integer::sum);
            if (selected.size() >= limit) {
                overLimit++;
                continue;
            }
            selected.add(e);
        }

        int removed = 0;
        if (!dryRun) {
            for (Entity e : selected) {
                apply(e, mode);
                removed++;
            }
        }

        JsonObject r = new JsonObject();
        r.addProperty("dim", Dimensions.idOf(level));
        r.add("center", center.toJson());
        r.addProperty("radius", radius);
        r.addProperty("mode", mode);
        r.addProperty("dry_run", dryRun);
        r.addProperty("matched", selected.size() + overLimit);
        r.addProperty("removed", removed);
        r.addProperty("players_skipped", skippedPlayers);
        if (overLimit > 0) {
            r.addProperty("over_limit", overLimit);
            r.addProperty("limit", limit);
        }
        JsonObject counts = new JsonObject();
        for (Map.Entry<String, Integer> e : byType.entrySet()) counts.addProperty(e.getKey(), e.getValue());
        r.add("counts_by_type", counts);
        if (dryRun) {
            r.addProperty("note", hasFilter
                    ? "dry_run was requested: nothing was removed. Re-issue with dry_run:false to apply."
                    : "No `types` or `categories` filter was given, so this defaulted to a preview."
                            + " Review counts_by_type, then re-issue with dry_run:false (and ideally a"
                            + " filter) to apply.");
        }
        return r;
    }

    /** {@code discard} skips death handling entirely; {@code kill} goes through it. */
    private static void apply(Entity entity, String mode) {
        if ("kill".equals(mode)) {
            entity.kill();
        } else {
            entity.discard();
        }
    }

    /** Same coarse buckets as {@code list_entities_near}, so filters transfer between the two. */
    private static String category(Entity e) {
        if (e instanceof Player) return "player";
        if (e instanceof net.minecraft.world.entity.projectile.Projectile) return "projectile";
        if (e instanceof net.minecraft.world.entity.item.ItemEntity) return "item";
        if (e instanceof net.minecraft.world.entity.ExperienceOrb) return "xp_orb";
        if (e instanceof net.minecraft.world.entity.Mob) return "mob";
        return "other";
    }

    private static Set<String> idSet(List<String> items) {
        if (items == null || items.isEmpty()) return null;
        Set<String> out = new HashSet<>();
        for (String s : items) {
            if (s == null || s.isBlank()) continue;
            String t = s.trim();
            // Accept bare names for convenience: "zombie" → "minecraft:zombie".
            out.add(t.contains(":") ? t : "minecraft:" + t);
        }
        return out.isEmpty() ? null : out;
    }

    /** Categories are our own bucket names, not registry ids — no namespace to add. */
    private static Set<String> plainSet(List<String> items) {
        if (items == null || items.isEmpty()) return null;
        Set<String> out = new HashSet<>();
        for (String s : items) {
            if (s == null || s.isBlank()) continue;
            out.add(s.trim().toLowerCase(java.util.Locale.ROOT));
        }
        return out.isEmpty() ? null : out;
    }
}

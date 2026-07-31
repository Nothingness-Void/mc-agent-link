package world.agentlink.dispatch.tools;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolArgs;
import world.agentlink.dispatch.ToolException;
import world.agentlink.nbt.NbtJson;
import world.agentlink.transport.ClientSession;

/**
 * Write NBT to a block entity, an entity, or a player inventory slot.
 *
 * <h2>Why an agent needs this</h2>
 * {@code get_nbt} makes everything readable; without a write counterpart the agent can diagnose but
 * not fix. Renaming a spawner's mob, correcting a sign, repairing a corrupted modded block entity,
 * or setting up an item with specific enchantments all reduce to an NBT merge. The alternative is
 * {@code run_console_command "/data merge ..."}, which needs an arbitrary-console approval and
 * returns unstructured chat text.
 *
 * <h2>Merge vs set</h2>
 * <ul>
 *   <li>{@code mode:"merge"} (default) — deep-merges the payload into the existing compound. Keys
 *       not mentioned keep their values. This is what you want almost always.</li>
 *   <li>{@code mode:"set"} — replaces the value at {@code path}. Requires {@code path}, because
 *       wholesale-replacing an entity's root compound reliably corrupts it (the id, UUID and
 *       position all live there).</li>
 * </ul>
 *
 * <p>Prefer {@code snbt} over {@code value} for the payload: JSON cannot express a byte vs an int,
 * and {@code Count:1} where vanilla expects {@code Count:1b} yields an item that silently vanishes.
 * See {@link NbtJson}.
 *
 * <h2>What this will not do</h2>
 * Refuses to touch {@code UUID}, {@code Pos}, {@code id} and a few other structural keys on
 * entities. Rewriting those doesn't edit an entity, it produces a broken one that may crash on the
 * next save — and the correct tools for those effects ({@code teleport}, {@code modify_entity})
 * exist alongside this.
 */
public class SetNbtTool implements Tool {

    /**
     * Keys whose values the engine treats as identity or invariants. Merging over them replaces a
     * live object's notion of what it is, and the failure shows up later as a corrupt save rather
     * than an error here.
     */
    private static final java.util.Set<String> PROTECTED_ENTITY_KEYS = java.util.Set.of(
            "UUID", "id", "Pos", "Dimension", "Passengers", "RootVehicle");
    private static final java.util.Set<String> PROTECTED_BLOCK_ENTITY_KEYS = java.util.Set.of(
            "x", "y", "z", "id");

    private final MinecraftServer mc;
    private final GetNbtTool resolver;

    public SetNbtTool(MinecraftServer mc) {
        this.mc = mc;
        this.resolver = new GetNbtTool(mc);
    }

    @Override
    public String name() {
        return "set_nbt";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        String target = ToolArgs.optEnum(args, "target", "block");
        String mode = ToolArgs.optEnum(args, "mode", "merge");
        if (!"merge".equals(mode) && !"set".equals(mode)) {
            throw new ToolException("INVALID_ARGS", "mode must be \"merge\" or \"set\"");
        }
        String path = ToolArgs.optString(args, "path", null);
        boolean hasPath = path != null && !path.isBlank();
        if ("set".equals(mode) && !hasPath) {
            throw new ToolException("INVALID_ARGS",
                    "mode:\"set\" requires `path` — replacing an entire root compound corrupts the"
                            + " target. Use mode:\"merge\" to change fields, or give a path like"
                            + " \"Items[0].Count\".");
        }

        Tag payload = readPayload(args);

        return switch (target) {
            case "block" -> writeBlock(args, mode, path, hasPath, payload);
            case "entity", "player" -> writeEntity(args, target, mode, path, hasPath, payload);
            case "item" -> writeItem(args, mode, path, hasPath, payload);
            default -> throw new ToolException("INVALID_ARGS",
                    "target must be block, entity, player, or item (got \"" + target + "\")");
        };
    }

    /** SNBT takes precedence; JSON {@code value} is the convenience path. */
    private Tag readPayload(JsonObject args) throws ToolException {
        String snbt = ToolArgs.optString(args, "snbt", null);
        if (snbt != null && !snbt.isBlank()) return NbtJson.parseSnbtValue(snbt);
        if (args.has("value") && !args.get("value").isJsonNull()) {
            return NbtJson.fromJson(args.get("value"));
        }
        throw new ToolException("INVALID_ARGS",
                "Provide `snbt` (preferred, type-exact) or `value` (JSON; ints and doubles only)");
    }

    // ------------------------------------------------------------------ block entity

    private JsonObject writeBlock(JsonObject args, String mode, String path, boolean hasPath, Tag payload)
            throws ToolException {
        ServerLevel level = Dimensions.resolve(mc, args);
        ToolArgs.IntPos p = args.has("pos")
                ? ToolArgs.requireIntPos(args, "pos")
                : ToolArgs.requireFlatIntPos(args);
        BlockPos pos = new BlockPos(p.x(), p.y(), p.z());
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            throw new ToolException("NOT_FOUND",
                    "No block entity at (" + p.x() + "," + p.y() + "," + p.z() + ") in "
                            + Dimensions.idOf(level) + ". Place a block that stores data first"
                            + " (set_block accepts inline NBT, e.g. chest{Items:[...]}).");
        }

        CompoundTag current = be.saveWithFullMetadata();
        CompoundTag updated = apply(current, mode, path, hasPath, payload, PROTECTED_BLOCK_ENTITY_KEYS,
                "block entity");
        // Position keys must always describe where the BE actually is.
        updated.putInt("x", pos.getX());
        updated.putInt("y", pos.getY());
        updated.putInt("z", pos.getZ());

        try {
            be.load(updated);
            be.setChanged();
        } catch (Exception ex) {
            throw new ToolException("NBT_REJECTED",
                    "The block entity refused the payload (" + ex.getClass().getSimpleName()
                            + (ex.getMessage() == null ? "" : ": " + ex.getMessage())
                            + "). Its previous state is unchanged in memory but may be inconsistent —"
                            + " re-read with get_nbt to confirm.");
        }
        // Push the new state to clients: BE data isn't part of the blockstate, so a plain
        // setBlock-style update won't reach anyone watching.
        level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);

        JsonObject r = new JsonObject();
        r.addProperty("target", "block");
        r.addProperty("dim", Dimensions.idOf(level));
        r.add("pos", p.toJson());
        r.addProperty("mode", mode);
        if (hasPath) r.addProperty("path", path);
        r.addProperty("applied", true);
        r.add("nbt_after", NbtJson.toJson(be.saveWithFullMetadata()));
        return r;
    }

    // ------------------------------------------------------------------ entity / player

    private JsonObject writeEntity(JsonObject args, String target, String mode, String path,
                                   boolean hasPath, Tag payload) throws ToolException {
        Entity entity = "player".equals(target)
                ? resolver.requirePlayer(ToolArgs.requireString(args, "name"))
                : resolver.resolveEntity(args);

        CompoundTag current = new CompoundTag();
        entity.saveWithoutId(current);
        CompoundTag updated = apply(current, mode, path, hasPath, payload, PROTECTED_ENTITY_KEYS, "entity");

        try {
            entity.load(updated);
        } catch (Exception ex) {
            throw new ToolException("NBT_REJECTED",
                    "The entity refused the payload (" + ex.getClass().getSimpleName()
                            + (ex.getMessage() == null ? "" : ": " + ex.getMessage()) + ")");
        }

        JsonObject r = new JsonObject();
        r.addProperty("target", target);
        r.addProperty("uuid", entity.getUUID().toString());
        r.addProperty("type", entity.getType().builtInRegistryHolder().key().location().toString());
        if (entity instanceof ServerPlayer sp) r.addProperty("name", sp.getGameProfile().getName());
        r.addProperty("mode", mode);
        if (hasPath) r.addProperty("path", path);
        r.addProperty("applied", true);
        return r;
    }

    // ------------------------------------------------------------------ item stack

    private JsonObject writeItem(JsonObject args, String mode, String path, boolean hasPath, Tag payload)
            throws ToolException {
        ServerPlayer player = resolver.requirePlayer(ToolArgs.requireString(args, "name"));
        int slot = ToolArgs.requireInt(args, "slot");
        if (slot < 0 || slot >= player.getInventory().getContainerSize()) {
            throw new ToolException("INVALID_ARGS",
                    "slot must be 0.." + (player.getInventory().getContainerSize() - 1));
        }
        ItemStack stack = player.getInventory().getItem(slot);
        if (stack.isEmpty()) throw new ToolException("NOT_FOUND", "Slot " + slot + " is empty");

        CompoundTag current = stack.save(new CompoundTag());
        // "id" and "Count" are the stack's identity here, but unlike an entity they're legitimately
        // editable (that's how you change a stack's size), so only the structural guard applies.
        CompoundTag updated = apply(current, mode, path, hasPath, payload, java.util.Set.of(), "item");
        ItemStack replacement = ItemStack.of(updated);
        if (replacement.isEmpty()) {
            throw new ToolException("NBT_REJECTED",
                    "The resulting stack is empty — check that `Count` is a byte (e.g. 1b) and `id`"
                            + " is a valid item. JSON `value` writes ints, which vanilla drops here;"
                            + " use `snbt`.");
        }
        player.getInventory().setItem(slot, replacement);
        player.inventoryMenu.broadcastChanges();

        JsonObject r = new JsonObject();
        r.addProperty("target", "item");
        r.addProperty("name", player.getGameProfile().getName());
        r.addProperty("slot", slot);
        r.addProperty("mode", mode);
        if (hasPath) r.addProperty("path", path);
        r.addProperty("item", replacement.getItem().builtInRegistryHolder().key().location().toString());
        r.addProperty("count", replacement.getCount());
        r.addProperty("applied", true);
        return r;
    }

    // ------------------------------------------------------------------ merge machinery

    /**
     * Produce the updated compound. {@code merge} without a path deep-merges at the root;
     * {@code merge}/{@code set} with a path operate on the addressed subtree.
     */
    private CompoundTag apply(CompoundTag current, String mode, String path, boolean hasPath,
                              Tag payload, java.util.Set<String> protectedKeys, String what)
            throws ToolException {
        CompoundTag working = current.copy();

        if (!hasPath) {
            if (!(payload instanceof CompoundTag compound)) {
                throw new ToolException("INVALID_ARGS",
                        "A root merge needs a compound payload like {CustomName:'\"Bob\"'}");
            }
            rejectProtected(compound, protectedKeys, what);
            deepMerge(working, compound);
            return working;
        }

        var compiled = NbtJson.parsePath(path);
        try {
            if ("set".equals(mode)) {
                int changed = compiled.set(working, payload);
                if (changed == 0) {
                    throw new ToolException("NOT_FOUND",
                            "path \"" + path + "\" matched nothing to set. Read the structure with"
                                    + " get_nbt first; `set` does not create intermediate nodes for"
                                    + " every path shape.");
                }
            } else {
                // merge at a path: the addressed node must be a compound to merge into.
                var matches = compiled.get(working);
                if (matches.isEmpty()) {
                    throw new ToolException("NOT_FOUND", "path \"" + path + "\" matched nothing to merge into");
                }
                if (!(payload instanceof CompoundTag compound)) {
                    throw new ToolException("INVALID_ARGS",
                            "mode:\"merge\" at a path needs a compound payload; use mode:\"set\""
                                    + " to write a scalar or list");
                }
                for (Tag match : matches) {
                    if (!(match instanceof CompoundTag target)) {
                        throw new ToolException("INVALID_ARGS",
                                "path \"" + path + "\" addresses a " + match.getClass().getSimpleName()
                                        + ", not a compound — use mode:\"set\" for it");
                    }
                    deepMerge(target, compound);
                }
            }
        } catch (ToolException te) {
            throw te;
        } catch (Exception ex) {
            throw new ToolException("INVALID_ARGS",
                    "Could not apply path \"" + path + "\": " + ex.getMessage());
        }
        return working;
    }

    /**
     * Recursive merge: nested compounds merge key-by-key, everything else (including lists) is
     * replaced outright. Element-wise list merging has no well-defined semantics — index 0 of an
     * inventory is not "the same object" as index 0 of the payload — so replacement is the honest
     * behaviour, and a targeted path is how you edit one element.
     */
    private static void deepMerge(CompoundTag into, CompoundTag from) {
        for (String key : from.getAllKeys()) {
            Tag incoming = from.get(key);
            Tag existing = into.get(key);
            if (incoming instanceof CompoundTag incomingCompound && existing instanceof CompoundTag existingCompound) {
                deepMerge(existingCompound, incomingCompound);
            } else if (incoming != null) {
                into.put(key, incoming.copy());
            }
        }
    }

    private static void rejectProtected(CompoundTag payload, java.util.Set<String> protectedKeys, String what)
            throws ToolException {
        for (String key : protectedKeys) {
            if (payload.contains(key)) {
                throw new ToolException("PROTECTED_KEY",
                        "Refusing to overwrite " + what + " key \"" + key + "\" — it is structural"
                                + " state the engine owns. Use teleport for position, modify_entity"
                                + " for common attributes, or address a nested path instead.");
            }
        }
    }
}

package world.agentlink.dispatch.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import world.agentlink.api.AgentApiException;
import world.agentlink.api.AgentLinkApi;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolArgs;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

import java.util.List;
import java.util.UUID;

/**
 * Read raw NBT from a block entity, an entity, or a player — the general-purpose escape hatch for
 * everything the structured tools don't model.
 *
 * <p>The existing read tools each expose a hand-picked projection: {@code get_container} returns
 * item ids and counts, {@code get_player_info} returns vitals and position. That covers common
 * questions and nothing else. Enchantments, custom mod data, villager trades, spawner contents,
 * banner patterns, and every modded block entity's internals were simply unreachable. NBT is the
 * one representation that covers all of it, so exposing it turns "the tool doesn't support that"
 * into "read the field you need".
 *
 * <p>Output carries both a JSON projection and canonical SNBT — see {@link world.agentlink.api.AgentNbtApi}
 * for why both.
 * An optional {@code path} narrows the read using vanilla's own NBT-path grammar, which matters for
 * token cost: a shulker box's full NBT is large, {@code Items[0].tag.display.Name} is not.
 */
public class GetNbtTool implements Tool {

    /** Guard against a single read blowing up the response. Modded BEs can be enormous. */
    private static final int MAX_SNBT_CHARS = 200_000;

    private final MinecraftServer mc;

    public GetNbtTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "get_nbt";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        String target = ToolArgs.optEnum(args, "target", "block");
        JsonObject r = new JsonObject();
        Tag root;

        switch (target) {
            case "block" -> {
                ServerLevel level = Dimensions.resolve(mc, args);
                ToolArgs.IntPos p = args.has("pos")
                        ? ToolArgs.requireIntPos(args, "pos")
                        : ToolArgs.requireFlatIntPos(args);
                BlockPos pos = new BlockPos(p.x(), p.y(), p.z());
                BlockEntity be = level.getBlockEntity(pos);
                if (be == null) {
                    throw new ToolException("NOT_FOUND",
                            "No block entity at (" + p.x() + "," + p.y() + "," + p.z() + ") in "
                                    + Dimensions.idOf(level) + " — block is "
                                    + world.agentlink.world.BlockWriter.idOf(level.getBlockState(pos))
                                    + ". Only blocks with stored data (chests, signs, spawners, ...) have NBT.");
                }
                root = be.saveWithFullMetadata();
                r.addProperty("target", "block");
                r.addProperty("dim", Dimensions.idOf(level));
                r.add("pos", p.toJson());
                var beType = net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
                r.addProperty("block_entity_type", beType == null ? "unknown" : beType.toString());
            }
            case "entity" -> {
                Entity entity = resolveEntity(args);
                CompoundTag tag = new CompoundTag();
                entity.saveWithoutId(tag);
                root = tag;
                r.addProperty("target", "entity");
                r.addProperty("uuid", entity.getUUID().toString());
                r.addProperty("type", entity.getType().builtInRegistryHolder().key().location().toString());
                r.addProperty("dim", entity.level().dimension().location().toString());
            }
            case "player" -> {
                ServerPlayer player = requirePlayer(ToolArgs.requireString(args, "name"));
                CompoundTag tag = new CompoundTag();
                player.saveWithoutId(tag);
                root = tag;
                r.addProperty("target", "player");
                r.addProperty("name", player.getGameProfile().getName());
                r.addProperty("uuid", player.getUUID().toString());
                r.addProperty("dim", player.level().dimension().location().toString());
            }
            case "item" -> {
                // The held/inventory item of a player, by slot. Reaching item NBT through the
                // entity blob works but forces the agent to page a whole player record for one stack.
                ServerPlayer player = requirePlayer(ToolArgs.requireString(args, "name"));
                int slot = ToolArgs.requireInt(args, "slot");
                if (slot < 0 || slot >= player.getInventory().getContainerSize()) {
                    throw new ToolException("INVALID_ARGS",
                            "slot must be 0.." + (player.getInventory().getContainerSize() - 1)
                                    + " (0-8 hotbar, 9-35 main, 36-39 armor, 40 offhand)");
                }
                var stack = player.getInventory().getItem(slot);
                if (stack.isEmpty()) {
                    throw new ToolException("NOT_FOUND", "Slot " + slot + " is empty");
                }
                root = stack.save(new CompoundTag());
                r.addProperty("target", "item");
                r.addProperty("name", player.getGameProfile().getName());
                r.addProperty("slot", slot);
                r.addProperty("item", stack.getItem().builtInRegistryHolder().key().location().toString());
                r.addProperty("count", stack.getCount());
            }
            default -> throw new ToolException("INVALID_ARGS",
                    "target must be block, entity, player, or item (got \"" + target + "\")");
        }

        String path = ToolArgs.optString(args, "path", null);
        if (path != null && !path.isBlank()) {
            List<Tag> matches = resolvePath(root, path);
            r.addProperty("path", path);
            r.addProperty("match_count", matches.size());
            if (matches.isEmpty()) {
                r.addProperty("found", false);
                r.addProperty("note", "path matched nothing; omit `path` to see the full structure");
                return r;
            }
            r.addProperty("found", true);
            if (matches.size() == 1) {
                addEnvelope(r, matches.get(0));
            } else {
                JsonArray arr = new JsonArray();
                for (Tag t : matches) arr.add(AgentLinkApi.nbt().envelope(t));
                r.add("matches", arr);
            }
            return r;
        }

        r.addProperty("found", true);
        addEnvelope(r, root);
        return r;
    }

    /** Attach {@code snbt} + {@code nbt}, truncating the SNBT rather than returning a huge blob. */
    private void addEnvelope(JsonObject r, Tag tag) {
        JsonObject env = AgentLinkApi.nbt().envelope(tag);
        String snbt = env.get("snbt").getAsString();
        if (snbt.length() > MAX_SNBT_CHARS) {
            r.addProperty("snbt", snbt.substring(0, MAX_SNBT_CHARS));
            r.addProperty("snbt_truncated_at", MAX_SNBT_CHARS);
            r.addProperty("snbt_length", snbt.length());
            r.addProperty("note", "output was truncated; use `path` to read a narrower subtree");
        } else {
            r.addProperty("snbt", snbt);
            r.addProperty("snbt_length", snbt.length());
        }
        r.add("nbt", env.get("nbt"));
    }

    /**
     * Resolve an entity by UUID, or by name for players. Shared with {@code set_nbt} and
     * {@code modify_entity} so all three accept the same reference forms.
     */
    Entity resolveEntity(JsonObject args) throws ToolException {
        String uuidRaw = ToolArgs.optString(args, "uuid", null);
        if (uuidRaw != null && !uuidRaw.isBlank()) {
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidRaw.trim());
            } catch (IllegalArgumentException ex) {
                throw new ToolException("INVALID_ARGS", "Not a valid UUID: " + uuidRaw);
            }
            for (ServerLevel level : mc.getAllLevels()) {
                Entity e = level.getEntity(uuid);
                if (e != null) return e;
            }
            throw new ToolException("NOT_FOUND",
                    "No loaded entity with UUID " + uuid + ". Entities only appear in the live index"
                            + " while their chunk is loaded — if you just spawned this one somewhere"
                            + " nobody is standing, call force_load_chunks over that area first (and"
                            + " release it when done). Otherwise use list_entities_near to see what is"
                            + " currently loaded.");
        }
        String name = ToolArgs.optString(args, "name", null);
        if (name != null && !name.isBlank()) return requirePlayer(name);
        throw new ToolException("INVALID_ARGS", "Provide either `uuid` or `name`");
    }

    ServerPlayer requirePlayer(String name) throws ToolException {
        ServerPlayer p = mc.getPlayerList().getPlayerByName(name);
        if (p == null) throw new ToolException("NOT_FOUND", "Player is not online: " + name);
        return p;
    }

    private static List<Tag> resolvePath(Tag root, String path) throws ToolException {
        try {
            return AgentLinkApi.nbt().resolvePath(root, path);
        } catch (AgentApiException ex) {
            throw new ToolException(ex.code(), ex.getMessage());
        }
    }

}

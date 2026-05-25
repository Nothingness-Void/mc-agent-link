package world.agentlink.dispatch.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

import java.util.Locale;

/**
 * Sniff a container block (chest, hopper, dispenser, shulker, barrel, ...) without
 * triggering the open animation. Admin-only by default — see approval.admin_only_tools.
 *
 * <p>Writes a one-line audit entry to the server log so admins can later reconstruct
 * which agent peeked at which container when:
 *   {@code [agent-link] container_read x=.. y=.. z=.. dim=.. block=.. slots=..}
 */
public class GetContainerTool implements Tool {

    private static final Logger AUDIT = LogUtils.getLogger();

    private final MinecraftServer mc;

    public GetContainerTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "get_container";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        ServerLevel level = GetBlockTool.resolveDimension(mc, args);
        int x = GetBlockTool.requireInt(args, "x");
        int y = GetBlockTool.requireInt(args, "y");
        int z = GetBlockTool.requireInt(args, "z");
        BlockPos pos = new BlockPos(x, y, z);

        BlockEntity be = level.getBlockEntity(pos);
        BlockState state = level.getBlockState(pos);
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (be == null) {
            throw new ToolException("INVALID_ARGS",
                    "No block entity at (" + x + "," + y + "," + z + "); block=" + blockId);
        }
        if (!(be instanceof Container container)) {
            throw new ToolException("INVALID_ARGS",
                    "Block entity is not a container: " + (be.getType() == null ? "?" : be.getType()));
        }

        JsonArray slots = new JsonArray();
        int filled = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty()) continue;
            filled++;
            JsonObject o = new JsonObject();
            o.addProperty("slot", slot);
            ResourceLocation rl = BuiltInRegistries.ITEM.getKey(stack.getItem());
            o.addProperty("id", rl == null ? "minecraft:unknown" : rl.toString());
            o.addProperty("count", stack.getCount());
            if (stack.isDamageableItem()) {
                o.addProperty("damage", stack.getDamageValue());
                o.addProperty("max_damage", stack.getMaxDamage());
            }
            if (stack.hasCustomHoverName()) {
                o.addProperty("custom_name", stack.getHoverName().getString());
            }
            if (stack.getTag() != null) o.addProperty("has_nbt", true);
            slots.add(o);
        }

        JsonObject r = new JsonObject();
        r.addProperty("dim", level.dimension().location().toString());
        JsonObject p = new JsonObject();
        p.addProperty("x", x); p.addProperty("y", y); p.addProperty("z", z);
        r.add("pos", p);
        r.addProperty("block", blockId == null ? "minecraft:unknown" : blockId.toString());
        r.addProperty("size", container.getContainerSize());
        r.addProperty("filled", filled);
        r.addProperty("free", container.getContainerSize() - filled);
        if (container instanceof WorldlyContainer wc) {
            JsonArray openFaces = new JsonArray();
            for (var dir : net.minecraft.core.Direction.values()) {
                int[] avail = wc.getSlotsForFace(dir);
                if (avail.length > 0) openFaces.add(dir.getName());
            }
            r.add("worldly_faces", openFaces);
        }
        r.add("items", slots);

        AUDIT.info("[agent-link] container_read x={} y={} z={} dim={} block={} filled={}/{}",
                x, y, z, level.dimension().location(), blockId, filled, container.getContainerSize());
        return r;
    }
}

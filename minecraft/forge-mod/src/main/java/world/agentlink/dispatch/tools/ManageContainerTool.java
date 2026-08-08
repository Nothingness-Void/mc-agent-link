package world.agentlink.dispatch.tools;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import world.agentlink.api.AgentApiException;
import world.agentlink.api.AgentLinkApi;
import world.agentlink.api.AgentWorldApi;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolArgs;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

/** Typed writes to a block container, complementing the admin-only get_container snapshot. */
public final class ManageContainerTool implements Tool {

    private final MinecraftServer mc;

    public ManageContainerTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "manage_container";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        String action = ToolArgs.requireString(args, "action").trim().toLowerCase();
        try {
            ServerLevel level = AgentLinkApi.worlds().level(mc,
                    ToolArgs.optString(args, "dim", AgentWorldApi.DEFAULT_DIMENSION));
            ToolArgs.IntPos raw = args.has("pos")
                    ? ToolArgs.requireIntPos(args, "pos") : ToolArgs.requireFlatIntPos(args);
            BlockPos pos = new BlockPos(raw.x(), raw.y(), raw.z());
            Container container = AgentLinkApi.containers().at(level, pos);
            return switch (action) {
                case "set_slot" -> setSlot(level, pos, container, args);
                case "clear_slot" -> clearSlot(level, pos, container, ToolArgs.requireInt(args, "slot"));
                case "clear_container" -> clearContainer(level, pos, container);
                case "swap_slots" -> swapSlots(level, pos, container, args);
                default -> throw new ToolException("INVALID_ARGS",
                        "action must be set_slot, clear_slot, clear_container, or swap_slots");
            };
        } catch (AgentApiException ex) {
            throw new ToolException(ex.code(), ex.getMessage());
        }
    }

    private JsonObject setSlot(ServerLevel level, BlockPos pos, Container container, JsonObject args)
            throws AgentApiException, ToolException {
        int slot = ToolArgs.requireInt(args, "slot");
        checkSlot(container, slot);
        ItemStack before = container.getItem(slot).copy();
        ItemStack after = AgentLinkApi.items().parse(ToolArgs.requireString(args, "item"),
                ToolArgs.optInt(args, "count", 1));
        AgentLinkApi.containers().setSlot(level, pos, slot, after);
        JsonObject result = base("set_slot", level, pos);
        result.addProperty("slot", slot);
        result.add("before", summary(before));
        result.add("after", summary(after));
        return result;
    }

    private JsonObject clearSlot(ServerLevel level, BlockPos pos, Container container, int slot)
            throws AgentApiException {
        checkSlot(container, slot);
        ItemStack before = container.getItem(slot).copy();
        AgentLinkApi.containers().clearSlot(level, pos, slot);
        JsonObject result = base("clear_slot", level, pos);
        result.addProperty("slot", slot);
        result.add("before", summary(before));
        return result;
    }

    private JsonObject clearContainer(ServerLevel level, BlockPos pos, Container container)
            throws AgentApiException {
        int filled = 0;
        for (int i = 0; i < container.getContainerSize(); i++) if (!container.getItem(i).isEmpty()) filled++;
        AgentLinkApi.containers().clear(level, pos);
        JsonObject result = base("clear_container", level, pos);
        result.addProperty("cleared_slots", filled);
        return result;
    }

    private JsonObject swapSlots(ServerLevel level, BlockPos pos, Container container, JsonObject args)
            throws AgentApiException, ToolException {
        int first = ToolArgs.requireInt(args, "first_slot");
        int second = ToolArgs.requireInt(args, "second_slot");
        checkSlot(container, first); checkSlot(container, second);
        AgentLinkApi.containers().swapSlots(level, pos, first, second);
        JsonObject result = base("swap_slots", level, pos);
        result.addProperty("first_slot", first);
        result.addProperty("second_slot", second);
        return result;
    }

    private static void checkSlot(Container container, int slot) throws AgentApiException {
        if (slot < 0 || slot >= container.getContainerSize()) {
            throw new AgentApiException("INVALID_ARGS", "slot must be 0.." + (container.getContainerSize() - 1));
        }
    }

    private static JsonObject base(String action, ServerLevel level, BlockPos pos) {
        JsonObject result = new JsonObject();
        result.addProperty("action", action);
        result.addProperty("dim", level.dimension().location().toString());
        JsonObject p = new JsonObject();
        p.addProperty("x", pos.getX()); p.addProperty("y", pos.getY()); p.addProperty("z", pos.getZ());
        result.add("pos", p);
        return result;
    }

    private static JsonObject summary(ItemStack stack) {
        JsonObject result = new JsonObject();
        if (stack == null || stack.isEmpty()) {
            result.addProperty("empty", true);
            return result;
        }
        result.addProperty("empty", false);
        result.addProperty("item", AgentLinkApi.items().id(stack));
        result.addProperty("count", stack.getCount());
        result.addProperty("has_nbt", stack.getTag() != null);
        return result;
    }
}

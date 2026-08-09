package world.agentlink.dispatch.tools;

import com.google.gson.JsonObject;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import world.agentlink.api.AgentApiException;
import world.agentlink.api.AgentLinkApi;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolArgs;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

/** Slot-level player inventory writes, kept separate from the higher-level give_item tool. */
public final class ManageInventoryTool implements Tool {

    private final MinecraftServer mc;

    public ManageInventoryTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "manage_player_inventory";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        String action = ToolArgs.requireString(args, "action").trim().toLowerCase();
        try {
            ServerPlayer player = AgentLinkApi.players().online(mc, ToolArgs.requireString(args, "name"));
            return switch (action) {
                case "clear_inventory" -> clearInventory(player);
                case "clear_slot" -> clearSlot(player, ToolArgs.requireInt(args, "slot"));
                case "set_slot" -> setSlot(player, args);
                case "swap_slots" -> swapSlots(player, args);
                case "set_selected_slot" -> setSelectedSlot(player, args);
                case "drop_slot" -> dropSlot(player, args);
                default -> throw new ToolException("INVALID_ARGS", "Unknown inventory action: " + action);
            };
        } catch (AgentApiException ex) {
            throw new ToolException(ex.code(), ex.getMessage());
        }
    }

    private JsonObject clearInventory(ServerPlayer player) throws AgentApiException {
        int nonEmpty = AgentLinkApi.inventory().clear(player);
        JsonObject result = base("clear_inventory", player);
        result.addProperty("cleared_slots", nonEmpty);
        return result;
    }

    private JsonObject clearSlot(ServerPlayer player, int slot) throws AgentApiException {
        ItemStack before = AgentLinkApi.inventory().clearSlot(player, slot);
        JsonObject result = base("clear_slot", player);
        result.addProperty("slot", slot);
        result.add("before", summary(before));
        return result;
    }

    private JsonObject setSlot(ServerPlayer player, JsonObject args) throws AgentApiException, ToolException {
        int slot = ToolArgs.requireInt(args, "slot");
        String spec = ToolArgs.requireString(args, "item");
        int count = ToolArgs.optInt(args, "count", 1);
        ItemStack after = AgentLinkApi.items().parse(spec, count);
        ItemStack before = AgentLinkApi.inventory().setSlot(player, slot, after);
        JsonObject result = base("set_slot", player);
        result.addProperty("slot", slot);
        result.add("before", summary(before));
        result.add("after", summary(after));
        return result;
    }

    private JsonObject swapSlots(ServerPlayer player, JsonObject args)
            throws AgentApiException, ToolException {
        int first = ToolArgs.requireInt(args, "first_slot");
        int second = ToolArgs.requireInt(args, "second_slot");
        ItemStack a = AgentLinkApi.inventory().getSlot(player, first);
        ItemStack b = AgentLinkApi.inventory().getSlot(player, second);
        AgentLinkApi.inventory().swapSlots(player, first, second);
        JsonObject result = base("swap_slots", player);
        result.addProperty("first_slot", first);
        result.addProperty("second_slot", second);
        result.add("first", summary(b));
        result.add("second", summary(a));
        return result;
    }

    private JsonObject setSelectedSlot(ServerPlayer player, JsonObject args) throws ToolException {
        int slot = ToolArgs.requireInt(args, "slot");
        int before;
        try {
            before = AgentLinkApi.inventory().setSelectedSlot(player, slot);
        } catch (AgentApiException ex) {
            throw new ToolException(ex.code(), ex.getMessage());
        }
        JsonObject result = base("set_selected_slot", player);
        result.addProperty("before", before);
        result.addProperty("selected_slot", slot);
        return result;
    }

    private JsonObject dropSlot(ServerPlayer player, JsonObject args) throws ToolException {
        int slot = ToolArgs.requireInt(args, "slot");
        ItemStack before;
        try {
            before = AgentLinkApi.inventory().getSlot(player, slot);
        } catch (AgentApiException ex) {
            throw new ToolException(ex.code(), ex.getMessage());
        }
        if (before.isEmpty()) {
            JsonObject result = base("drop_slot", player);
            result.addProperty("slot", slot);
            result.addProperty("dropped", 0);
            return result;
        }
        int count = ToolArgs.optInt(args, "count", before.getCount());
        if (count < 1 || count > before.getCount()) {
            throw new ToolException("INVALID_ARGS", "count must be between 1 and " + before.getCount());
        }
        ItemStack dropped;
        try {
            dropped = AgentLinkApi.inventory().dropSlot(player, slot, count);
        } catch (AgentApiException ex) {
            throw new ToolException(ex.code(), ex.getMessage());
        }
        JsonObject result = base("drop_slot", player);
        result.addProperty("slot", slot);
        result.addProperty("dropped", dropped.getCount());
        result.add("item", summary(dropped));
        return result;
    }

    private static JsonObject base(String action, ServerPlayer player) {
        JsonObject result = new JsonObject();
        result.addProperty("action", action);
        result.addProperty("name", player.getGameProfile().getName());
        result.addProperty("uuid", player.getUUID().toString());
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
        if (stack.isDamageableItem()) {
            result.addProperty("damage", stack.getDamageValue());
            result.addProperty("max_damage", stack.getMaxDamage());
        }
        result.addProperty("has_nbt", stack.has(DataComponents.CUSTOM_DATA));
        return result;
    }
}

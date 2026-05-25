package world.agentlink.dispatch.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import world.agentlink.dispatch.RequestDispatcher;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

/**
 * Snapshot of one online player's inventory: hotbar + main + armor + offhand. Mirrors
 * Minecraft's slot layout: 0-8 hotbar, 9-35 main, 36-39 armor (boots/legs/chest/helmet), 40 offhand.
 */
public class GetPlayerInventoryTool implements Tool {

    private final MinecraftServer mc;

    public GetPlayerInventoryTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "get_player_inventory";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        String name = RequestDispatcher.requireString(args, "name");
        ServerPlayer p = mc.getPlayerList().getPlayerByName(name);
        if (p == null) {
            throw new ToolException("INVALID_ARGS", "Player not online: " + name);
        }
        Inventory inv = p.getInventory();

        JsonObject r = new JsonObject();
        r.addProperty("name", p.getGameProfile().getName());
        r.addProperty("uuid", p.getUUID().toString());
        r.addProperty("selected_slot", inv.selected);

        JsonArray main = new JsonArray();
        addAll(main, inv.items, 0);
        r.add("main", main);

        JsonArray armor = new JsonArray();
        addAll(armor, inv.armor, 36);
        r.add("armor", armor);

        JsonArray offhand = new JsonArray();
        addAll(offhand, inv.offhand, 40);
        r.add("offhand", offhand);

        r.addProperty("free_slots", countEmpty(inv));
        r.addProperty("total_slots", inv.items.size() + inv.armor.size() + inv.offhand.size());
        return r;
    }

    private static void addAll(JsonArray out, NonNullList<ItemStack> stacks, int slotBase) {
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack stack = stacks.get(i);
            if (stack.isEmpty()) continue;
            JsonObject o = new JsonObject();
            o.addProperty("slot", slotBase + i);
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
            if (stack.getTag() != null) {
                // Don't ship raw NBT; agent rarely needs it and it can be huge / sensitive.
                o.addProperty("has_nbt", true);
            }
            out.add(o);
        }
    }

    private static int countEmpty(Inventory inv) {
        int empty = 0;
        for (ItemStack s : inv.items) if (s.isEmpty()) empty++;
        for (ItemStack s : inv.armor) if (s.isEmpty()) empty++;
        for (ItemStack s : inv.offhand) if (s.isEmpty()) empty++;
        return empty;
    }
}

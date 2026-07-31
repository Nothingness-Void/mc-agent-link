package world.agentlink.dispatch.tools;

import com.google.gson.JsonObject;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.item.ItemParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolArgs;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

/**
 * Give items to a player.
 *
 * <p>Accepts the full vanilla item grammar, so enchantments, custom names and lore all work:
 * {@code diamond_pickaxe{Enchantments:[{id:"minecraft:efficiency",lvl:5s}]}}. Structured instead of
 * {@code run_console_command "/give ..."} for the usual reasons — a narrower permission, typed args,
 * and a result that says how much actually fit rather than chat text to parse.
 *
 * <p>Overflow is reported rather than silently dropped: {@code /give} scatters the remainder on the
 * ground, which is rarely what the agent intended. {@code drop_overflow} makes that choice explicit,
 * and either way the response says how many items landed where.
 */
public class GiveItemTool implements Tool {

    private static final int MAX_TOTAL = 6912; // a full inventory of 64-stacks

    private final MinecraftServer mc;
    private final GetNbtTool resolver;

    public GiveItemTool(MinecraftServer mc) {
        this.mc = mc;
        this.resolver = new GetNbtTool(mc);
    }

    @Override
    public String name() {
        return "give_item";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        ServerPlayer player = resolver.requirePlayer(ToolArgs.requireString(args, "name"));
        String spec = ToolArgs.requireString(args, "item");
        int count = ToolArgs.optIntClamped(args, "count", 1, 1, MAX_TOTAL);
        boolean dropOverflow = ToolArgs.optBool(args, "drop_overflow", true);

        ItemParser.ItemResult parsed;
        try {
            parsed = ItemParser.parseForItem(BuiltInRegistries.ITEM.asLookup(),
                    new StringReader(spec.trim()));
        } catch (CommandSyntaxException e) {
            throw new ToolException("INVALID_ARGS",
                    "Invalid item \"" + spec + "\": " + e.getMessage()
                            + " (accepts id plus optional NBT, e.g. diamond_sword{Unbreakable:1b})");
        }
        Item item = parsed.item().value();

        int given = 0;
        int dropped = 0;
        int remaining = count;
        // Hand out in stack-sized chunks so a request for 200 cobblestone becomes three stacks and
        // a partial, exactly as a player would expect.
        while (remaining > 0) {
            ItemStack stack = new ItemStack(item, 1);
            if (parsed.nbt() != null) stack.setTag(parsed.nbt().copy());
            int chunk = Math.min(remaining, stack.getMaxStackSize());
            stack.setCount(chunk);
            remaining -= chunk;

            if (player.getInventory().add(stack)) {
                given += chunk;
                continue;
            }
            // add() may have taken part of the stack; whatever's left is overflow.
            int leftover = stack.getCount();
            given += chunk - leftover;
            if (leftover <= 0) continue;
            if (dropOverflow) {
                var entity = player.drop(stack, false);
                if (entity != null) {
                    entity.setNoPickUpDelay();
                    entity.setTarget(player.getUUID());
                }
                dropped += leftover;
            } else {
                remaining += leftover;
                break;
            }
        }
        player.inventoryMenu.broadcastChanges();

        JsonObject r = new JsonObject();
        r.addProperty("name", player.getGameProfile().getName());
        r.addProperty("item", String.valueOf(BuiltInRegistries.ITEM.getKey(item)));
        r.addProperty("requested", count);
        r.addProperty("added_to_inventory", given);
        r.addProperty("dropped_on_ground", dropped);
        r.addProperty("not_delivered", remaining);
        r.addProperty("free_slots", freeSlots(player));
        if (remaining > 0) {
            r.addProperty("note", "Inventory is full and drop_overflow was false, so "
                    + remaining + " item(s) were not delivered.");
        }
        return r;
    }

    private static int freeSlots(ServerPlayer player) {
        int free = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            if (inv.items.get(i).isEmpty()) free++;
        }
        return free;
    }
}

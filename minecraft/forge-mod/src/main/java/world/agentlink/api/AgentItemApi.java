package world.agentlink.api;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.item.ItemParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/** Type-exact parser for the vanilla item specification grammar. */
public final class AgentItemApi {

    private static final int MAX_STACK_COUNT = 64;

    AgentItemApi() {}

    /** Parse an item id with optional SNBT, returning one item. */
    public ItemStack parse(String spec) throws AgentApiException {
        if (spec == null || spec.isBlank()) {
            throw new AgentApiException("INVALID_ARGS", "item specification is required");
        }
        try {
            ItemParser.ItemResult parsed = ItemParser.parseForItem(
                    BuiltInRegistries.ITEM.asLookup(), new StringReader(spec.trim()));
            ItemStack stack = new ItemStack(parsed.item().value(), 1);
            if (parsed.nbt() != null) stack.setTag(parsed.nbt().copy());
            return stack;
        } catch (CommandSyntaxException ex) {
            throw new AgentApiException("INVALID_ARGS",
                    "Invalid item specification: " + ex.getMessage(), ex);
        }
    }

    /** Parse an item and apply a count that fits in one inventory slot. */
    public ItemStack parse(String spec, int count) throws AgentApiException {
        ItemStack stack = parse(spec);
        if (count < 1 || count > stack.getMaxStackSize() || count > MAX_STACK_COUNT) {
            throw new AgentApiException("INVALID_ARGS",
                    "count must be between 1 and " + stack.getMaxStackSize()
                            + " for " + BuiltInRegistries.ITEM.getKey(stack.getItem()));
        }
        stack.setCount(count);
        return stack;
    }

    public String id(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        return String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }
}

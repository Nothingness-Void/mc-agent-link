package world.agentlink.dispatch.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import world.agentlink.dispatch.RequestDispatcher;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

/**
 * Static catalog info for one item or block id. Returns the bits Claude actually needs:
 * stack size, durability, fuel value (where relevant), edibility, fire resistance, the
 * block form (if any), tags. Intentionally avoids "all NBT": those are runtime-only.
 */
public class GetItemInfoTool implements Tool {

    @Override
    public String name() {
        return "get_item_info";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        String idStr = RequestDispatcher.requireString(args, "id");
        ResourceLocation rl = ResourceLocation.tryParse(idStr);
        if (rl == null) throw new ToolException("INVALID_ARGS", "Invalid item id: " + idStr);

        Item item = BuiltInRegistries.ITEM.get(rl);
        if (item == Items_AIR_FALLBACK_OR_NULL(item, rl)) {
            // Fall through to block-only lookup below.
        }
        boolean foundItem = BuiltInRegistries.ITEM.containsKey(rl);

        JsonObject r = new JsonObject();
        r.addProperty("id", rl.toString());
        r.addProperty("found_as_item", foundItem);

        if (foundItem) {
            ItemStack stack = new ItemStack(item);
            r.addProperty("display_name", stack.getHoverName().getString());
            r.addProperty("max_stack", item.getMaxStackSize());
            r.addProperty("max_damage", item.getMaxDamage());
            r.addProperty("damageable", stack.isDamageableItem());
            r.addProperty("enchantable", item.isEnchantable(stack));
            r.addProperty("enchantment_value", item.getEnchantmentValue());
            r.addProperty("fire_resistant", stack.isEnderMask(null, null) || item.isFireResistant());
            r.addProperty("rarity", stack.getRarity().name().toLowerCase(java.util.Locale.ROOT));
            // Edibility
            if (item.isEdible() && item.getFoodProperties() != null) {
                JsonObject food = new JsonObject();
                var props = item.getFoodProperties();
                food.addProperty("nutrition", props.getNutrition());
                food.addProperty("saturation_modifier", props.getSaturationModifier());
                food.addProperty("can_always_eat", props.canAlwaysEat());
                food.addProperty("fast_food", props.isFastFood());
                food.addProperty("meat", props.isMeat());
                r.add("food", food);
            }
            // Fuel value (Forge's hook)
            try {
                int burn = net.minecraftforge.common.ForgeHooks.getBurnTime(stack, null);
                if (burn > 0) r.addProperty("burn_time_ticks", burn);
            } catch (Throwable ignored) {}
            // Tags
            JsonArray itemTags = new JsonArray();
            BuiltInRegistries.ITEM.getResourceKey(item).flatMap(k -> BuiltInRegistries.ITEM.getHolder(k))
                    .ifPresent(h -> h.tags().forEach((TagKey<Item> t) -> itemTags.add(t.location().toString())));
            r.add("item_tags", itemTags);
        }

        // Block form
        Block block = BuiltInRegistries.BLOCK.get(rl);
        boolean foundBlock = BuiltInRegistries.BLOCK.containsKey(rl);
        r.addProperty("found_as_block", foundBlock);
        if (foundBlock) {
            BlockState defaultState = block.defaultBlockState();
            JsonObject b = new JsonObject();
            b.addProperty("default_id", BuiltInRegistries.BLOCK.getKey(block).toString());
            b.addProperty("explosion_resistance", block.getExplosionResistance());
            b.addProperty("destroy_speed_default", defaultState.getDestroySpeed(null, null));
            b.addProperty("light_emission_default", defaultState.getLightEmission());
            b.addProperty("is_air_default", defaultState.isAir());
            b.addProperty("is_solid_default", defaultState.isSolid());
            b.addProperty("requires_correct_tool_default", defaultState.requiresCorrectToolForDrops());
            JsonArray blockTags = new JsonArray();
            BuiltInRegistries.BLOCK.getResourceKey(block).flatMap(k -> BuiltInRegistries.BLOCK.getHolder(k))
                    .ifPresent(h -> h.tags().forEach((TagKey<Block> t) -> blockTags.add(t.location().toString())));
            b.add("block_tags", blockTags);
            r.add("block", b);
        }

        if (!foundItem && !foundBlock) {
            throw new ToolException("INVALID_ARGS", "Unknown id (not in item or block registries): " + idStr);
        }
        return r;
    }

    // Stub helper to suppress an unused-warning trick; the real check is foundItem above.
    private static Item Items_AIR_FALLBACK_OR_NULL(Item item, ResourceLocation rl) {
        return item;
    }
}

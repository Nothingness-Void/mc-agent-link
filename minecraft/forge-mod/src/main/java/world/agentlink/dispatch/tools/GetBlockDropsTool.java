package world.agentlink.dispatch.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.phys.Vec3;
import world.agentlink.dispatch.RequestDispatcher;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

import java.util.HashMap;
import java.util.Map;

/**
 * Computes the loot table drops for a block under a configured tool/enchantment combo.
 *
 * <p>Result is one randomized roll (vanilla loot tables use {@code RandomSource}); call
 * multiple times for a distribution. {@code expected_rolls} arg averages {@code rolls}
 * iterations and returns mean counts to save round-trips.
 *
 * <p>Tool detection rules (last write wins):
 * <ul>
 *   <li>{@code tool}: item id, e.g. {@code minecraft:diamond_pickaxe}</li>
 *   <li>{@code fortune}: int 0-3</li>
 *   <li>{@code silk_touch}: bool</li>
 *   <li>{@code tool_nbt}: optional raw NBT (rarely needed)</li>
 * </ul>
 *
 * <p>The fake player used for the LootContext is real but not in the player list, so
 * advancements/mods looking for "player who broke this" do not see them.
 */
public class GetBlockDropsTool implements Tool {

    private final MinecraftServer mc;

    public GetBlockDropsTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "get_block_drops";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        ServerLevel level = GetBlockTool.resolveDimension(mc, args);
        int x = GetBlockTool.requireInt(args, "x");
        int y = GetBlockTool.requireInt(args, "y");
        int z = GetBlockTool.requireInt(args, "z");
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            JsonObject r = new JsonObject();
            r.addProperty("note", "Block at position is air; no drops");
            r.add("drops", new JsonArray());
            return r;
        }

        ItemStack tool = buildTool(args);
        int rolls = args.has("rolls") && !args.get("rolls").isJsonNull() ? args.get("rolls").getAsInt() : 1;
        if (rolls < 1) rolls = 1;
        if (rolls > 1000) rolls = 1000;

        Map<String, double[]> totals = new HashMap<>(); // [count_total, observations]
        for (int i = 0; i < rolls; i++) {
            LootParams.Builder builder = new LootParams.Builder(level)
                    .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                    .withParameter(LootContextParams.TOOL, tool)
                    .withParameter(LootContextParams.BLOCK_STATE, state)
                    .withOptionalParameter(LootContextParams.BLOCK_ENTITY, level.getBlockEntity(pos));
            for (ItemStack stack : state.getDrops(builder)) {
                if (stack.isEmpty()) continue;
                ResourceLocation rl = BuiltInRegistries.ITEM.getKey(stack.getItem());
                String id = rl == null ? "minecraft:unknown" : rl.toString();
                double[] cell = totals.computeIfAbsent(id, k -> new double[]{0, 0});
                cell[0] += stack.getCount();
            }
        }
        for (double[] cell : totals.values()) cell[1] = rolls;

        JsonArray drops = new JsonArray();
        for (Map.Entry<String, double[]> e : totals.entrySet()) {
            JsonObject d = new JsonObject();
            d.addProperty("id", e.getKey());
            d.addProperty("total_count", (long) e.getValue()[0]);
            d.addProperty("avg_per_break", e.getValue()[0] / e.getValue()[1]);
            drops.add(d);
        }

        JsonObject r = new JsonObject();
        r.addProperty("dim", level.dimension().location().toString());
        JsonObject p = new JsonObject();
        p.addProperty("x", x); p.addProperty("y", y); p.addProperty("z", z);
        r.add("pos", p);
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        r.addProperty("block", blockId == null ? "minecraft:unknown" : blockId.toString());
        ResourceLocation toolId = BuiltInRegistries.ITEM.getKey(tool.getItem());
        r.addProperty("tool", tool.isEmpty() ? "minecraft:air" : (toolId == null ? "minecraft:unknown" : toolId.toString()));
        r.addProperty("fortune", EnchantmentHelper.getItemEnchantmentLevel(Enchantments.BLOCK_FORTUNE, tool));
        r.addProperty("silk_touch", EnchantmentHelper.getItemEnchantmentLevel(Enchantments.SILK_TOUCH, tool) > 0);
        r.addProperty("rolls", rolls);
        r.add("drops", drops);
        return r;
    }

    private ItemStack buildTool(JsonObject args) throws ToolException {
        String toolId = args.has("tool") && !args.get("tool").isJsonNull()
                ? args.get("tool").getAsString() : "";
        ItemStack tool;
        if (toolId.isBlank()) {
            tool = ItemStack.EMPTY;
        } else {
            ResourceLocation rl = ResourceLocation.tryParse(toolId);
            if (rl == null) throw new ToolException("INVALID_ARGS", "Invalid tool id: " + toolId);
            var item = BuiltInRegistries.ITEM.get(rl);
            if (item == Items.AIR && !rl.toString().equals("minecraft:air")) {
                throw new ToolException("INVALID_ARGS", "Unknown tool id: " + toolId);
            }
            tool = new ItemStack(item);
        }

        int fortune = args.has("fortune") && !args.get("fortune").isJsonNull()
                ? args.get("fortune").getAsInt() : 0;
        if (fortune < 0) fortune = 0;
        if (fortune > 3) fortune = 3;
        boolean silk = args.has("silk_touch") && !args.get("silk_touch").isJsonNull()
                && args.get("silk_touch").getAsBoolean();

        if (!tool.isEmpty()) {
            if (fortune > 0) tool.enchant(Enchantments.BLOCK_FORTUNE, fortune);
            if (silk) tool.enchant(Enchantments.SILK_TOUCH, 1);
        }
        return tool;
    }
}

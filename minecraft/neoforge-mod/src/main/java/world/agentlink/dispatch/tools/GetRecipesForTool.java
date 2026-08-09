package world.agentlink.dispatch.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.item.crafting.SmithingRecipe;
import world.agentlink.dispatch.RequestDispatcher;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

import java.util.Locale;

/**
 * Returns crafting / smelting / cooking / stonecutting / smithing recipes whose result
 * is the requested item id. Datapack-modified recipes are included automatically because
 * we read from the server's live RecipeManager.
 */
public class GetRecipesForTool implements Tool {

    private final MinecraftServer mc;

    public GetRecipesForTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "get_recipes_for";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        String idStr = RequestDispatcher.requireString(args, "id");
        ResourceLocation rl = ResourceLocation.tryParse(idStr);
        if (rl == null) throw new ToolException("INVALID_ARGS", "Invalid item id: " + idStr);
        Item targetItem = BuiltInRegistries.ITEM.get(rl);
        if (!BuiltInRegistries.ITEM.containsKey(rl)) {
            throw new ToolException("INVALID_ARGS", "Unknown item: " + idStr);
        }

        RecipeManager rm = mc.getRecipeManager();
        RegistryAccess access = mc.registryAccess();

        JsonObject r = new JsonObject();
        r.addProperty("id", rl.toString());
        JsonArray recipes = new JsonArray();

        for (RecipeHolder<?> holder : rm.getRecipes()) {
            Recipe<?> recipe = holder.value();
            ItemStack result;
            try {
                result = recipe.getResultItem(access);
            } catch (Exception ex) {
                continue;
            }
            if (result.isEmpty() || result.getItem() != targetItem) continue;

            JsonObject o = new JsonObject();
            o.addProperty("id", holder.id().toString());
            o.addProperty("type", recipeTypeId(recipe).toString());
            o.addProperty("group", recipe.getGroup());
            o.addProperty("result_count", result.getCount());

            // Specific shape info when available.
            if (recipe instanceof ShapedRecipe shaped) {
                o.addProperty("category", "crafting_shaped");
                o.addProperty("width", shaped.getWidth());
                o.addProperty("height", shaped.getHeight());
                o.add("ingredients", encodeIngredients(shaped.getIngredients()));
            } else if (recipe instanceof ShapelessRecipe) {
                o.addProperty("category", "crafting_shapeless");
                o.add("ingredients", encodeIngredients(recipe.getIngredients()));
            } else if (recipe instanceof SmeltingRecipe smelt) {
                furnaceLike(o, smelt, "smelting");
            } else if (recipe instanceof BlastingRecipe blast) {
                furnaceLike(o, blast, "blasting");
            } else if (recipe instanceof SmokingRecipe smoke) {
                furnaceLike(o, smoke, "smoking");
            } else if (recipe instanceof CampfireCookingRecipe camp) {
                furnaceLike(o, camp, "campfire");
            } else if (recipe instanceof StonecutterRecipe) {
                o.addProperty("category", "stonecutter");
                o.add("ingredients", encodeIngredients(recipe.getIngredients()));
            } else if (recipe instanceof SmithingRecipe) {
                o.addProperty("category", "smithing");
                o.add("ingredients", encodeIngredients(recipe.getIngredients()));
            } else {
                o.addProperty("category", "other");
                o.add("ingredients", encodeIngredients(recipe.getIngredients()));
            }
            recipes.add(o);
        }

        r.addProperty("count", recipes.size());
        r.add("recipes", recipes);
        return r;
    }

    private static <T extends net.minecraft.world.item.crafting.AbstractCookingRecipe> void furnaceLike(
            JsonObject o, T recipe, String label) {
        o.addProperty("category", label);
        o.addProperty("cooking_time_ticks", recipe.getCookingTime());
        o.addProperty("experience", recipe.getExperience());
        o.add("ingredients", encodeIngredients(recipe.getIngredients()));
    }

    private static JsonArray encodeIngredients(NonNullList<Ingredient> ingredients) {
        JsonArray arr = new JsonArray();
        for (Ingredient ing : ingredients) {
            JsonArray choices = new JsonArray();
            ItemStack[] items;
            try {
                items = ing.getItems();
            } catch (Exception ex) {
                items = new ItemStack[0];
            }
            for (ItemStack stack : items) {
                if (stack.isEmpty()) continue;
                ResourceLocation rl = BuiltInRegistries.ITEM.getKey(stack.getItem());
                JsonObject c = new JsonObject();
                c.addProperty("id", rl == null ? "minecraft:unknown" : rl.toString());
                c.addProperty("count", stack.getCount());
                choices.add(c);
            }
            JsonObject ingObj = new JsonObject();
            ingObj.addProperty("empty", items.length == 0);
            ingObj.add("choices", choices);
            arr.add(ingObj);
        }
        return arr;
    }

    private static ResourceLocation recipeTypeId(Recipe<?> recipe) {
        RecipeType<?> type = recipe.getType();
        ResourceLocation rl = BuiltInRegistries.RECIPE_TYPE.getKey(type);
        return rl == null ? ResourceLocation.withDefaultNamespace("unknown") : rl;
    }
}

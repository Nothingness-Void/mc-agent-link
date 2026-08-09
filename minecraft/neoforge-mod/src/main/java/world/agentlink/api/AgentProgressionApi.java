package world.agentlink.api;

import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Player recipes and advancement progress, using the live server registries. */
public final class AgentProgressionApi {

    public record AdvancementState(String id, boolean done, float percent,
                                    List<String> completed, List<String> remaining) {
        public AdvancementState {
            completed = List.copyOf(completed == null ? List.of() : completed);
            remaining = List.copyOf(remaining == null ? List.of() : remaining);
        }
    }

    AgentProgressionApi() {}

    public List<ResourceLocation> recipeIds(MinecraftServer server) throws AgentApiException {
        requireServer(server);
        return server.getRecipeManager().getRecipeIds().sorted().toList();
    }

    public int grantRecipes(MinecraftServer server, ServerPlayer player,
                            Collection<ResourceLocation> ids) throws AgentApiException {
        return updateRecipes(server, player, ids, true);
    }

    public int revokeRecipes(MinecraftServer server, ServerPlayer player,
                             Collection<ResourceLocation> ids) throws AgentApiException {
        return updateRecipes(server, player, ids, false);
    }

    public AdvancementState advancement(MinecraftServer server, ServerPlayer player,
                                        ResourceLocation id) throws AgentApiException {
        AdvancementHolder advancement = advancement(server, id);
        AdvancementProgress progress = player.getAdvancements().getOrStartProgress(advancement);
        return new AdvancementState(id.toString(), progress.isDone(), progress.getPercent(),
                iterable(progress.getCompletedCriteria()), iterable(progress.getRemainingCriteria()));
    }

    public int grantAdvancement(MinecraftServer server, ServerPlayer player,
                                ResourceLocation id, String criterion) throws AgentApiException {
        return updateAdvancement(server, player, id, criterion, true);
    }

    public int revokeAdvancement(MinecraftServer server, ServerPlayer player,
                                 ResourceLocation id, String criterion) throws AgentApiException {
        return updateAdvancement(server, player, id, criterion, false);
    }

    private int updateRecipes(MinecraftServer server, ServerPlayer player,
                              Collection<ResourceLocation> ids, boolean grant) throws AgentApiException {
        requireServer(server);
        requirePlayer(player);
        if (ids == null || ids.isEmpty()) throw new AgentApiException("INVALID_ARGS", "at least one recipe is required");
        List<RecipeHolder<?>> recipes = new ArrayList<>();
        for (ResourceLocation id : ids) {
            RecipeHolder<?> recipe = server.getRecipeManager().byKey(id).orElseThrow(() ->
                    new AgentApiException("NOT_FOUND", "Recipe not found: " + id));
            recipes.add(recipe);
        }
        return grant ? player.awardRecipes(recipes) : player.resetRecipes(recipes);
    }

    private int updateAdvancement(MinecraftServer server, ServerPlayer player,
                                  ResourceLocation id, String criterion, boolean grant)
            throws AgentApiException {
        requireServer(server);
        requirePlayer(player);
        AdvancementHolder advancement = advancement(server, id);
        List<String> criteria = new ArrayList<>();
        if (criterion == null || criterion.isBlank() || "*".equals(criterion.trim())) {
            criteria.addAll(advancement.value().criteria().keySet());
        } else {
            if (!advancement.value().criteria().containsKey(criterion)) {
                throw new AgentApiException("NOT_FOUND", "Criterion not found: " + criterion);
            }
            criteria.add(criterion);
        }
        int changed = 0;
        for (String name : criteria) {
            boolean didChange = grant
                    ? player.getAdvancements().award(advancement, name)
                    : player.getAdvancements().revoke(advancement, name);
            if (didChange) changed++;
        }
        return changed;
    }

    private static AdvancementHolder advancement(MinecraftServer server, ResourceLocation id)
            throws AgentApiException {
        if (id == null) throw new AgentApiException("INVALID_ARGS", "advancement id is required");
        AdvancementHolder advancement = server.getAdvancements().get(id);
        if (advancement == null) throw new AgentApiException("NOT_FOUND", "Advancement not found: " + id);
        return advancement;
    }

    private static List<String> iterable(Iterable<String> values) {
        List<String> result = new ArrayList<>();
        for (String value : values) result.add(value);
        return result;
    }

    private static void requireServer(MinecraftServer server) throws AgentApiException {
        if (server == null) throw new AgentApiException("SERVER_UNAVAILABLE", "server is not running");
    }

    private static void requirePlayer(ServerPlayer player) throws AgentApiException {
        if (player == null) throw new AgentApiException("NOT_FOUND", "player is required");
    }
}

package world.agentlink.dispatch.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import world.agentlink.api.AgentApiException;
import world.agentlink.api.AgentLinkApi;
import world.agentlink.api.AgentProgressionApi;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolArgs;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

import java.util.ArrayList;
import java.util.List;

/** Typed recipe and advancement management for an online player. */
public final class ManageProgressionTool implements Tool {

    private final MinecraftServer mc;

    public ManageProgressionTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "manage_player_progression";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        String action = ToolArgs.requireString(args, "action").trim().toLowerCase();
        try {
            if ("list_recipes".equals(action)) return listRecipes(args);
            ServerPlayer player = AgentLinkApi.players().online(mc, ToolArgs.requireString(args, "name"));
            return switch (action) {
                case "grant_recipes" -> recipes(player, args, true);
                case "revoke_recipes" -> recipes(player, args, false);
                case "inspect_advancement" -> inspect(player, args);
                case "grant_advancement" -> advancement(player, args, true);
                case "revoke_advancement" -> advancement(player, args, false);
                default -> throw new ToolException("INVALID_ARGS", "Unknown progression action: " + action);
            };
        } catch (AgentApiException ex) {
            throw new ToolException(ex.code(), ex.getMessage());
        }
    }

    private JsonObject listRecipes(JsonObject args) throws AgentApiException {
        String filter = ToolArgs.optString(args, "contains", "").toLowerCase();
        int limit = ToolArgs.optIntClamped(args, "limit", 200, 1, 1000);
        JsonArray recipes = new JsonArray();
        for (ResourceLocation id : AgentLinkApi.progression().recipeIds(mc)) {
            if (!filter.isBlank() && !id.toString().toLowerCase().contains(filter)) continue;
            recipes.add(id.toString());
            if (recipes.size() >= limit) break;
        }
        JsonObject result = new JsonObject();
        result.addProperty("count", recipes.size());
        result.add("recipes", recipes);
        result.addProperty("limit", limit);
        if (recipes.size() == limit) result.addProperty("truncated", true);
        return result;
    }

    private JsonObject recipes(ServerPlayer player, JsonObject args, boolean grant)
            throws AgentApiException, ToolException {
        List<ResourceLocation> ids = resourceIds(args, "recipes", "recipe");
        int changed = grant
                ? AgentLinkApi.progression().grantRecipes(mc, player, ids)
                : AgentLinkApi.progression().revokeRecipes(mc, player, ids);
        JsonObject result = base(grant ? "grant_recipes" : "revoke_recipes", player);
        result.addProperty("changed", changed);
        result.add("recipes", strings(ids));
        return result;
    }

    private JsonObject inspect(ServerPlayer player, JsonObject args) throws AgentApiException, ToolException {
        ResourceLocation id = resourceId(ToolArgs.requireString(args, "advancement"), "advancement");
        AgentProgressionApi.AdvancementState state = AgentLinkApi.progression().advancement(mc, player, id);
        JsonObject result = base("inspect_advancement", player);
        result.addProperty("advancement", state.id());
        result.addProperty("done", state.done());
        result.addProperty("percent", state.percent());
        result.add("completed", strings(state.completed()));
        result.add("remaining", strings(state.remaining()));
        return result;
    }

    private JsonObject advancement(ServerPlayer player, JsonObject args, boolean grant)
            throws AgentApiException, ToolException {
        ResourceLocation id = resourceId(ToolArgs.requireString(args, "advancement"), "advancement");
        String criterion = ToolArgs.optString(args, "criterion", "*");
        int changed = grant
                ? AgentLinkApi.progression().grantAdvancement(mc, player, id, criterion)
                : AgentLinkApi.progression().revokeAdvancement(mc, player, id, criterion);
        JsonObject result = base(grant ? "grant_advancement" : "revoke_advancement", player);
        result.addProperty("advancement", id.toString());
        result.addProperty("criterion", criterion);
        result.addProperty("changed", changed);
        return result;
    }

    private static List<ResourceLocation> resourceIds(JsonObject args, String listKey, String singleKey)
            throws ToolException {
        List<String> raw;
        if (args.has(listKey)) raw = ToolArgs.requireStringList(args, listKey);
        else raw = List.of(ToolArgs.requireString(args, singleKey));
        List<ResourceLocation> ids = new ArrayList<>();
        for (String value : raw) ids.add(resourceId(value, listKey));
        return ids;
    }

    private static ResourceLocation resourceId(String raw, String label) throws ToolException {
        ResourceLocation id = ResourceLocation.tryParse(raw == null ? "" : raw.trim());
        if (id == null) throw new ToolException("INVALID_ARGS", "Invalid " + label + " id: " + raw);
        return id;
    }

    private static JsonArray strings(List<?> values) {
        JsonArray result = new JsonArray();
        for (Object value : values) result.add(String.valueOf(value));
        return result;
    }

    private static JsonObject base(String action, ServerPlayer player) {
        JsonObject result = new JsonObject();
        result.addProperty("action", action);
        result.addProperty("name", player.getGameProfile().getName());
        result.addProperty("uuid", player.getUUID().toString());
        return result;
    }
}

package world.agentlink.dispatch.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import world.agentlink.api.AgentApiException;
import world.agentlink.api.AgentLinkApi;
import world.agentlink.api.AgentServerControlApi;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolArgs;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

import java.util.ArrayList;
import java.util.List;

/** List, select, and reload server data packs through PackRepository. */
public final class ManageDatapacksTool implements Tool {

    private final MinecraftServer mc;

    public ManageDatapacksTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "manage_datapacks";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        String action = ToolArgs.optEnum(args, "action", "list");
        try {
            AgentServerControlApi.PackState state = AgentLinkApi.serverControl().dataPacks(mc, true);
            if ("list".equals(action)) return result(action, state);
            if ("reload".equals(action)) {
                AgentLinkApi.serverControl().reloadResources(mc);
                return result(action, AgentLinkApi.serverControl().dataPacks(mc, false));
            }
            if (!"enable".equals(action) && !"disable".equals(action)) {
                throw new ToolException("INVALID_ARGS", "action must be list, enable, disable, or reload");
            }
            List<String> selected = new ArrayList<>(state.selected());
            List<String> ids = args.has("ids")
                    ? ToolArgs.requireStringList(args, "ids")
                    : List.of(ToolArgs.requireString(args, "id"));
            for (String id : ids) {
                if ("enable".equals(action) && !selected.contains(id)) selected.add(id);
                if ("disable".equals(action)) selected.remove(id);
            }
            return result(action, AgentLinkApi.serverControl().selectDataPacks(mc, selected));
        } catch (AgentApiException ex) {
            throw new ToolException(ex.code(), ex.getMessage());
        }
    }

    private static JsonObject result(String action, AgentServerControlApi.PackState state) {
        JsonObject result = new JsonObject();
        result.addProperty("action", action);
        result.add("available", strings(state.available()));
        result.add("selected", strings(state.selected()));
        result.addProperty("count_available", state.available().size());
        result.addProperty("count_selected", state.selected().size());
        return result;
    }

    private static JsonArray strings(List<String> values) {
        JsonArray result = new JsonArray();
        for (String value : values) result.add(value);
        return result;
    }
}

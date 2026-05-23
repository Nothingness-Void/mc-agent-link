package world.agentlink.dispatch.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModInfo;
import world.agentlink.dispatch.Tool;
import world.agentlink.transport.ClientSession;

public class ListModsTool implements Tool {
    private final MinecraftServer mc;

    public ListModsTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "list_mods";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) {
        JsonArray mods = new JsonArray();
        for (IModInfo info : ModList.get().getMods()) {
            JsonObject m = new JsonObject();
            m.addProperty("mod_id", info.getModId());
            m.addProperty("display_name", info.getDisplayName());
            m.addProperty("version", info.getVersion().toString());
            String desc = info.getDescription();
            if (desc != null) {
                String trimmed = desc.strip();
                if (!trimmed.isEmpty()) m.addProperty("description", trimmed);
            }
            mods.add(m);
        }
        JsonObject r = new JsonObject();
        r.add("mods", mods);
        r.addProperty("count", mods.size());
        r.addProperty("loader", "forge");
        return r;
    }
}

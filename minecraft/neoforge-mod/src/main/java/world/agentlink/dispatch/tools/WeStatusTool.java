package world.agentlink.dispatch.tools;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;
import world.agentlink.we.WorldEditBridge;

/**
 * Probes WorldEdit / FAWE installation. Read-only; auto-allowed.
 */
public class WeStatusTool implements Tool {

    public WeStatusTool(MinecraftServer mc) {}

    @Override
    public String name() {
        return "we_status";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        boolean available = WorldEditBridge.isAvailable();
        JsonObject r = new JsonObject();
        r.addProperty("available", available);
        if (!available) {
            r.addProperty("hint", "Install WorldEdit (https://enginehub.org/worldedit) or FAWE to enable we_* tools.");
            return r;
        }
        r.addProperty("implementation", WorldEditBridge.implementation());
        String v = WorldEditBridge.version();
        if (v != null) r.addProperty("version", v);
        String fv = WorldEditBridge.faweVersion();
        if (fv != null) r.addProperty("fawe_version", fv);
        r.addProperty("undo_depth", WorldEditBridge.undoDepth());
        return r;
    }
}

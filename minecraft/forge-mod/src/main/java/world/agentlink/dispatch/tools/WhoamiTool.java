package world.agentlink.dispatch.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import world.agentlink.approval.CallTier;
import world.agentlink.config.AgentLinkConfig;
import world.agentlink.dispatch.RequestDispatcher;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.sandbox.BuildZones;
import world.agentlink.task.TaskManager;
import world.agentlink.transport.ClientSession;
import world.agentlink.we.WorldEditBridge;
import world.agentlink.world.BlockWriter;

import java.util.List;

/**
 * Report what this caller is actually allowed to do.
 *
 * <h2>Why</h2>
 * Until now the agent discovered its own limits by hitting them: call a tool, get
 * {@code APPROVAL_DENIED}, guess whether the fix is "wait for an OP", "ask the operator to widen
 * write_allow", or "this token can't ever do that". That wastes calls, spams approval prompts at
 * players, and produces confidently-wrong explanations to the user.
 *
 * <p>One read-only call answers it up front: which tier the token has, which tools bypass approval,
 * which require an admin, where writes are permitted, which build zones exist, whether an admin is
 * even online to approve anything, and which optional integrations are present. An agent that reads
 * this first can plan instead of probing.
 */
public class WhoamiTool implements Tool {

    private final MinecraftServer mc;

    public WhoamiTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "whoami";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        AgentLinkConfig.Snapshot cfg = AgentLinkConfig.get();
        JsonObject r = new JsonObject();
        r.addProperty("agentlink_version", cfg == null ? "unknown" : cfg.version());
        r.addProperty("minecraft_version", mc.getServerVersion());
        r.addProperty("server_brand", mc.getServerModName());

        // --- caller identity -------------------------------------------------
        CallTier.Tier tier = CallTier.current();
        JsonObject caller = new JsonObject();
        caller.addProperty("token_tier", tier.name().toLowerCase(java.util.Locale.ROOT));
        caller.addProperty("transport", session == null ? "mcp_http" : "websocket");
        caller.addProperty("bypasses_all_approval", tier == CallTier.Tier.CONSOLE);
        caller.addProperty("explanation", tier == CallTier.Tier.CONSOLE
                ? "CONSOLE token (minted by /agentlink pair): every tool runs immediately, including"
                        + " admin-only ones. No in-game prompts will appear for your calls."
                : "GUEST token (minted by /agentlink pair-guest, or the legacy master token): calls"
                        + " outside auto_allow_tools need in-game approval, and admin_only_tools need"
                        + " an approver whose UUID is in roles.admin_uuids.");
        r.add("caller", caller);

        if (cfg == null) return r;

        // --- approval posture ------------------------------------------------
        JsonObject approval = new JsonObject();
        approval.addProperty("enabled", cfg.approvalEnabled());
        approval.addProperty("timeout_seconds", cfg.approvalTimeoutSeconds());
        approval.add("auto_allow_tools", toArray(cfg.approvalAutoAllowTools()));
        approval.add("admin_only_tools", toArray(cfg.approvalAdminOnlyTools()));
        approval.add("trusted_rules", toArray(cfg.approvalTrustedTools()));

        List<java.util.UUID> admins = cfg.roleAdminUuids();
        boolean adminsConfigured = admins != null && !admins.isEmpty();
        approval.addProperty("admins_configured", adminsConfigured);
        approval.addProperty("admin_count", adminsConfigured ? admins.size() : 0);

        // Who could actually approve right now. A GUEST agent about to call an admin-only tool with
        // nobody eligible online will be denied before any prompt is sent — better to know now.
        int onlineOps = 0;
        int onlineAdmins = 0;
        JsonArray approvers = new JsonArray();
        for (ServerPlayer p : mc.getPlayerList().getPlayers()) {
            if (!p.hasPermissions(2)) continue;
            onlineOps++;
            boolean isAdmin = adminsConfigured && admins.contains(p.getUUID());
            if (isAdmin) onlineAdmins++;
            JsonObject o = new JsonObject();
            o.addProperty("name", p.getGameProfile().getName());
            o.addProperty("uuid", p.getUUID().toString());
            o.addProperty("is_admin", isAdmin);
            approvers.add(o);
        }
        approval.addProperty("online_ops", onlineOps);
        approval.addProperty("online_admins", onlineAdmins);
        approval.add("eligible_approvers", approvers);
        if (tier == CallTier.Tier.GUEST && cfg.approvalEnabled()) {
            if (onlineOps == 0) {
                approval.addProperty("warning", "No OP is online, so any call needing approval will be"
                        + " denied immediately (DENIED_NO_APPROVERS). Ask the user to have an operator"
                        + " log in, or to re-pair with /agentlink pair for a console-tier token.");
            } else if (adminsConfigured && onlineAdmins == 0) {
                approval.addProperty("warning", "OPs are online but none are in roles.admin_uuids, so"
                        + " admin_only_tools cannot be approved right now.");
            }
        }
        r.add("approval", approval);

        // --- write surfaces --------------------------------------------------
        JsonObject writes = new JsonObject();
        writes.add("file_write_allow", toArray(cfg.writeAllow()));
        writes.add("file_write_deny", toArray(cfg.writeDeny()));
        writes.add("build_zones", BuildZones.toJsonArray());
        writes.addProperty("build_zones_note", BuildZones.anyConfigured()
                ? "Spatial edits whose ENTIRE region fits inside one of these zones skip in-game"
                        + " approval. An edit straddling a boundary still prompts."
                : "No build zones configured, so every spatial edit goes through approval for a GUEST"
                        + " token. The operator can add [[build_zones]] to config/agent-link.toml.");
        writes.addProperty("native_undo_depth", BlockWriter.undoDepth());
        r.add("writes", writes);

        // --- limits the agent should plan around -----------------------------
        JsonObject limits = new JsonObject();
        limits.addProperty("fill_blocks_sync_max_volume", FillBlocksTool.SYNC_MAX_VOLUME);
        limits.addProperty("fill_blocks_task_max_volume", FillBlocksTool.TASK_MAX_VOLUME);
        limits.addProperty("set_blocks_sync_max", SetBlocksTool.SYNC_MAX_BLOCKS);
        limits.addProperty("set_blocks_task_max", SetBlocksTool.TASK_MAX_BLOCKS);
        limits.addProperty("find_blocks_sync_max_volume", FindBlocksTool.SYNC_MAX_VOLUME);
        limits.addProperty("get_blocks_region_max_volume", 4096);
        limits.addProperty("task_max_concurrent", cfg.taskMaxConcurrent());
        limits.addProperty("task_blocks_per_tick", cfg.taskBlocksPerTick());
        limits.addProperty("note", "Exceeding a sync limit returns VOLUME_TOO_LARGE with the exact"
                + " start_task call to use instead — don't pre-split regions yourself.");
        r.add("limits", limits);

        // --- optional integrations ------------------------------------------
        JsonObject integrations = new JsonObject();
        boolean weAvailable = WorldEditBridge.isAvailable();
        integrations.addProperty("worldedit", weAvailable);
        if (weAvailable) {
            integrations.addProperty("worldedit_impl", WorldEditBridge.implementation());
            integrations.addProperty("worldedit_undo_depth", WorldEditBridge.undoDepth());
        }
        integrations.addProperty("spark", world.agentlink.spark.SparkBridge.isAvailable(mc));
        r.add("integrations", integrations);

        // --- runtime bookkeeping --------------------------------------------
        JsonObject runtime = new JsonObject();
        TaskManager tasks = TaskManager.current();
        runtime.addProperty("tasks_available", tasks != null);
        if (tasks != null) runtime.addProperty("tasks_running", tasks.runningCount());
        runtime.addProperty("audit_enabled", cfg.auditEnabled());
        RequestDispatcher dispatcher = RequestDispatcher.current();
        if (dispatcher != null) {
            runtime.addProperty("addon_tool_count", dispatcher.addonTools().size());
            JsonArray addons = new JsonArray();
            for (RequestDispatcher.RegisteredEntry e : dispatcher.addonTools()) addons.add(e.fullName());
            runtime.add("addon_tools", addons);
        }
        r.add("runtime", runtime);
        return r;
    }

    private static JsonArray toArray(List<String> items) {
        JsonArray arr = new JsonArray();
        if (items != null) for (String s : items) arr.add(s);
        return arr;
    }
}

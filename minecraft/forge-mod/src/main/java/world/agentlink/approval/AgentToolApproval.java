package world.agentlink.approval;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import world.agentlink.AgentLinkMod;
import world.agentlink.config.AgentLinkConfig;
import world.agentlink.config.AgentLinkConfig.Snapshot;
import world.agentlink.i18n.AgentLinkLang;
import world.agentlink.transport.ClientSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class AgentToolApproval {
    private static final Gson GSON = new Gson();
    private static final AtomicLong NEXT_ID = new AtomicLong(1);
    private static final Set<String> NEVER_TRUST_TOOLS = Set.of(
            "run_console_command", "write_config_file", "broadcast",
            "spark_profiler_start", "spark_profiler_stop", "spark_profiler_cancel"
    );
    /**
     * Tools whose own approval flow can be skipped entirely if the operator pinned a parameter
     * pattern. {@code run_console_command} is the canonical case — pinning {@code command=say *}
     * is far less risky than blanket-trusting the whole tool, so the {@link #NEVER_TRUST_TOOLS}
     * check applies only to whole-tool trust.
     */
    private static final Set<String> PARAM_TRUSTABLE_TOOLS = Set.of(
            "run_console_command", "broadcast", "write_config_file"
    );
    private static volatile AgentToolApproval CURRENT;

    private final MinecraftServer mc;
    private final Snapshot cfg;
    private final List<TrustRule> trustedRules = new CopyOnWriteArrayList<>();
    private final Map<String, PendingApproval> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    private AgentToolApproval(MinecraftServer mc, Snapshot cfg) {
        this.mc = mc;
        this.cfg = cfg;
        for (String raw : cfg.approvalTrustedTools()) {
            TrustRule rule = TrustRule.parse(raw);
            if (rule == null) {
                AgentLinkMod.LOG.warn("agent-link: ignoring malformed approval.trusted_tools entry: {}", raw);
                continue;
            }
            if (!trustedRules.contains(rule)) trustedRules.add(rule);
        }
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agent-link-approval-timeout");
            t.setDaemon(true);
            return t;
        });
    }

    public static AgentToolApproval start(MinecraftServer mc, Snapshot cfg) {
        AgentToolApproval next = new AgentToolApproval(mc, cfg);
        CURRENT = next;
        return next;
    }

    public static AgentToolApproval current() {
        return CURRENT;
    }

    public static void stopCurrent() {
        AgentToolApproval cur = CURRENT;
        CURRENT = null;
        if (cur != null) cur.stop();
    }

    public CompletableFuture<Decision> request(String toolName, JsonObject args, ClientSession session) {
        if (!cfg.approvalEnabled()) {
            AgentLinkMod.LOG.info("agent-link approval bypassed for tool {}: approval disabled", toolName);
            return CompletableFuture.completedFuture(Decision.approved("approval disabled"));
        }
        String normalizedTool = normalizeTool(toolName);
        if (isAutoAllowed(normalizedTool)) {
            AgentLinkMod.LOG.info("agent-link approval bypassed for tool {}: auto-allowed", toolName);
            return CompletableFuture.completedFuture(Decision.approved("auto allowed"));
        }
        TrustRule trustHit = matchTrusted(normalizedTool, args);
        if (trustHit != null) {
            AgentLinkMod.LOG.info("agent-link approval bypassed for tool {}: trusted by rule {}", toolName, trustHit);
            return CompletableFuture.completedFuture(Decision.approved("trusted: " + trustHit));
        }

        // admin_only_tools narrows WHO sees the button (admin-only) when admins are configured.
        // When admin_uuids is empty, role-tiering is disabled and every OP can approve every tool —
        // legacy behavior; never short-circuit to a hard deny, otherwise non-admin servers lose the
        // ability to approve anything.
        boolean adminOnly = isAdminOnly(normalizedTool);
        ArrayList<ServerPlayer> ops = onlineApprovers(adminOnly);
        if (ops.isEmpty()) {
            java.util.List<java.util.UUID> admins = cfg.roleAdminUuids();
            boolean adminsConfigured = admins != null && !admins.isEmpty();
            String reason;
            if (adminOnly && adminsConfigured) {
                reason = AgentLinkLang.tr("agentlink.approval.no_online_admins", toolName);
            } else {
                reason = AgentLinkLang.tr("agentlink.approval.no_online_ops", toolName);
            }
            AgentLinkMod.LOG.warn("agent-link approval denied before prompt for tool {}: {}", toolName, reason);
            return CompletableFuture.completedFuture(Decision.denied(reason));
        }

        String id = "approval-" + NEXT_ID.getAndIncrement();
        JsonObject argsCopy = args == null ? new JsonObject() : args.deepCopy();
        String argsText = GSON.toJson(argsCopy);
        PendingApproval approval = new PendingApproval(id, toolName, normalizedTool, adminOnly, argsCopy, argsText, System.currentTimeMillis(), new CompletableFuture<>());
        pending.put(id, approval);
        ScheduledFuture<?> timeout = scheduler.schedule(() -> timeout(id), cfg.approvalTimeoutSeconds(), TimeUnit.SECONDS);
        approval.timeout(timeout);
        AgentLinkMod.LOG.info("agent-link approval queued {} for tool {} (adminOnly={}, approvers={})",
                id, toolName, adminOnly, ops.stream().map(ServerPlayer::getScoreboardName).toList());
        mc.execute(() -> sendPrompt(approval, ops));
        return approval.future();
    }

    public CommandResult approve(String id, String actor, java.util.UUID actorUuid, boolean hasOpPermission, boolean console) {
        PendingApproval approval = pending.remove(id);
        if (approval == null) return new CommandResult(false, AgentLinkLang.tr("agentlink.approval.no_pending", id));
        CommandResult auth = authorizeActor(approval, actorUuid, hasOpPermission, console);
        if (!auth.ok()) {
            pending.put(id, approval);
            return auth;
        }
        approval.cancelTimeout();
        approval.future().complete(Decision.approved(AgentLinkLang.tr("agentlink.approval.decision.approved_by", actor)));
        broadcastLocalized("agentlink.approval.broadcast.approved", ChatFormatting.GREEN, id, approval.toolName());
        return new CommandResult(true, AgentLinkLang.tr("agentlink.approval.command.approved", id));
    }

    public CommandResult deny(String id, String actor, java.util.UUID actorUuid, boolean hasOpPermission, boolean console) {
        PendingApproval approval = pending.remove(id);
        if (approval == null) return new CommandResult(false, AgentLinkLang.tr("agentlink.approval.no_pending", id));
        CommandResult auth = authorizeActor(approval, actorUuid, hasOpPermission, console);
        if (!auth.ok()) {
            pending.put(id, approval);
            return auth;
        }
        approval.cancelTimeout();
        approval.future().complete(Decision.denied(AgentLinkLang.tr("agentlink.approval.decision.denied_by", actor)));
        broadcastLocalized("agentlink.approval.broadcast.denied", ChatFormatting.RED, id, approval.toolName());
        return new CommandResult(true, AgentLinkLang.tr("agentlink.approval.command.denied", id));
    }

    public CommandResult trust(String id, String actor, java.util.UUID actorUuid, boolean hasOpPermission, boolean console) {
        PendingApproval approval = pending.remove(id);
        if (approval == null) return new CommandResult(false, AgentLinkLang.tr("agentlink.approval.no_pending", id));
        CommandResult auth = authorizeActor(approval, actorUuid, hasOpPermission, console);
        if (!auth.ok()) {
            pending.put(id, approval);
            return auth;
        }
        if (!isWholeTrustable(approval.normalizedTool())) {
            pending.put(id, approval);
            return new CommandResult(false, AgentLinkLang.tr("agentlink.approval.not_trustable", approval.toolName()));
        }
        approval.cancelTimeout();
        TrustRule rule = TrustRule.whole(approval.normalizedTool());
        addRule(rule);
        approval.future().complete(Decision.approved(AgentLinkLang.tr("agentlink.approval.decision.trusted_by", actor)));
        broadcastLocalized("agentlink.approval.broadcast.trusted", ChatFormatting.GREEN, id, approval.toolName());
        return new CommandResult(true, AgentLinkLang.tr("agentlink.approval.command.trusted", approval.toolName()));
    }

    /**
     * Trust a parameter-scoped rule derived from this approval. Built-in heuristic picks the
     * arg key (e.g. {@code command} for run_console_command, {@code message} for broadcast)
     * and turns the actual value into a glob: full string for short tokens, prefix-glob for
     * space-separated commands like {@code say hello world} → {@code say *}.
     */
    public CommandResult trustPattern(String id, String actor, java.util.UUID actorUuid, boolean hasOpPermission, boolean console) {
        PendingApproval approval = pending.remove(id);
        if (approval == null) return new CommandResult(false, AgentLinkLang.tr("agentlink.approval.no_pending", id));
        CommandResult auth = authorizeActor(approval, actorUuid, hasOpPermission, console);
        if (!auth.ok()) {
            pending.put(id, approval);
            return auth;
        }
        TrustRule rule = derivePatternRule(approval);
        if (rule == null) {
            pending.put(id, approval);
            return new CommandResult(false, AgentLinkLang.tr("agentlink.approval.pattern.unavailable", approval.toolName()));
        }
        approval.cancelTimeout();
        addRule(rule);
        approval.future().complete(Decision.approved(AgentLinkLang.tr("agentlink.approval.decision.trusted_by", actor)));
        broadcastLocalized("agentlink.approval.broadcast.trusted_pattern", ChatFormatting.GREEN, id, rule.toString());
        return new CommandResult(true, AgentLinkLang.tr("agentlink.approval.command.trusted_pattern", rule.toString()));
    }

    public CommandResult listTrust() {
        if (trustedRules.isEmpty()) return new CommandResult(true, AgentLinkLang.tr("agentlink.approval.trustlist.empty"));
        StringBuilder sb = new StringBuilder();
        sb.append(AgentLinkLang.tr("agentlink.approval.trustlist.header", trustedRules.size()));
        int i = 1;
        for (TrustRule rule : trustedRules) {
            sb.append('\n').append(i++).append(". ").append(rule);
        }
        return new CommandResult(true, sb.toString());
    }

    public CommandResult untrust(String ruleString) {
        if (ruleString == null || ruleString.isBlank()) {
            return new CommandResult(false, AgentLinkLang.tr("agentlink.approval.untrust.empty"));
        }
        TrustRule rule = TrustRule.parse(ruleString);
        if (rule == null) {
            return new CommandResult(false, AgentLinkLang.tr("agentlink.approval.untrust.invalid", ruleString));
        }
        boolean removedMem = trustedRules.removeIf(r -> r.equals(rule));
        boolean removedDisk = AgentLinkConfig.removeApprovalTrustedTool(rule.toString());
        if (!removedMem && !removedDisk) {
            return new CommandResult(false, AgentLinkLang.tr("agentlink.approval.untrust.not_found", rule.toString()));
        }
        return new CommandResult(true, AgentLinkLang.tr("agentlink.approval.untrust.removed", rule.toString()));
    }

    public CommandResult pendingSummary() {
        if (pending.isEmpty()) return new CommandResult(true, AgentLinkLang.tr("agentlink.approval.pending.none"));
        return new CommandResult(true, AgentLinkLang.tr("agentlink.approval.pending.some", pending.size(), String.join(", ", pending.keySet())));
    }

    private void timeout(String id) {
        PendingApproval approval = pending.remove(id);
        if (approval == null) return;
        approval.future().complete(Decision.denied(AgentLinkLang.tr("agentlink.approval.timeout_reason")));
        mc.execute(() -> broadcastLocalized("agentlink.approval.broadcast.timeout", ChatFormatting.YELLOW, id, approval.toolName()));
    }

    private boolean isAutoAllowed(String normalizedTool) {
        for (String allowed : cfg.approvalAutoAllowTools()) {
            String normalized = normalizeTool(allowed);
            if ("*".equals(normalized) || normalized.equals(normalizedTool)) return true;
        }
        return false;
    }

    private TrustRule matchTrusted(String normalizedTool, JsonObject args) {
        for (TrustRule rule : trustedRules) {
            // Whole-tool entries for NEVER_TRUST_TOOLS are ignored at match time so a stale toml
            // can't bypass the safety net even if hand-edited.
            if (!rule.isParameterized() && NEVER_TRUST_TOOLS.contains(rule.tool())) continue;
            if (rule.matches(normalizedTool, args)) return rule;
        }
        return null;
    }

    private boolean isAdminOnly(String normalizedTool) {
        java.util.List<String> list = cfg.approvalAdminOnlyTools();
        if (list == null) return false;
        for (String entry : list) {
            String normalized = normalizeTool(entry);
            if (normalized.isEmpty()) continue;
            if (normalized.equals(normalizedTool)) return true;
        }
        return false;
    }

    private CommandResult authorizeActor(PendingApproval approval, java.util.UUID actorUuid, boolean hasOpPermission, boolean console) {
        if (console) return new CommandResult(true, "ok");
        if (!hasOpPermission) {
            return new CommandResult(false, AgentLinkLang.tr("agentlink.approval.require_op", approval.toolName()));
        }
        if (approval.adminOnly() && adminsConfigured()) {
            if (actorUuid != null && cfg.roleAdminUuids().contains(actorUuid)) {
                return new CommandResult(true, "ok");
            }
            return new CommandResult(false, AgentLinkLang.tr("agentlink.approval.require_admin", approval.toolName()));
        }
        return new CommandResult(true, "ok");
    }

    private ArrayList<ServerPlayer> onlineApprovers() {
        return onlineApprovers(false);
    }

    private ArrayList<ServerPlayer> onlineApprovers(boolean adminOnly) {
        ArrayList<ServerPlayer> out = new ArrayList<>();
        if (adminOnly && adminsConfigured()) {
            java.util.List<java.util.UUID> admins = cfg.roleAdminUuids();
            for (ServerPlayer player : mc.getPlayerList().getPlayers()) {
                if (player.hasPermissions(2) && admins.contains(player.getUUID())) out.add(player);
            }
            return out;
        }
        for (ServerPlayer player : mc.getPlayerList().getPlayers()) {
            if (player.hasPermissions(2)) out.add(player);
        }
        return out;
    }

    private boolean adminsConfigured() {
        java.util.List<java.util.UUID> admins = cfg.roleAdminUuids();
        return admins != null && !admins.isEmpty();
    }

    private void sendPrompt(PendingApproval approval, ArrayList<ServerPlayer> ops) {
        String preview = approval.argsText();
        if (preview.length() > 240) preview = preview.substring(0, 240) + "...";
        boolean wholeTrustable = isWholeTrustable(approval.normalizedTool());
        TrustRule patternPreview = derivePatternRule(approval);
        for (ServerPlayer op : ops) {
            Component line1 = Component.literal(AgentLinkLang.tr(op, "agentlink.approval.prompt.request", approval.toolName())).withStyle(ChatFormatting.AQUA);
            Component line2 = Component.literal(AgentLinkLang.tr(op, "agentlink.approval.prompt.args", preview)).withStyle(ChatFormatting.GRAY);
            var actions = Component.literal("")
                    .append(button(AgentLinkLang.tr(op, "agentlink.approval.button.allow_once"), ChatFormatting.GREEN,
                            "/agentlink approve " + approval.id(), AgentLinkLang.tr(op, "agentlink.approval.hover.allow_once")))
                    .append(Component.literal(" "))
                    .append(button(AgentLinkLang.tr(op, "agentlink.approval.button.deny"), ChatFormatting.RED,
                            "/agentlink deny " + approval.id(), AgentLinkLang.tr(op, "agentlink.approval.hover.deny")));
            if (wholeTrustable) {
                actions = actions.append(Component.literal(" "))
                        .append(button(AgentLinkLang.tr(op, "agentlink.approval.button.trust"), ChatFormatting.GOLD,
                                "/agentlink trust " + approval.id(), AgentLinkLang.tr(op, "agentlink.approval.hover.trust")));
            }
            if (patternPreview != null) {
                actions = actions.append(Component.literal(" "))
                        .append(button(AgentLinkLang.tr(op, "agentlink.approval.button.trust_pattern", patternPreview.toString()),
                                ChatFormatting.LIGHT_PURPLE,
                                "/agentlink trustpattern " + approval.id(),
                                AgentLinkLang.tr(op, "agentlink.approval.hover.trust_pattern", patternPreview.toString())));
            }
            actions = actions.append(Component.literal(" "))
                    .append(copyButton(AgentLinkLang.tr(op, "agentlink.approval.button.copy_details"), approval.details(),
                            AgentLinkLang.tr(op, "agentlink.approval.hover.copy_details")));
            op.sendSystemMessage(line1);
            op.sendSystemMessage(line2);
            op.sendSystemMessage(actions);
        }
    }

    private Component button(String text, ChatFormatting color, String command, String hover) {
        return Component.literal(text).withStyle(style -> style
                .withColor(color)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover))));
    }

    private Component copyButton(String text, String details, String hover) {
        return Component.literal(text).withStyle(style -> style
                .withColor(ChatFormatting.YELLOW)
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, details))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover))));
    }

    private void broadcast(Component component) {
        for (ServerPlayer player : onlineApprovers()) {
            player.sendSystemMessage(component);
        }
    }

    private void broadcastLocalized(String key, ChatFormatting style, Object... args) {
        for (ServerPlayer player : onlineApprovers()) {
            player.sendSystemMessage(Component.literal("[Agent Link] " + AgentLinkLang.tr(player, key, args)).withStyle(style));
        }
    }

    private void addRule(TrustRule rule) {
        if (!trustedRules.contains(rule)) trustedRules.add(rule);
        AgentLinkConfig.addApprovalTrustedTool(rule.toString());
    }

    private void stop() {
        for (PendingApproval approval : pending.values()) {
            approval.cancelTimeout();
            approval.future().complete(Decision.denied(AgentLinkLang.tr("agentlink.approval.server_stopping")));
        }
        pending.clear();
        scheduler.shutdownNow();
    }

    private static String normalizeTool(String toolName) {
        return toolName == null ? "" : toolName.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isWholeTrustable(String normalizedTool) {
        return !NEVER_TRUST_TOOLS.contains(normalizedTool);
    }

    /**
     * Picks a sensible parameter to scope the trust rule on. Returns null if no useful scoping
     * arg exists for this tool. The chosen glob is conservative: a single-token command becomes
     * {@code <token> *} (covering all subcommands), full text otherwise becomes itself.
     */
    private static TrustRule derivePatternRule(PendingApproval approval) {
        String tool = approval.normalizedTool();
        if (!PARAM_TRUSTABLE_TOOLS.contains(tool)) return null;
        JsonObject args = approval.args();
        if (args == null) return null;
        String key = paramKeyFor(tool);
        if (key == null) return null;
        if (!args.has(key) || args.get(key).isJsonNull()) return null;
        String value;
        try {
            value = args.get(key).getAsString();
        } catch (Exception ex) {
            return null;
        }
        if (value == null || value.isEmpty()) return null;
        String glob = patternFromValue(value);
        return TrustRule.parameterized(tool, key, glob);
    }

    private static String paramKeyFor(String tool) {
        return switch (tool) {
            case "run_console_command" -> "command";
            case "broadcast" -> "message";
            case "write_config_file" -> "path";
            default -> null;
        };
    }

    private static String patternFromValue(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return value;
        // Strip leading slash so "say hi" and "/say hi" produce the same rule.
        if (trimmed.startsWith("/")) trimmed = trimmed.substring(1);
        int sp = trimmed.indexOf(' ');
        if (sp <= 0) {
            // single-token — pin exactly. e.g. "list" → "list".
            return trimmed;
        }
        String head = trimmed.substring(0, sp);
        return head + " *";
    }

    public record Decision(boolean approved, String reason) {
        public static Decision approved(String reason) {
            return new Decision(true, reason);
        }

        public static Decision denied(String reason) {
            return new Decision(false, reason);
        }
    }

    public record CommandResult(boolean ok, String message) {}

    private static final class PendingApproval {
        private final String id;
        private final String toolName;
        private final String normalizedTool;
        private final boolean adminOnly;
        private final JsonObject args;
        private final String argsText;
        private final long createdAtMs;
        private final CompletableFuture<Decision> future;
        private volatile ScheduledFuture<?> timeout;

        private PendingApproval(String id, String toolName, String normalizedTool, boolean adminOnly,
                                JsonObject args, String argsText, long createdAtMs,
                                CompletableFuture<Decision> future) {
            this.id = id;
            this.toolName = toolName;
            this.normalizedTool = normalizedTool;
            this.adminOnly = adminOnly;
            this.args = args;
            this.argsText = argsText;
            this.createdAtMs = createdAtMs;
            this.future = future;
        }

        private String id() {
            return id;
        }

        private String toolName() {
            return toolName;
        }

        private String normalizedTool() {
            return normalizedTool;
        }

        private boolean adminOnly() {
            return adminOnly;
        }

        private JsonObject args() {
            return args;
        }

        private String argsText() {
            return argsText;
        }

        private CompletableFuture<Decision> future() {
            return future;
        }

        private void timeout(ScheduledFuture<?> timeout) {
            this.timeout = timeout;
        }

        private void cancelTimeout() {
            ScheduledFuture<?> task = timeout;
            if (task != null) task.cancel(false);
        }

        private String details() {
            return AgentLinkLang.tr("agentlink.approval.details.title") + "\n"
                    + "approval_id=" + id + "\n"
                    + "tool=" + toolName + "\n"
                    + "created_at_ms=" + createdAtMs + "\n"
                    + "arguments=" + argsText + "\n";
        }
    }
}

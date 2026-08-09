package world.agentlink.agent;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import world.agentlink.approval.AgentToolApproval;
import world.agentlink.audit.AuditLog;
import world.agentlink.api.AgentLinkApi;
import world.agentlink.config.AgentLinkConfig;
import world.agentlink.i18n.AgentLinkLang;
import world.agentlink.transport.mcp.IssuedTokens;
import world.agentlink.transport.mcp.McpHttpServer;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public final class AgentLinkCommand {

    private static final int AUDIT_TAIL_DEFAULT = 20;
    private static final int AUDIT_TAIL_MAX = 200;

    private AgentLinkCommand() {}

    public static void register(RegisterCommandsEvent event, Supplier<McpHttpServer> mcpServer) {
        event.getDispatcher().register(Commands.literal("agentlink")
                .requires(AgentLinkCommand::canUseInGameCommand)
                .then(Commands.literal("pair")
                        .executes(ctx -> refreshPairLink(ctx.getSource(), mcpServer, IssuedTokens.Tier.CONSOLE)))
                .then(Commands.literal("pair-guest")
                        .executes(ctx -> refreshPairLink(ctx.getSource(), mcpServer, IssuedTokens.Tier.GUEST)))
                .then(Commands.literal("approve")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> approve(ctx.getSource(), StringArgumentType.getString(ctx, "id")))))
                .then(Commands.literal("deny")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> deny(ctx.getSource(), StringArgumentType.getString(ctx, "id")))))
                .then(Commands.literal("trust")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> trust(ctx.getSource(), StringArgumentType.getString(ctx, "id")))))
                .then(Commands.literal("trustpattern")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> trustPattern(ctx.getSource(), StringArgumentType.getString(ctx, "id")))))
                .then(Commands.literal("trustlist")
                        .executes(ctx -> trustList(ctx.getSource())))
                .then(Commands.literal("untrust")
                        .then(Commands.argument("rule", StringArgumentType.greedyString())
                                .executes(ctx -> untrust(ctx.getSource(), StringArgumentType.getString(ctx, "rule")))))
                .then(Commands.literal("approvals")
                        .executes(ctx -> approvals(ctx.getSource())))
                .then(Commands.literal("tokens")
                        .executes(ctx -> tokensList(ctx.getSource()))
                        .then(Commands.literal("revoke")
                                .then(Commands.argument("prefix", StringArgumentType.word())
                                        .executes(ctx -> tokensRevoke(ctx.getSource(), StringArgumentType.getString(ctx, "prefix"))))))
                .then(Commands.literal("audit")
                        .then(Commands.literal("path")
                                .executes(ctx -> auditPath(ctx.getSource())))
                        .then(Commands.literal("tail")
                                .executes(ctx -> auditTail(ctx.getSource(), AUDIT_TAIL_DEFAULT))
                                .then(Commands.argument("n", IntegerArgumentType.integer(1, AUDIT_TAIL_MAX))
                                        .executes(ctx -> auditTail(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "n")))))));
    }

    private static int refreshPairLink(CommandSourceStack source, Supplier<McpHttpServer> mcpServerSupplier,
                                       IssuedTokens.Tier tier) {
        McpHttpServer server = mcpServerSupplier.get();
        if (server == null) {
            source.sendFailure(Component.literal(prefix(source, "agentlink.command.mcp_http_not_running")).withStyle(ChatFormatting.RED));
            return 0;
        }

        McpHttpServer.SetupLink setup = server.refreshSetupLink(tier);
        String tierLabel = tier.name().toLowerCase(java.util.Locale.ROOT);
        source.sendSuccess(() -> Component.literal(prefix(source, "agentlink.command.setup_link_generated_tiered",
                tierLabel,
                Instant.ofEpochMilli(setup.expiresAtMs()))).withStyle(ChatFormatting.AQUA), false);
        source.sendSuccess(() -> Component.literal(setup.link()).withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int approve(CommandSourceStack source, String id) {
        AgentToolApproval approval = AgentToolApproval.current();
        if (approval == null) return failure(source, text(source, "agentlink.command.approval_service_not_running"));
        return result(source, approval.approve(id, source.getTextName(), actorUuid(source), source.hasPermission(2), isConsole(source)));
    }

    private static int deny(CommandSourceStack source, String id) {
        AgentToolApproval approval = AgentToolApproval.current();
        if (approval == null) return failure(source, text(source, "agentlink.command.approval_service_not_running"));
        return result(source, approval.deny(id, source.getTextName(), actorUuid(source), source.hasPermission(2), isConsole(source)));
    }

    private static int trust(CommandSourceStack source, String id) {
        AgentToolApproval approval = AgentToolApproval.current();
        if (approval == null) return failure(source, text(source, "agentlink.command.approval_service_not_running"));
        return result(source, approval.trust(id, source.getTextName(), actorUuid(source), source.hasPermission(2), isConsole(source)));
    }

    private static int trustPattern(CommandSourceStack source, String id) {
        AgentToolApproval approval = AgentToolApproval.current();
        if (approval == null) return failure(source, text(source, "agentlink.command.approval_service_not_running"));
        return result(source, approval.trustPattern(id, source.getTextName(), actorUuid(source), source.hasPermission(2), isConsole(source)));
    }

    private static int trustList(CommandSourceStack source) {
        AgentToolApproval approval = AgentToolApproval.current();
        if (approval == null) return failure(source, text(source, "agentlink.command.approval_service_not_running"));
        return result(source, approval.listTrust());
    }

    private static int untrust(CommandSourceStack source, String rule) {
        AgentToolApproval approval = AgentToolApproval.current();
        if (approval == null) return failure(source, text(source, "agentlink.command.approval_service_not_running"));
        return result(source, approval.untrust(rule));
    }

    private static int approvals(CommandSourceStack source) {
        AgentToolApproval approval = AgentToolApproval.current();
        if (approval == null) return failure(source, text(source, "agentlink.command.approval_service_not_running"));
        return result(source, approval.pendingSummary());
    }

    private static int tokensList(CommandSourceStack source) {
        java.util.List<IssuedTokens.Entry> entries = IssuedTokens.current().list();
        if (entries.isEmpty()) {
            source.sendSuccess(() -> Component.literal(prefix(source, "agentlink.command.tokens.empty"))
                    .withStyle(ChatFormatting.GRAY), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal(prefix(source, "agentlink.command.tokens.header", entries.size()))
                .withStyle(ChatFormatting.AQUA), false);
        for (IssuedTokens.Entry e : entries) {
            String hashShort = e.tokenHash().substring(0, Math.min(12, e.tokenHash().length()));
            String tier = e.tier().name().toLowerCase(java.util.Locale.ROOT);
            String issued = e.issuedAtMs() <= 0 ? "?" : Instant.ofEpochMilli(e.issuedAtMs()).toString();
            String last = e.lastUsedAtMs() <= 0 ? "never" : Instant.ofEpochMilli(e.lastUsedAtMs()).toString();
            String label = e.label() == null ? "" : e.label();
            String line = "  " + hashShort + "… [" + tier + "] " + label + " issued=" + issued + " last_used=" + last;
            source.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    private static int tokensRevoke(CommandSourceStack source, String prefix) {
        int n = IssuedTokens.current().revokeByPrefix(prefix);
        source.sendSuccess(() -> Component.literal(AgentLinkCommand.prefix(source, "agentlink.command.tokens.revoked", n, prefix))
                .withStyle(n > 0 ? ChatFormatting.AQUA : ChatFormatting.RED), false);
        return n > 0 ? 1 : 0;
    }

    private static int auditPath(CommandSourceStack source) {
        if (!checkAuditPermission(source)) return 0;
        Path file = AuditLog.filePath();
        if (file == null) return failure(source, text(source, "agentlink.audit.disabled"));
        source.sendSuccess(() -> Component.literal(prefix(source, "agentlink.audit.path", file.toAbsolutePath().toString()))
                .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int auditTail(CommandSourceStack source, int n) {
        if (!checkAuditPermission(source)) return 0;
        if (AuditLog.current() == null) return failure(source, text(source, "agentlink.audit.disabled"));
        if (n > AUDIT_TAIL_MAX) return failure(source, text(source, "agentlink.audit.tail.too_many", AUDIT_TAIL_MAX));
        List<String> lines;
        try {
            lines = AuditLog.tail(n);
        } catch (IOException e) {
            return failure(source, text(source, "agentlink.audit.tail.read_failed", e.getMessage()));
        }
        if (lines.isEmpty()) {
            source.sendSuccess(() -> Component.literal(prefix(source, "agentlink.audit.tail.empty")).withStyle(ChatFormatting.GRAY), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal(prefix(source, "agentlink.audit.tail.header", lines.size()))
                .withStyle(ChatFormatting.AQUA), false);
        for (String line : lines) {
            source.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    /**
     * /agentlink audit tail/path is admin-gated when admin_uuids is configured.
     * When admin_uuids is empty, fall back to OP-level — same legacy behavior as
     * tools without admin scoping.
     */
    private static boolean checkAuditPermission(CommandSourceStack source) {
        AgentLinkConfig.Snapshot cfg = AgentLinkConfig.get();
        if (cfg == null) return true;
        List<UUID> admins = cfg.roleAdminUuids();
        if (admins == null || admins.isEmpty()) return true;
        if (isConsole(source)) return true;
        UUID actor = actorUuid(source);
        if (actor != null && admins.contains(actor)) return true;
        failure(source, text(source, "agentlink.audit.require_admin"));
        return false;
    }

    private static int result(CommandSourceStack source, AgentToolApproval.CommandResult result) {
        if (!result.ok()) return failure(source, result.message());
        source.sendSuccess(() -> Component.literal("[Agent Link] " + result.message()).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int failure(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal("[Agent Link] " + message).withStyle(ChatFormatting.RED));
        return 0;
    }

    private static java.util.UUID actorUuid(CommandSourceStack source) {
        return source.getPlayer() == null ? null : source.getPlayer().getUUID();
    }

    private static boolean isConsole(CommandSourceStack source) {
        return source.getEntity() == null;
    }

    private static boolean canUseInGameCommand(CommandSourceStack source) {
        if (isConsole(source) || source.hasPermission(2)) return true;
        ServerPlayer player = source.getPlayer();
        return player != null && AgentLinkApi.isAdmin(player.getUUID());
    }

    private static String prefix(CommandSourceStack source, String key, Object... args) {
        return "[Agent Link] " + text(source, key, args);
    }

    private static String text(CommandSourceStack source, String key, Object... args) {
        return AgentLinkLang.tr(source.getPlayer(), key, args);
    }
}

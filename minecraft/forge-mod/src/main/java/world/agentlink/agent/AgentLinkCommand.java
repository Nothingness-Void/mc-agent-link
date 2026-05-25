package world.agentlink.agent;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import world.agentlink.approval.AgentToolApproval;
import world.agentlink.i18n.AgentLinkLang;
import world.agentlink.transport.mcp.McpHttpServer;

import java.time.Instant;
import java.util.function.Supplier;

public final class AgentLinkCommand {

    private AgentLinkCommand() {}

    public static void register(RegisterCommandsEvent event, Supplier<McpHttpServer> mcpServer) {
        event.getDispatcher().register(Commands.literal("agentlink")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("pair")
                        .executes(ctx -> refreshPairLink(ctx.getSource(), mcpServer)))
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
                        .executes(ctx -> approvals(ctx.getSource()))));
    }

    private static int refreshPairLink(CommandSourceStack source, Supplier<McpHttpServer> mcpServerSupplier) {
        McpHttpServer server = mcpServerSupplier.get();
        if (server == null) {
            source.sendFailure(Component.literal(prefix(source, "agentlink.command.mcp_http_not_running")).withStyle(ChatFormatting.RED));
            return 0;
        }

        McpHttpServer.SetupLink setup = server.refreshSetupLink();
        source.sendSuccess(() -> Component.literal(prefix(source, "agentlink.command.setup_link_generated",
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

    private static String prefix(CommandSourceStack source, String key, Object... args) {
        return "[Agent Link] " + text(source, key, args);
    }

    private static String text(CommandSourceStack source, String key, Object... args) {
        return AgentLinkLang.tr(source.getPlayer(), key, args);
    }
}

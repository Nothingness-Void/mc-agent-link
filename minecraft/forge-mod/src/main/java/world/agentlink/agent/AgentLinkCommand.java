package world.agentlink.agent;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import world.agentlink.transport.mcp.McpHttpServer;

import java.time.Instant;
import java.util.function.Supplier;

public final class AgentLinkCommand {

    private AgentLinkCommand() {}

    public static void register(RegisterCommandsEvent event, Supplier<McpHttpServer> mcpServer) {
        event.getDispatcher().register(Commands.literal("agentlink")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("pair")
                        .executes(ctx -> refreshPairLink(ctx.getSource(), mcpServer))));
    }

    private static int refreshPairLink(CommandSourceStack source, Supplier<McpHttpServer> mcpServerSupplier) {
        McpHttpServer server = mcpServerSupplier.get();
        if (server == null) {
            source.sendFailure(Component.literal("[Agent Link] MCP HTTP is not running; enable mcp_enabled and restart the server.").withStyle(ChatFormatting.RED));
            return 0;
        }

        McpHttpServer.SetupLink setup = server.refreshSetupLink();
        source.sendSuccess(() -> Component.literal("[Agent Link] New setup link generated. Expires at " + Instant.ofEpochMilli(setup.expiresAtMs()) + ".").withStyle(ChatFormatting.AQUA), false);
        source.sendSuccess(() -> Component.literal(setup.link()).withStyle(ChatFormatting.GRAY), false);
        return 1;
    }
}

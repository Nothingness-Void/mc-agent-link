package world.agentlink.agent;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;

public final class AgentCommand {

    private AgentCommand() {}

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("agent")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(ctx -> submit(ctx.getSource().getPlayerOrException(),
                                StringArgumentType.getString(ctx, "message")))));
    }

    private static int submit(ServerPlayer player, String message) {
        if (message == null || message.isBlank()) {
            player.sendSystemMessage(Component.literal("Usage: /agent <request>").withStyle(ChatFormatting.YELLOW));
            return 0;
        }
        AgentRequestBuffer.Entry entry = AgentRequestBuffer.get().createFromPlayer(player, message);
        player.sendSystemMessage(Component.literal("[Agent] Request " + entry.id() + " queued. Keep your MCP agent connected and ask it to check get_agent_requests.").withStyle(ChatFormatting.AQUA));
        return 1;
    }
}

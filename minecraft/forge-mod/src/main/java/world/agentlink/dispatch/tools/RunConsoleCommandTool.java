package world.agentlink.dispatch.tools;

import com.google.gson.JsonObject;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import world.agentlink.dispatch.RequestDispatcher;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

import java.util.ArrayList;
import java.util.List;

public class RunConsoleCommandTool implements Tool {
    private final MinecraftServer mc;

    public RunConsoleCommandTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "run_console_command";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        String command = RequestDispatcher.requireString(args, "command");
        if (command.startsWith("/")) command = command.substring(1);

        List<String> captured = new ArrayList<>();
        CommandSource sink = new CommandSource() {
            @Override
            public void sendSystemMessage(Component component) {
                captured.add(component.getString());
            }

            @Override
            public boolean acceptsSuccess() { return true; }

            @Override
            public boolean acceptsFailure() { return true; }

            @Override
            public boolean shouldInformAdmins() { return false; }
        };

        CommandSourceStack stack = new CommandSourceStack(
                sink,
                Vec3.ZERO, Vec2.ZERO,
                mc.overworld(),
                4, // op level
                "agent-link",
                Component.literal("agent-link"),
                mc,
                null
        );

        int returnValue;
        try {
            returnValue = mc.getCommands().getDispatcher().execute(command, stack);
        } catch (CommandSyntaxException e) {
            throw new ToolException("INVALID_ARGS", e.getMessage());
        }

        JsonObject r = new JsonObject();
        r.addProperty("return_value", returnValue);
        r.addProperty("output", String.join("\n", captured));
        return r;
    }
}

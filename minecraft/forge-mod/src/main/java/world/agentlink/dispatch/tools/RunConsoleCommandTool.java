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
            recordEvent(command, -1, false);
            throw new ToolException("INVALID_ARGS", e.getMessage());
        }

        recordEvent(command, returnValue, true);

        JsonObject r = new JsonObject();
        r.addProperty("return_value", returnValue);
        r.addProperty("output", String.join("\n", captured));
        return r;
    }

    /**
     * Publish a {@code command} event for this invocation.
     *
     * <p>We execute through the Brigadier dispatcher directly (so we can capture output into our own
     * sink), which bypasses Forge's {@code CommandEvent} — the hook {@link
     * world.agentlink.events.ForgeEventBridge} listens on for player- and console-issued commands.
     * Without this, {@code get_recent_events} would show commands run by humans but silently omit the
     * agent's own, so an agent reviewing "what happened" could not see its own footprints. The
     * {@code source} field distinguishes them.
     */
    private void recordEvent(String command, int returnValue, boolean ok) {
        try {
            JsonObject d = new JsonObject();
            d.addProperty("command", command);
            d.addProperty("source", "agent-link");
            d.addProperty("player", "@agent");
            d.addProperty("via", "run_console_command");
            d.addProperty("ok", ok);
            if (ok) d.addProperty("return_value", returnValue);
            d.addProperty("cancelled", false);
            world.agentlink.events.ForgeEventBridge.post(
                    world.agentlink.events.EventTopics.COMMAND, d);
        } catch (Throwable ignored) {
            // Telemetry must never fail the command that already ran.
        }
    }
}

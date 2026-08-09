package world.agentlink.dispatch.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
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

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Brigadier-backed dry-run for the server's command tree.
 *
 * <p>{@code mode = "suggest"}: returns the same suggestions you would see by pressing Tab.
 * <p>{@code mode = "parse"}: parses {@code command} without executing and reports any
 * {@link CommandSyntaxException}, including the cursor position.
 *
 * <p>The synthetic {@link CommandSourceStack} runs at op level 4 so suggestions cover the
 * full command surface; nothing is actually executed and no permissions are exercised.
 */
public class CommandTool implements Tool {

    private final MinecraftServer mc;

    public CommandTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "command";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        String mode = (args.has("mode") && !args.get("mode").isJsonNull()
                ? args.get("mode").getAsString() : "parse").trim().toLowerCase(Locale.ROOT);
        String command = RequestDispatcher.requireString(args, "command");
        String stripped = command.startsWith("/") ? command.substring(1) : command;

        CommandDispatcher<CommandSourceStack> dispatcher = mc.getCommands().getDispatcher();
        CommandSourceStack source = syntheticSource();

        return switch (mode) {
            case "suggest" -> handleSuggest(args, dispatcher, source, stripped);
            case "parse" -> handleParse(dispatcher, source, stripped);
            default -> throw new ToolException("INVALID_ARGS",
                    "mode must be \"suggest\" or \"parse\" (got: " + mode + ")");
        };
    }

    private JsonObject handleSuggest(JsonObject args, CommandDispatcher<CommandSourceStack> dispatcher,
                                     CommandSourceStack source, String command) throws ToolException {
        int cursor = args.has("cursor") && !args.get("cursor").isJsonNull()
                ? args.get("cursor").getAsInt()
                : command.length();
        if (cursor < 0) cursor = 0;
        if (cursor > command.length()) cursor = command.length();

        StringReader reader = new StringReader(command);
        ParseResults<CommandSourceStack> parse = dispatcher.parse(reader, source);
        CompletableFuture<Suggestions> fut = dispatcher.getCompletionSuggestions(parse, cursor);
        Suggestions suggestions;
        try {
            suggestions = fut.get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ToolException("INTERNAL_ERROR", "interrupted while computing suggestions");
        } catch (ExecutionException ex) {
            throw new ToolException("INTERNAL_ERROR",
                    "suggestion error: " + (ex.getCause() == null ? ex.getMessage() : ex.getCause().getMessage()));
        }

        JsonArray list = new JsonArray();
        for (Suggestion s : suggestions.getList()) {
            JsonObject o = new JsonObject();
            o.addProperty("text", s.getText());
            o.addProperty("range_start", s.getRange().getStart());
            o.addProperty("range_end", s.getRange().getEnd());
            if (s.getTooltip() != null) {
                o.addProperty("tooltip", s.getTooltip().getString());
            }
            list.add(o);
        }

        JsonObject r = new JsonObject();
        r.addProperty("mode", "suggest");
        r.addProperty("command", command);
        r.addProperty("cursor", cursor);
        r.addProperty("count", list.size());
        r.add("suggestions", list);
        return r;
    }

    private JsonObject handleParse(CommandDispatcher<CommandSourceStack> dispatcher,
                                   CommandSourceStack source, String command) {
        StringReader reader = new StringReader(command);
        ParseResults<CommandSourceStack> parse = dispatcher.parse(reader, source);
        JsonObject r = new JsonObject();
        r.addProperty("mode", "parse");
        r.addProperty("command", command);
        boolean hasExceptions = !parse.getExceptions().isEmpty();
        boolean unused = parse.getReader().canRead();
        r.addProperty("ok", !hasExceptions && !unused);
        r.addProperty("cursor", parse.getReader().getCursor());

        if (hasExceptions) {
            JsonArray errs = new JsonArray();
            parse.getExceptions().forEach((node, ex) -> {
                JsonObject e = new JsonObject();
                e.addProperty("node", String.valueOf(node.getName()));
                e.addProperty("message", ex.getMessage());
                if (ex instanceof CommandSyntaxException) {
                    CommandSyntaxException cse = (CommandSyntaxException) ex;
                    e.addProperty("cursor", cse.getCursor());
                    if (cse.getInput() != null) e.addProperty("input", cse.getInput());
                }
                errs.add(e);
            });
            r.add("errors", errs);
        }
        if (unused) {
            r.addProperty("unused_input", command.substring(parse.getReader().getCursor()));
        }
        return r;
    }

    private CommandSourceStack syntheticSource() {
        // Silent CommandSource — never sends feedback or success messages anywhere.
        CommandSource silent = new CommandSource() {
            @Override public void sendSystemMessage(Component component) {}
            @Override public boolean acceptsSuccess() { return false; }
            @Override public boolean acceptsFailure() { return false; }
            @Override public boolean shouldInformAdmins() { return false; }
        };
        return new CommandSourceStack(
                silent,
                Vec3.atCenterOf(mc.overworld().getSharedSpawnPos()),
                Vec2.ZERO,
                mc.overworld(),
                4,
                "agent-link/command",
                Component.literal("agent-link/command"),
                mc,
                null
        );
    }
}

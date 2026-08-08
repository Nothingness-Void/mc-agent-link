package world.agentlink.dispatch.tools;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import world.agentlink.api.AgentApiException;
import world.agentlink.api.AgentLinkApi;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolArgs;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

/** Structured scoreboard mutation surface. Read operations remain in get_scoreboard. */
public final class ManageScoreboardTool implements Tool {

    private final MinecraftServer mc;

    public ManageScoreboardTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "manage_scoreboard";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        String action = ToolArgs.requireString(args, "action").trim().toLowerCase();
        try {
            return switch (action) {
                case "create_objective" -> createObjective(args);
                case "remove_objective" -> removeObjective(args);
                case "set_score" -> score(args, "set");
                case "add_score" -> score(args, "add");
                case "reset_score" -> resetScore(args);
                case "create_team" -> createTeam(args);
                case "remove_team" -> removeTeam(args);
                case "add_team_member" -> teamMember(args, true);
                case "remove_team_member" -> teamMember(args, false);
                case "configure_team" -> configureTeam(args);
                case "set_display" -> setDisplay(args);
                default -> throw new ToolException("INVALID_ARGS", "Unknown scoreboard action: " + action);
            };
        } catch (AgentApiException ex) {
            throw new ToolException(ex.code(), ex.getMessage());
        }
    }

    private JsonObject createObjective(JsonObject args) throws AgentApiException, ToolException {
        String name = ToolArgs.requireString(args, "name");
        var objective = AgentLinkApi.scoreboards().createObjective(mc, name,
                ToolArgs.optString(args, "criteria", "dummy"),
                ToolArgs.optString(args, "display_name", name),
                ToolArgs.optString(args, "render_type", "integer"));
        JsonObject result = base("create_objective");
        result.addProperty("name", objective.getName());
        result.addProperty("criteria", objective.getCriteria().getName());
        return result;
    }

    private JsonObject removeObjective(JsonObject args) throws AgentApiException, ToolException {
        String name = ToolArgs.requireString(args, "name");
        AgentLinkApi.scoreboards().removeObjective(mc, name);
        JsonObject result = base("remove_objective");
        result.addProperty("name", name);
        return result;
    }

    private JsonObject score(JsonObject args, String mode) throws AgentApiException, ToolException {
        String holder = ToolArgs.requireString(args, "holder");
        String objective = ToolArgs.requireString(args, "objective");
        int value = ToolArgs.requireInt(args, "value");
        int resultValue = "set".equals(mode)
                ? AgentLinkApi.scoreboards().setScore(mc, holder, objective, value)
                : AgentLinkApi.scoreboards().addScore(mc, holder, objective, value);
        JsonObject result = base(mode + "_score");
        result.addProperty("holder", holder);
        result.addProperty("objective", objective);
        result.addProperty("value", resultValue);
        return result;
    }

    private JsonObject resetScore(JsonObject args) throws AgentApiException, ToolException {
        String holder = ToolArgs.requireString(args, "holder");
        String objective = ToolArgs.requireString(args, "objective");
        JsonObject result = base("reset_score");
        result.addProperty("holder", holder);
        result.addProperty("objective", objective);
        result.addProperty("changed", AgentLinkApi.scoreboards().resetScore(mc, holder, objective));
        return result;
    }

    private JsonObject createTeam(JsonObject args) throws AgentApiException, ToolException {
        String name = ToolArgs.requireString(args, "name");
        AgentLinkApi.scoreboards().createTeam(mc, name, ToolArgs.optString(args, "display_name", name));
        JsonObject result = base("create_team");
        result.addProperty("name", name);
        return result;
    }

    private JsonObject removeTeam(JsonObject args) throws AgentApiException, ToolException {
        String name = ToolArgs.requireString(args, "name");
        AgentLinkApi.scoreboards().removeTeam(mc, name);
        JsonObject result = base("remove_team");
        result.addProperty("name", name);
        return result;
    }

    private JsonObject teamMember(JsonObject args, boolean add) throws AgentApiException, ToolException {
        String team = ToolArgs.requireString(args, "team");
        String member = ToolArgs.requireString(args, "member");
        boolean changed = add
                ? AgentLinkApi.scoreboards().addTeamMember(mc, team, member)
                : AgentLinkApi.scoreboards().removeTeamMember(mc, team, member);
        JsonObject result = base(add ? "add_team_member" : "remove_team_member");
        result.addProperty("team", team);
        result.addProperty("member", member);
        result.addProperty("changed", changed);
        return result;
    }

    private JsonObject configureTeam(JsonObject args) throws AgentApiException, ToolException {
        String name = ToolArgs.requireString(args, "name");
        AgentLinkApi.scoreboards().configureTeam(mc, name,
                optionalString(args, "display_name"), optionalString(args, "color"),
                optionalBoolean(args, "allow_friendly_fire"), optionalBoolean(args, "see_friendly_invisibles"),
                optionalString(args, "name_tag_visibility"), optionalString(args, "death_message_visibility"),
                optionalString(args, "collision_rule"));
        JsonObject result = base("configure_team");
        result.addProperty("name", name);
        return result;
    }

    private JsonObject setDisplay(JsonObject args) throws AgentApiException, ToolException {
        String slot = ToolArgs.requireString(args, "slot");
        AgentLinkApi.scoreboards().setDisplay(mc, slot, optionalString(args, "objective"));
        JsonObject result = base("set_display");
        result.addProperty("slot", slot);
        result.addProperty("objective", optionalString(args, "objective") == null ? "" : optionalString(args, "objective"));
        return result;
    }

    private static String optionalString(JsonObject args, String key) {
        return args.has(key) && !args.get(key).isJsonNull() ? ToolArgs.optString(args, key, null) : null;
    }

    private static Boolean optionalBoolean(JsonObject args, String key) {
        return args.has(key) && !args.get(key).isJsonNull() ? ToolArgs.optBool(args, key, false) : null;
    }

    private static JsonObject base(String action) {
        JsonObject result = new JsonObject();
        result.addProperty("action", action);
        return result;
    }
}

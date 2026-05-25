package world.agentlink.dispatch.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Score;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.transport.ClientSession;

/**
 * Read-only access to scoreboard objectives + teams. Three modes:
 *
 * <ul>
 *   <li>{@code mode = "objectives"} (default): list objectives with their criteria and a few top scores.</li>
 *   <li>{@code mode = "objective"}: full per-player score table for one objective ({@code name} required).</li>
 *   <li>{@code mode = "teams"}: list teams and members.</li>
 * </ul>
 */
public class GetScoreboardTool implements Tool {

    private final MinecraftServer mc;

    public GetScoreboardTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "get_scoreboard";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        String mode = (args.has("mode") && !args.get("mode").isJsonNull()
                ? args.get("mode").getAsString() : "objectives").trim().toLowerCase();
        ServerScoreboard sb = mc.getScoreboard();
        return switch (mode) {
            case "objectives" -> listObjectives(sb);
            case "objective" -> readObjective(sb, args);
            case "teams" -> listTeams(sb);
            default -> throw new ToolException("INVALID_ARGS",
                    "mode must be \"objectives\" | \"objective\" | \"teams\"");
        };
    }

    private JsonObject listObjectives(ServerScoreboard sb) {
        JsonArray arr = new JsonArray();
        for (Objective o : sb.getObjectives()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("name", o.getName());
            obj.addProperty("display_name", o.getDisplayName().getString());
            ObjectiveCriteria criteria = o.getCriteria();
            obj.addProperty("criteria", criteria == null ? "" : criteria.getName());
            obj.addProperty("render_type", o.getRenderType().getId());
            int total = sb.getPlayerScores(o).size();
            obj.addProperty("score_count", total);
            arr.add(obj);
        }
        JsonObject r = new JsonObject();
        r.addProperty("count", arr.size());
        r.add("objectives", arr);
        return r;
    }

    private JsonObject readObjective(ServerScoreboard sb, JsonObject args) throws ToolException {
        String objectiveName = args.has("name") && !args.get("name").isJsonNull()
                ? args.get("name").getAsString()
                : null;
        if (objectiveName == null) {
            throw new ToolException("INVALID_ARGS", "name is required for mode=objective");
        }
        Objective obj = sb.getObjective(objectiveName);
        if (obj == null) {
            throw new ToolException("INVALID_ARGS", "Objective not found: " + objectiveName);
        }
        JsonArray scores = new JsonArray();
        for (Score s : sb.getPlayerScores(obj)) {
            JsonObject row = new JsonObject();
            row.addProperty("holder", s.getOwner());
            row.addProperty("score", s.getScore());
            row.addProperty("locked", s.isLocked());
            scores.add(row);
        }
        JsonObject r = new JsonObject();
        r.addProperty("name", obj.getName());
        r.addProperty("display_name", obj.getDisplayName().getString());
        r.addProperty("count", scores.size());
        r.add("scores", scores);
        return r;
    }

    private JsonObject listTeams(ServerScoreboard sb) {
        JsonArray arr = new JsonArray();
        for (PlayerTeam team : sb.getPlayerTeams()) {
            JsonObject t = new JsonObject();
            t.addProperty("name", team.getName());
            t.addProperty("display_name", team.getDisplayName().getString());
            ChatFormatting color = team.getColor();
            t.addProperty("color", color == null ? "" : color.getName());
            t.addProperty("collision_rule", team.getCollisionRule().name);
            t.addProperty("see_friendly_invisibles", team.canSeeFriendlyInvisibles());
            t.addProperty("allow_friendly_fire", team.isAllowFriendlyFire());
            t.addProperty("nametag_visibility", team.getNameTagVisibility().name);
            t.addProperty("death_message_visibility", team.getDeathMessageVisibility().name);
            JsonArray members = new JsonArray();
            for (String name : team.getPlayers()) members.add(name);
            t.add("members", members);
            arr.add(t);
        }
        JsonObject r = new JsonObject();
        r.addProperty("count", arr.size());
        r.add("teams", arr);
        return r;
    }
}

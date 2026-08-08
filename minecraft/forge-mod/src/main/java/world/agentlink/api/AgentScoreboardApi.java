package world.agentlink.api;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Score;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.util.Locale;

/** Typed scoreboard objectives, scores, display slots, and teams. */
public final class AgentScoreboardApi {

    AgentScoreboardApi() {}

    public ServerScoreboard scoreboard(MinecraftServer server) throws AgentApiException {
        if (server == null) throw new AgentApiException("SERVER_UNAVAILABLE", "server is not running");
        return server.getScoreboard();
    }

    public Objective createObjective(MinecraftServer server, String name, String criteria,
                                     String displayName, String renderType) throws AgentApiException {
        ServerScoreboard scoreboard = scoreboard(server);
        requireName(name, "objective name");
        if (scoreboard.getObjective(name) != null) {
            throw new AgentApiException("ALREADY_EXISTS", "Objective already exists: " + name);
        }
        ObjectiveCriteria criterion = ObjectiveCriteria.byName(
                criteria == null || criteria.isBlank() ? "dummy" : criteria.trim()).orElseThrow(() ->
                new AgentApiException("INVALID_ARGS", "Unknown objective criteria: " + criteria));
        ObjectiveCriteria.RenderType render = renderType(renderType);
        return scoreboard.addObjective(name, criterion,
                Component.literal(displayName == null || displayName.isBlank() ? name : displayName), render);
    }

    public void removeObjective(MinecraftServer server, String name) throws AgentApiException {
        ServerScoreboard scoreboard = scoreboard(server);
        Objective objective = objective(scoreboard, name);
        scoreboard.removeObjective(objective);
    }

    public int setScore(MinecraftServer server, String holder, String objectiveName, int value)
            throws AgentApiException {
        Score score = score(server, holder, objectiveName);
        score.setScore(value);
        return score.getScore();
    }

    public int addScore(MinecraftServer server, String holder, String objectiveName, int delta)
            throws AgentApiException {
        Score score = score(server, holder, objectiveName);
        score.add(delta);
        return score.getScore();
    }

    public boolean resetScore(MinecraftServer server, String holder, String objectiveName)
            throws AgentApiException {
        ServerScoreboard scoreboard = scoreboard(server);
        Objective objective = objective(scoreboard, objectiveName);
        boolean existed = scoreboard.hasPlayerScore(holder, objective);
        scoreboard.resetPlayerScore(holder, objective);
        return existed;
    }

    public PlayerTeam createTeam(MinecraftServer server, String name, String displayName)
            throws AgentApiException {
        ServerScoreboard scoreboard = scoreboard(server);
        requireName(name, "team name");
        if (scoreboard.getPlayerTeam(name) != null) {
            throw new AgentApiException("ALREADY_EXISTS", "Team already exists: " + name);
        }
        PlayerTeam team = scoreboard.addPlayerTeam(name);
        if (displayName != null && !displayName.isBlank()) team.setDisplayName(Component.literal(displayName));
        return team;
    }

    public void removeTeam(MinecraftServer server, String name) throws AgentApiException {
        ServerScoreboard scoreboard = scoreboard(server);
        scoreboard.removePlayerTeam(team(scoreboard, name));
    }

    public boolean addTeamMember(MinecraftServer server, String teamName, String member)
            throws AgentApiException {
        requireName(member, "member");
        ServerScoreboard scoreboard = scoreboard(server);
        return scoreboard.addPlayerToTeam(member, team(scoreboard, teamName));
    }

    public boolean removeTeamMember(MinecraftServer server, String teamName, String member)
            throws AgentApiException {
        ServerScoreboard scoreboard = scoreboard(server);
        PlayerTeam team = team(scoreboard, teamName);
        if (!team.getPlayers().contains(member)) return false;
        scoreboard.removePlayerFromTeam(member, team);
        return true;
    }

    public void configureTeam(MinecraftServer server, String name, String displayName, String color,
                              Boolean allowFriendlyFire, Boolean seeFriendlyInvisibles,
                              String nameTagVisibility, String deathMessageVisibility,
                              String collisionRule) throws AgentApiException {
        PlayerTeam team = team(scoreboard(server), name);
        if (displayName != null) team.setDisplayName(Component.literal(displayName));
        if (color != null) {
            ChatFormatting formatting = ChatFormatting.getByName(color.trim().toLowerCase(Locale.ROOT));
            if (formatting == null || !formatting.isColor()) {
                throw new AgentApiException("INVALID_ARGS", "Unknown team color: " + color);
            }
            team.setColor(formatting);
        }
        if (allowFriendlyFire != null) team.setAllowFriendlyFire(allowFriendlyFire);
        if (seeFriendlyInvisibles != null) team.setSeeFriendlyInvisibles(seeFriendlyInvisibles);
        if (nameTagVisibility != null) team.setNameTagVisibility(visibility(nameTagVisibility));
        if (deathMessageVisibility != null) team.setDeathMessageVisibility(visibility(deathMessageVisibility));
        if (collisionRule != null) team.setCollisionRule(collision(collisionRule));
    }

    public void setDisplay(MinecraftServer server, String slotName, String objectiveName)
            throws AgentApiException {
        ServerScoreboard scoreboard = scoreboard(server);
        int slot = Scoreboard.getDisplaySlotByName(slotName == null ? "" : slotName.trim().toLowerCase(Locale.ROOT));
        if (slot < 0) throw new AgentApiException("INVALID_ARGS", "Unknown display slot: " + slotName);
        Objective objective = objectiveName == null || objectiveName.isBlank()
                ? null : objective(scoreboard, objectiveName);
        scoreboard.setDisplayObjective(slot, objective);
    }

    private Score score(MinecraftServer server, String holder, String objectiveName) throws AgentApiException {
        requireName(holder, "holder");
        ServerScoreboard scoreboard = scoreboard(server);
        return scoreboard.getOrCreatePlayerScore(holder, objective(scoreboard, objectiveName));
    }

    private static Objective objective(ServerScoreboard scoreboard, String name) throws AgentApiException {
        requireName(name, "objective name");
        Objective objective = scoreboard.getObjective(name);
        if (objective == null) throw new AgentApiException("NOT_FOUND", "Objective not found: " + name);
        return objective;
    }

    private static PlayerTeam team(ServerScoreboard scoreboard, String name) throws AgentApiException {
        requireName(name, "team name");
        PlayerTeam team = scoreboard.getPlayerTeam(name);
        if (team == null) throw new AgentApiException("NOT_FOUND", "Team not found: " + name);
        return team;
    }

    private static ObjectiveCriteria.RenderType renderType(String raw) throws AgentApiException {
        String value = raw == null || raw.isBlank() ? "integer" : raw.trim().toUpperCase(Locale.ROOT);
        try {
            return ObjectiveCriteria.RenderType.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new AgentApiException("INVALID_ARGS", "render_type must be integer or hearts");
        }
    }

    private static Team.Visibility visibility(String raw) throws AgentApiException {
        try {
            return Team.Visibility.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new AgentApiException("INVALID_ARGS",
                    "visibility must be always, never, hide_for_other_teams, or hide_for_own_team");
        }
    }

    private static Team.CollisionRule collision(String raw) throws AgentApiException {
        try {
            return Team.CollisionRule.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new AgentApiException("INVALID_ARGS",
                    "collision_rule must be always, never, push_other_teams, or push_own_team");
        }
    }

    private static void requireName(String value, String label) throws AgentApiException {
        if (value == null || value.isBlank()) throw new AgentApiException("INVALID_ARGS", label + " is required");
    }
}

package world.agentlink.api;

import net.minecraft.server.MinecraftServer;

import java.util.Collection;
import java.util.List;

/** Explicit server lifecycle and runtime tuning operations. */
public final class AgentServerControlApi {

    public record SaveResult(boolean saved, boolean flushed, long durationMs) {}
    public record PackState(List<String> available, List<String> selected) {}

    AgentServerControlApi() {}

    public SaveResult save(MinecraftServer server, boolean flush) throws AgentApiException {
        requireServer(server);
        long started = System.currentTimeMillis();
        boolean saved = server.saveEverything(true, flush, true);
        return new SaveResult(saved, flush, System.currentTimeMillis() - started);
    }

    /** Start a vanilla resource reload. The server completes the reload asynchronously. */
    public void reloadResources(MinecraftServer server) throws AgentApiException {
        requireServer(server);
        server.reloadResources(server.getPackRepository().getSelectedIds());
    }

    public PackState dataPacks(MinecraftServer server, boolean refresh) throws AgentApiException {
        requireServer(server);
        var repository = server.getPackRepository();
        if (refresh) repository.reload();
        return new PackState(List.copyOf(repository.getAvailableIds()), List.copyOf(repository.getSelectedIds()));
    }

    public PackState selectDataPacks(MinecraftServer server, Collection<String> selected)
            throws AgentApiException {
        requireServer(server);
        if (selected == null || selected.isEmpty()) {
            throw new AgentApiException("INVALID_ARGS", "at least one data pack must remain selected");
        }
        var repository = server.getPackRepository();
        repository.reload();
        for (String id : selected) {
            if (id == null || id.isBlank() || !repository.isAvailable(id)) {
                throw new AgentApiException("NOT_FOUND", "Data pack not available: " + id);
            }
        }
        List<String> ids = List.copyOf(selected);
        repository.setSelected(ids);
        server.reloadResources(ids);
        return new PackState(List.copyOf(repository.getAvailableIds()), ids);
    }

    public void stop(MinecraftServer server, boolean confirmed) throws AgentApiException {
        requireServer(server);
        if (!confirmed) throw new AgentApiException("CONFIRMATION_REQUIRED",
                "Stopping the server is irreversible for this call; pass confirmed=true");
        server.halt(false);
    }

    public void setViewDistance(MinecraftServer server, int chunks) throws AgentApiException {
        requireServer(server);
        if (chunks < 2 || chunks > 32) throw new AgentApiException("INVALID_ARGS", "view distance must be 2..32");
        server.getPlayerList().setViewDistance(chunks);
    }

    public void setSimulationDistance(MinecraftServer server, int chunks) throws AgentApiException {
        requireServer(server);
        if (chunks < 2 || chunks > 32) throw new AgentApiException("INVALID_ARGS", "simulation distance must be 2..32");
        server.getPlayerList().setSimulationDistance(chunks);
    }

    public void setAllowCheatsForAllPlayers(MinecraftServer server, boolean enabled)
            throws AgentApiException {
        requireServer(server);
        server.getPlayerList().setAllowCommandsForAllPlayers(enabled);
    }

    private static void requireServer(MinecraftServer server) throws AgentApiException {
        if (server == null) throw new AgentApiException("SERVER_UNAVAILABLE", "server is not running");
    }
}

package world.agentlink.diagnostics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import world.agentlink.AgentLinkMod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Small crash-safe lifecycle marker. It deliberately records no token, world data, or operation
 * payload; its purpose is to tell an offline analyzer whether the previous JVM stopped cleanly.
 */
public final class IncidentLedger {
    public static final String FILE_NAME = "incident-ledger.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static volatile IncidentLedger CURRENT;

    private final Path file;
    private final String bootId = UUID.randomUUID().toString();
    private final long processId = ProcessHandle.current().pid();
    private final String processStartedAt = Instant.ofEpochMilli(
            java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime()).toString();
    private final String serverStartedAt = Instant.now().toString();
    private final boolean previousUncleanShutdown;
    private final ScheduledExecutorService heartbeat;
    private boolean cleanShutdown;
    private String shutdownAt;

    private IncidentLedger(Path file, boolean previousUncleanShutdown) {
        this.file = file;
        this.previousUncleanShutdown = previousUncleanShutdown;
        this.heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agent-link-incident-ledger");
            t.setDaemon(true);
            return t;
        });
    }

    public static synchronized IncidentLedger start(MinecraftServer server) {
        stopCurrent(false);
        Path file = server.getServerDirectory().toAbsolutePath().normalize()
                .resolve("diagnostics").resolve(FILE_NAME);
        boolean previousUnclean = readPreviousUnclean(file);
        IncidentLedger next = new IncidentLedger(file, previousUnclean);
        CURRENT = next;
        next.write(false);
        next.heartbeat.scheduleAtFixedRate(() -> next.write(false), 5, 5, TimeUnit.SECONDS);
        return next;
    }

    public static IncidentLedger current() {
        return CURRENT;
    }

    public static synchronized void stopCurrent(boolean clean) {
        IncidentLedger current = CURRENT;
        CURRENT = null;
        if (current != null) current.stop(clean);
    }

    public Path file() {
        return file;
    }

    public synchronized void stop(boolean clean) {
        if (cleanShutdown) return;
        cleanShutdown = clean;
        shutdownAt = Instant.now().toString();
        writeLocked();
        heartbeat.shutdownNow();
    }

    private void write(boolean ignored) {
        synchronized (this) {
            if (heartbeat.isShutdown() && !cleanShutdown) return;
            writeLocked();
        }
    }

    private void writeLocked() {
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("schema_version", 1);
            root.addProperty("boot_id", bootId);
            root.addProperty("process_id", processId);
            root.addProperty("process_started_at", processStartedAt);
            root.addProperty("server_started_at", serverStartedAt);
            root.addProperty("heartbeat_at", Instant.now().toString());
            root.addProperty("clean_shutdown", cleanShutdown);
            root.addProperty("previous_unclean_shutdown", previousUncleanShutdown);
            if (shutdownAt != null) root.addProperty("shutdown_at", shutdownAt);
            Path temp = file.resolveSibling(FILE_NAME + ".tmp");
            Files.writeString(temp, GSON.toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            AgentLinkMod.LOG.warn("agent-link: incident ledger write failed: {}", e.getMessage());
        }
    }

    private static boolean readPreviousUnclean(Path file) {
        if (!Files.isRegularFile(file)) return false;
        try {
            JsonObject root = com.google.gson.JsonParser.parseString(
                    Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            return root.has("clean_shutdown") && !root.get("clean_shutdown").getAsBoolean();
        } catch (Exception e) {
            AgentLinkMod.LOG.warn("agent-link: previous incident ledger is unreadable: {}", e.getMessage());
            return true;
        }
    }
}

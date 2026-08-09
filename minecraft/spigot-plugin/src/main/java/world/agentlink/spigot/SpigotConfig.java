package world.agentlink.spigot;

import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Converts Bukkit's YAML configuration into an immutable request-time snapshot. */
public final class SpigotConfig {
    private SpigotConfig() {}

    public record Snapshot(
            int listenPort,
            int mcpPort,
            boolean mcpEnabled,
            boolean allowRemote,
            String publicHost,
            List<String> allowedOrigins,
            String masterToken,
            boolean approvalEnabled,
            int approvalTimeoutSeconds,
            List<String> autoAllowTools,
            List<String> trustedTools,
            List<String> adminOnlyTools,
            List<String> writeAllow,
            List<String> writeDeny,
            String version
    ) {
        public Snapshot {
            allowedOrigins = List.copyOf(allowedOrigins == null ? List.of("null") : allowedOrigins);
            autoAllowTools = List.copyOf(autoAllowTools == null ? List.of("ping", "whoami") : autoAllowTools);
            trustedTools = List.copyOf(trustedTools == null ? List.of() : trustedTools);
            adminOnlyTools = List.copyOf(adminOnlyTools == null ? List.of() : adminOnlyTools);
            writeAllow = List.copyOf(writeAllow == null ? List.of("config/**") : writeAllow);
            writeDeny = List.copyOf(writeDeny == null ? List.of() : writeDeny);
        }
    }

    public static Snapshot load(JavaPlugin plugin) {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        String token = plugin.getConfig().getString("token", "");
        if (token == null || token.isBlank()) {
            token = TokenStore.generate();
            plugin.getConfig().set("token", token);
            plugin.saveConfig();
            plugin.getLogger().info("Generated a new Agent Link master token.");
        }

        return new Snapshot(
                boundedPort(plugin.getConfig().getInt("listen-port", 25580)),
                boundedPort(plugin.getConfig().getInt("mcp.port", 25581)),
                plugin.getConfig().getBoolean("mcp.enabled", true),
                plugin.getConfig().getBoolean("mcp.allow-remote", false),
                host(plugin.getConfig().getString("mcp.public-host", "127.0.0.1")),
                strings(plugin.getConfig().getStringList("mcp.allowed-origins"), List.of("null", "http://127.0.0.1")),
                token,
                plugin.getConfig().getBoolean("approval.enabled", true),
                Math.max(5, Math.min(300, plugin.getConfig().getInt("approval.timeout-seconds", 30))),
                strings(plugin.getConfig().getStringList("approval.auto-allow-tools"), List.of("ping", "whoami")),
                plugin.getConfig().getStringList("approval.trusted-tools"),
                plugin.getConfig().getStringList("approval.admin-only-tools"),
                strings(plugin.getConfig().getStringList("write-allow"), List.of("config/**")),
                plugin.getConfig().getStringList("write-deny"),
                plugin.getDescription().getVersion()
        );
    }

    public static Path serverRoot(JavaPlugin plugin) {
        return plugin.getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
    }

    private static int boundedPort(int port) {
        return port >= 1 && port <= 65535 ? port : 25581;
    }

    private static String host(String value) {
        if (value == null || value.isBlank()) return "127.0.0.1";
        return value.trim().replace("http://", "").replace("https://", "").replaceAll("/+$", "");
    }

    private static List<String> strings(List<String> values, List<String> fallback) {
        if (values == null || values.isEmpty()) return new ArrayList<>(fallback);
        return values;
    }
}

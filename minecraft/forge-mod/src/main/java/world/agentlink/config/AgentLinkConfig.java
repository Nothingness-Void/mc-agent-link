package world.agentlink.config;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import net.minecraftforge.fml.loading.FMLPaths;
import world.agentlink.AgentLinkMod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;

/**
 * Loaded once on mod init. Hand-rolled instead of Forge's ForgeConfigSpec because
 * the spec hooks come too late for the WebSocket port we want available the moment
 * the integrated/dedicated server is up.
 */
public final class AgentLinkConfig {

    public record Snapshot(
            int listenPort,
            boolean allowRemote,
            String token,
            List<String> writeAllow,
            List<String> writeDeny,
            String version
    ) {}

    private static final String FILE_NAME = "agent-link.toml";
    private static final List<String> DEFAULT_WRITE_ALLOW = List.of("config/**");
    private static final List<String> DEFAULT_WRITE_DENY = List.of();
    private static Snapshot CURRENT;

    private AgentLinkConfig() {}

    public static Snapshot get() {
        return CURRENT;
    }

    public static void load() {
        Path path = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
        boolean fresh = !Files.exists(path);
        try (CommentedFileConfig cfg = CommentedFileConfig.builder(path)
                .preserveInsertionOrder()
                .build()) {
            cfg.load();

            int port = cfg.getIntOrElse("listen_port", 25580);
            boolean allowRemote = cfg.getOrElse("allow_remote", false);
            String token = cfg.getOrElse("token", "");

            List<String> writeAllow = readStringList(cfg, "write_allow", DEFAULT_WRITE_ALLOW);
            List<String> writeDeny = readStringList(cfg, "write_deny", DEFAULT_WRITE_DENY);

            if (token.isBlank()) {
                token = generateToken();
                cfg.set("token", token);
                cfg.setComment("token", " Shared secret. Anyone with this token can run console commands. Keep it safe.");
            }
            cfg.set("listen_port", port);
            cfg.setComment("listen_port", " WebSocket port. Default 25580.");
            cfg.set("allow_remote", allowRemote);
            cfg.setComment("allow_remote", " false = bind to 127.0.0.1 only. true = bind to 0.0.0.0 (LAN/internet). Use a firewall if true.");

            cfg.set("write_allow", writeAllow);
            cfg.setComment("write_allow",
                    "\n write_config_file glob allowlist. A path is writable only if it matches at least one of these.\n" +
                    " Patterns are evaluated relative to the server root.\n" +
                    " Glob syntax: ** (any segments), * (any chars within a segment), ? (one char).\n" +
                    " Default: [\"config/**\"] — only config/ is writable.");
            cfg.set("write_deny", writeDeny);
            cfg.setComment("write_deny",
                    "\n write_config_file glob denylist. Evaluated BEFORE write_allow — anything matched here is rejected\n" +
                    " regardless of write_allow. Use to carve out exceptions, e.g. [\"config/security/**\"].\n" +
                    " Default: [] (nothing extra denied).");

            cfg.save();
            CURRENT = new Snapshot(port, allowRemote, token, writeAllow, writeDeny, "0.1.1-alpha");

            if (fresh) {
                AgentLinkMod.LOG.info("agent-link wrote default config to {}", path);
                AgentLinkMod.LOG.info("agent-link generated token: {}", token);
            }
        }
    }

    private static List<String> readStringList(CommentedFileConfig cfg, String key, List<String> fallback) {
        Object raw = cfg.get(key);
        if (raw == null) return fallback;
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        AgentLinkMod.LOG.warn("agent-link: config key '{}' is not a list; using default", key);
        return fallback;
    }

    private static String generateToken() {
        byte[] buf = new byte[24];
        new SecureRandom().nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }
}

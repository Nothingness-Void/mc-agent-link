package world.agentlink.config;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import net.minecraftforge.fml.loading.FMLPaths;
import world.agentlink.AgentLinkMod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;

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
            String version
    ) {}

    private static final String FILE_NAME = "agent-link.toml";
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

            if (token.isBlank()) {
                token = generateToken();
                cfg.set("token", token);
                cfg.setComment("token", " Shared secret. Anyone with this token can run console commands. Keep it safe.");
            }
            cfg.set("listen_port", port);
            cfg.setComment("listen_port", " WebSocket port. Default 25580.");
            cfg.set("allow_remote", allowRemote);
            cfg.setComment("allow_remote", " false = bind to 127.0.0.1 only. true = bind to 0.0.0.0 (LAN/internet). Use a firewall if true.");

            cfg.save();
            CURRENT = new Snapshot(port, allowRemote, token, "0.1.0");

            if (fresh) {
                AgentLinkMod.LOG.info("agent-link wrote default config to {}", path);
                AgentLinkMod.LOG.info("agent-link generated token: {}", token);
            }
        }
    }

    private static String generateToken() {
        byte[] buf = new byte[24];
        new SecureRandom().nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }
}

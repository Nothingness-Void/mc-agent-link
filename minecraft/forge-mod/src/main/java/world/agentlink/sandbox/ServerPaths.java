package world.agentlink.sandbox;

import net.minecraft.server.MinecraftServer;
import world.agentlink.dispatch.ToolException;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Path sandbox helpers shared by file-access tools.
 *
 * <p>The contract every caller relies on: any path returned from
 * {@link #resolveUnderRoot} or {@link #resolveUnderConfig} is the result of
 * {@code root.resolve(rel).normalize()} and is guaranteed to live inside
 * {@code root} (or {@code root/config} respectively). Callers must not
 * pass the returned path through any further user-controlled join.
 */
public final class ServerPaths {

    private ServerPaths() {}

    public static Path root(MinecraftServer mc) throws ToolException {
        try {
            return mc.getServerDirectory().toPath().toRealPath();
        } catch (IOException e) {
            throw new ToolException("INTERNAL_ERROR", "Cannot resolve server root: " + e.getMessage());
        }
    }

    /** Resolve {@code rel} relative to server root. Rejects absolute paths and parent escapes. */
    public static Path resolveUnderRoot(MinecraftServer mc, String rel) throws ToolException {
        if (rel == null) throw new ToolException("INVALID_ARGS", "Path is required");
        // Normalize separator on Windows: accept both "/" and "\"
        String cleaned = rel.replace('\\', '/').trim();
        if (cleaned.startsWith("/")) cleaned = cleaned.substring(1);

        Path root = root(mc);
        Path target = root.resolve(cleaned).normalize();
        if (!target.startsWith(root)) {
            throw new ToolException("INVALID_ARGS", "Path escapes server root: " + rel);
        }
        return target;
    }

    /** Resolve {@code rel}, but only succeed if the result lives under {@code config/}. */
    public static Path resolveUnderConfig(MinecraftServer mc, String rel) throws ToolException {
        Path target = resolveUnderRoot(mc, rel);
        Path configDir = root(mc).resolve("config");
        if (!target.startsWith(configDir)) {
            throw new ToolException("INVALID_ARGS", "Writes are limited to config/");
        }
        return target;
    }

    /** Render a server-root-relative path (forward slashes) for stable output across OSes. */
    public static String relativize(MinecraftServer mc, Path target) throws ToolException {
        Path root = root(mc);
        return root.relativize(target).toString().replace('\\', '/');
    }
}

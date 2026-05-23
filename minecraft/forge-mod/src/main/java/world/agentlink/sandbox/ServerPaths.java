package world.agentlink.sandbox;

import net.minecraft.server.MinecraftServer;
import world.agentlink.config.AgentLinkConfig;
import world.agentlink.dispatch.ToolException;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;

/**
 * Path sandbox helpers shared by file-access tools.
 *
 * <p>The contract every caller relies on: any path returned from
 * {@link #resolveUnderRoot} or {@link #resolveForWrite} is the result of
 * {@code root.resolve(rel).normalize()} and is guaranteed to live inside
 * {@code root}. Callers must not pass the returned path through any further
 * user-controlled join.
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

    /**
     * Resolve {@code rel} for a write. Path must be inside server root and pass the
     * configured {@code write_allow} / {@code write_deny} glob lists.
     *
     * <p>Evaluation order:
     * <ol>
     *   <li>Path inside server root (anything escaping is rejected).</li>
     *   <li>If any {@code write_deny} pattern matches → reject.</li>
     *   <li>If no {@code write_allow} pattern matches → reject.</li>
     *   <li>Otherwise → allowed.</li>
     * </ol>
     *
     * <p>Patterns are evaluated against the server-root-relative path with forward
     * slashes (e.g. {@code config/foo.toml}). Glob syntax is the JDK's
     * {@code FileSystem#getPathMatcher} {@code "glob:..."} syntax: {@code **} matches
     * any number of segments, {@code *} matches any chars within a segment, {@code ?}
     * matches one char.
     */
    public static Path resolveForWrite(MinecraftServer mc, String rel) throws ToolException {
        Path target = resolveUnderRoot(mc, rel);
        AgentLinkConfig.Snapshot cfg = AgentLinkConfig.get();
        String relPath = relativize(mc, target);

        List<PathMatcher> denyMatchers = compile(cfg.writeDeny());
        for (int i = 0; i < denyMatchers.size(); i++) {
            if (matches(denyMatchers.get(i), relPath)) {
                throw new ToolException("INVALID_ARGS",
                        "Path matches write_deny pattern '" + cfg.writeDeny().get(i) + "': " + relPath);
            }
        }

        List<PathMatcher> allowMatchers = compile(cfg.writeAllow());
        if (allowMatchers.isEmpty()) {
            throw new ToolException("INVALID_ARGS",
                    "write_allow is empty; no writes are permitted. Edit config/agent-link.toml to enable writes.");
        }
        for (PathMatcher m : allowMatchers) {
            if (matches(m, relPath)) {
                return target;
            }
        }

        throw new ToolException("INVALID_ARGS",
                "Path does not match any write_allow pattern. Allowed: " + cfg.writeAllow() + ". Got: " + relPath);
    }

    private static List<PathMatcher> compile(List<String> patterns) {
        List<PathMatcher> out = new ArrayList<>(patterns.size());
        for (String p : patterns) {
            try {
                out.add(FileSystems.getDefault().getPathMatcher("glob:" + p));
            } catch (Exception e) {
                throw new IllegalStateException("Invalid glob pattern in agent-link.toml: " + p, e);
            }
        }
        return out;
    }

    /**
     * The JDK glob matcher operates on Path. We construct a Path from the
     * forward-slash relative string using the default file system, which on Windows
     * uses backslashes — so we feed the matcher a Path produced from the same
     * separator the patterns assume.
     */
    private static boolean matches(PathMatcher matcher, String relForwardSlash) {
        Path p = Path.of(relForwardSlash.replace('/', java.io.File.separatorChar));
        return matcher.matches(p);
    }

    /** Render a server-root-relative path (forward slashes) for stable output across OSes. */
    public static String relativize(MinecraftServer mc, Path target) throws ToolException {
        Path root = root(mc);
        return root.relativize(target).toString().replace('\\', '/');
    }
}

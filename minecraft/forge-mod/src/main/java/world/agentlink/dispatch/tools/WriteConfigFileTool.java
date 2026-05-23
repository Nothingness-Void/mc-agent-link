package world.agentlink.dispatch.tools;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import world.agentlink.AgentLinkMod;
import world.agentlink.dispatch.RequestDispatcher;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.sandbox.ServerPaths;
import world.agentlink.transport.ClientSession;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * Writes a file under the server root, gated by the configured write_allow /
 * write_deny glob lists in {@code config/agent-link.toml}.
 *
 * <p>Sandbox: see {@link ServerPaths#resolveForWrite} for the allow/deny semantics.
 * <p>Audit: every successful write is mirrored to the server log so a human reviewer
 * can see what an agent changed.
 * <p>Backup: when the target exists, the prior contents are copied to
 * {@code config/.agent-link-backup/<name>.<ts>.bak} before being overwritten.
 */
public class WriteConfigFileTool implements Tool {

    private static final int HARD_MAX_BYTES = 4 * 1024 * 1024;
    private static final String BACKUP_DIR = ".agent-link-backup";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final MinecraftServer mc;

    public WriteConfigFileTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "write_config_file";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        String rel = RequestDispatcher.requireString(args, "path");
        boolean overwrite = args.has("overwrite") && args.get("overwrite").getAsBoolean();
        String encoding = args.has("encoding") && !args.get("encoding").isJsonNull()
                ? args.get("encoding").getAsString().toLowerCase()
                : "utf-8";
        if (!encoding.equals("utf-8") && !encoding.equals("base64")) {
            throw new ToolException("INVALID_ARGS", "encoding must be utf-8 or base64");
        }

        String contentStr = RequestDispatcher.requireString(args, "content");
        byte[] payload;
        if (encoding.equals("base64")) {
            try {
                payload = Base64.getDecoder().decode(contentStr);
            } catch (IllegalArgumentException e) {
                throw new ToolException("INVALID_ARGS", "Bad base64: " + e.getMessage());
            }
        } else {
            payload = contentStr.getBytes(StandardCharsets.UTF_8);
        }

        if (payload.length > HARD_MAX_BYTES) {
            throw new ToolException("INVALID_ARGS",
                    "content exceeds " + HARD_MAX_BYTES + " bytes");
        }

        Path target = ServerPaths.resolveForWrite(mc, rel);

        // Forbid writing into the backup dir directly to keep restoration sane.
        // Even if write_allow somehow includes the backup dir, this safety net stays.
        Path configRoot = ServerPaths.root(mc).resolve("config");
        Path backupRoot = configRoot.resolve(BACKUP_DIR);
        if (target.startsWith(backupRoot)) {
            throw new ToolException("INVALID_ARGS", "Cannot write into the backup directory");
        }

        boolean existed = Files.exists(target);
        if (existed && Files.isDirectory(target)) {
            throw new ToolException("INVALID_ARGS", "Target is a directory: " + rel);
        }
        if (existed && !overwrite) {
            throw new ToolException("INVALID_ARGS",
                    "File exists and overwrite=false: " + rel);
        }

        String backupRel = null;
        try {
            Files.createDirectories(target.getParent());

            if (existed) {
                Files.createDirectories(backupRoot);
                String stamp = LocalDateTime.now().format(TS);
                // Encode the relative path so backups from outside config/ don't collide
                // with same-named files. Inside config/ this stays close to the old name.
                String shown = ServerPaths.relativize(mc, target);
                String safeName = shown.replace('/', '_');
                Path backup = backupRoot.resolve(safeName + "." + stamp + ".bak");
                Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
                backupRel = ServerPaths.relativize(mc, backup);
            }

            Path tmp = target.resolveSibling(target.getFileName().toString() + ".agent-link.tmp");
            Files.write(tmp, payload);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new ToolException("INTERNAL_ERROR", "write failed: " + e.getMessage());
        }

        String shownPath = ServerPaths.relativize(mc, target);
        AgentLinkMod.LOG.info("agent-link: wrote {} ({} bytes){}",
                shownPath, payload.length,
                backupRel == null ? "" : ", backup=" + backupRel);

        JsonObject r = new JsonObject();
        r.addProperty("path", shownPath);
        r.addProperty("bytes_written", payload.length);
        r.addProperty("created", !existed);
        if (backupRel != null) r.addProperty("backup", backupRel);
        return r;
    }
}

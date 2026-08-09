package world.agentlink.dispatch.tools;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import world.agentlink.dispatch.RequestDispatcher;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.sandbox.ServerPaths;
import world.agentlink.transport.ClientSession;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

public class ReadServerFileTool implements Tool {

    private static final int DEFAULT_MAX_BYTES = 256 * 1024;
    private static final int HARD_MAX_BYTES = 4 * 1024 * 1024;

    private final MinecraftServer mc;

    public ReadServerFileTool(MinecraftServer mc) {
        this.mc = mc;
    }

    @Override
    public String name() {
        return "read_server_file";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        String rel = RequestDispatcher.requireString(args, "path");
        long offset = args.has("offset") ? args.get("offset").getAsLong() : 0L;
        if (offset < 0) throw new ToolException("INVALID_ARGS", "offset must be >= 0");

        int maxBytes = args.has("max_bytes") ? args.get("max_bytes").getAsInt() : DEFAULT_MAX_BYTES;
        if (maxBytes <= 0) maxBytes = DEFAULT_MAX_BYTES;
        if (maxBytes > HARD_MAX_BYTES) maxBytes = HARD_MAX_BYTES;

        Path target = ServerPaths.resolveUnderRoot(mc, rel);

        if (!Files.exists(target)) throw new ToolException("INVALID_ARGS", "Not found: " + rel);
        if (Files.isDirectory(target)) throw new ToolException("INVALID_ARGS", "Is a directory: " + rel);

        long size;
        try {
            size = Files.size(target);
        } catch (IOException e) {
            throw new ToolException("INTERNAL_ERROR", "stat failed: " + e.getMessage());
        }
        if (offset > size) offset = size;

        long remaining = size - offset;
        int toRead = (int) Math.min(remaining, maxBytes);
        byte[] buf = new byte[toRead];

        try (var ch = Files.newByteChannel(target)) {
            ch.position(offset);
            int read = 0;
            var bb = java.nio.ByteBuffer.wrap(buf);
            while (read < toRead) {
                int n = ch.read(bb);
                if (n <= 0) break;
                read += n;
            }
            if (read < toRead) {
                byte[] shrunk = new byte[read];
                System.arraycopy(buf, 0, shrunk, 0, read);
                buf = shrunk;
            }
        } catch (IOException e) {
            throw new ToolException("INTERNAL_ERROR", "read failed: " + e.getMessage());
        }

        boolean truncated = offset + buf.length < size;

        JsonObject r = new JsonObject();
        r.addProperty("path", ServerPaths.relativize(mc, target));
        r.addProperty("size", size);
        r.addProperty("offset", offset);
        r.addProperty("bytes_read", buf.length);
        r.addProperty("truncated", truncated);

        // Try strict UTF-8; if it fails, fall back to base64 so the agent can still see something.
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder();
            String text = decoder.decode(java.nio.ByteBuffer.wrap(buf)).toString();
            r.addProperty("encoding", "utf-8");
            r.addProperty("content", text);
        } catch (CharacterCodingException e) {
            r.addProperty("encoding", "base64");
            r.addProperty("content", Base64.getEncoder().encodeToString(buf));
        }
        return r;
    }
}

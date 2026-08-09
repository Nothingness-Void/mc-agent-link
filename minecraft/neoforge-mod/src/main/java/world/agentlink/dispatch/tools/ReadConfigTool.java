package world.agentlink.dispatch.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import world.agentlink.dispatch.RequestDispatcher;
import world.agentlink.dispatch.Tool;
import world.agentlink.dispatch.ToolException;
import world.agentlink.approval.CallTier;
import world.agentlink.transport.ClientSession;
import world.agentlink.security.SensitiveDataRedactor;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads a known config-class file by short name. Bypasses the broader sandbox of
 * read_server_file by exposing only an explicit allowlist of config files agents
 * actually need to look at, with no path arg accepted from the caller.
 *
 * <p>{@code list} mode returns the names that resolve to existing files.
 */
public class ReadConfigTool implements Tool {

    private static final int MAX_BYTES = 256 * 1024;

    private final MinecraftServer mc;
    private final Map<String, String> allowList;

    public ReadConfigTool(MinecraftServer mc) {
        this.mc = mc;
        // Each value is a path relative to the server root.
        Map<String, String> map = new LinkedHashMap<>();
        map.put("server.properties", "server.properties");
        map.put("agent-link.toml", "config/agent-link.toml");
        map.put("agent-link-agent.toml", "config/agent-link-agent.toml");
        map.put("whitelist.json", "whitelist.json");
        map.put("ops.json", "ops.json");
        map.put("banned-players.json", "banned-players.json");
        map.put("banned-ips.json", "banned-ips.json");
        map.put("forge-common.toml", "config/forge-common.toml");
        map.put("forge-server.toml", "defaultconfigs/forge-server.toml");
        this.allowList = java.util.Collections.unmodifiableMap(map);
    }

    @Override
    public String name() {
        return "read_config";
    }

    @Override
    public JsonObject invoke(JsonObject args, ClientSession session) throws ToolException {
        String mode = (args.has("mode") && !args.get("mode").isJsonNull()
                ? args.get("mode").getAsString() : "read").trim().toLowerCase();
        if ("list".equals(mode)) return listAvailable();

        String name = RequestDispatcher.requireString(args, "name");
        String relative = allowList.get(name);
        if (relative == null) {
            throw new ToolException("INVALID_ARGS",
                    "Unknown config file: " + name + " (try \"mode\":\"list\")");
        }
        Path serverRoot = mc.getServerDirectory().toAbsolutePath().normalize();
        Path target = serverRoot.resolve(relative).normalize();
        if (!target.startsWith(serverRoot)) {
            throw new ToolException("INVALID_ARGS", "Path escapes server root: " + relative);
        }
        if (!Files.exists(target)) {
            throw new ToolException("INVALID_ARGS", "Config file not found: " + relative);
        }
        long size;
        try {
            size = Files.size(target);
        } catch (IOException e) {
            throw new ToolException("INTERNAL_ERROR", "stat failed: " + e.getMessage());
        }
        boolean truncated = size > MAX_BYTES;
        byte[] buf;
        try {
            byte[] all = Files.readAllBytes(target);
            buf = all.length <= MAX_BYTES ? all : java.util.Arrays.copyOf(all, MAX_BYTES);
        } catch (IOException e) {
            throw new ToolException("INTERNAL_ERROR", "read failed: " + e.getMessage());
        }

        JsonObject r = new JsonObject();
        r.addProperty("name", name);
        r.addProperty("relative_path", relative);
        r.addProperty("size", size);
        r.addProperty("bytes_read", buf.length);
        r.addProperty("truncated", truncated);
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(buf)).toString();
            r.addProperty("encoding", "utf-8");
            String safe = SensitiveDataRedactor.config(name, text, CallTier.is(CallTier.Tier.CONSOLE));
            r.addProperty("content", safe);
            r.addProperty("redacted", !safe.equals(text));
        } catch (CharacterCodingException ex) {
            r.addProperty("encoding", "base64");
            r.addProperty("content", Base64.getEncoder().encodeToString(buf));
            r.addProperty("redacted", false);
        }
        return r;
    }

    private JsonObject listAvailable() {
        JsonObject r = new JsonObject();
        Path serverRoot = mc.getServerDirectory().toAbsolutePath().normalize();
        JsonArray names = new JsonArray();
        for (Map.Entry<String, String> e : allowList.entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", e.getKey());
            o.addProperty("relative_path", e.getValue());
            Path p = serverRoot.resolve(e.getValue()).normalize();
            o.addProperty("exists", Files.exists(p));
            names.add(o);
        }
        r.add("entries", names);
        r.addProperty("count", names.size());
        return r;
    }
}

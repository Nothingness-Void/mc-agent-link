package world.agentlink.transport.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import world.agentlink.AgentLinkMod;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * Loads the MCP tool spec list and the instructions string from classpath resources packed with the
 * mod. Both are static — they describe the server's MCP-facing API and never change at runtime.
 */
public final class McpToolSpecs {

    private static final String TOOLS_RESOURCE = "agent-link/mcp-tools.json";
    private static final String INSTRUCTIONS_RESOURCE = "agent-link/mcp-instructions.txt";

    private static volatile JsonArray TOOLS;
    private static volatile String INSTRUCTIONS;

    private McpToolSpecs() {}

    public static JsonArray tools() {
        ensureLoaded();
        return TOOLS;
    }

    public static String instructions() {
        ensureLoaded();
        return INSTRUCTIONS;
    }

    private static void ensureLoaded() {
        if (TOOLS != null && INSTRUCTIONS != null) return;
        synchronized (McpToolSpecs.class) {
            if (TOOLS == null) {
                TOOLS = loadJsonArray(TOOLS_RESOURCE);
            }
            if (INSTRUCTIONS == null) {
                INSTRUCTIONS = loadText(INSTRUCTIONS_RESOURCE).trim();
            }
        }
    }

    private static JsonArray loadJsonArray(String resource) {
        try (InputStream in = McpToolSpecs.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                AgentLinkMod.LOG.error("agent-link: missing MCP resource {}", resource);
                return new JsonArray();
            }
            JsonElement el = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            if (!el.isJsonArray()) {
                AgentLinkMod.LOG.error("agent-link: {} is not a JSON array", resource);
                return new JsonArray();
            }
            return el.getAsJsonArray();
        } catch (IOException e) {
            AgentLinkMod.LOG.error("agent-link: failed to read {}", resource, e);
            return new JsonArray();
        }
    }

    private static String loadText(String resource) {
        try (InputStream in = McpToolSpecs.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                AgentLinkMod.LOG.error("agent-link: missing MCP resource {}", resource);
                return "";
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return r.lines().collect(Collectors.joining("\n"));
            }
        } catch (IOException e) {
            AgentLinkMod.LOG.error("agent-link: failed to read {}", resource, e);
            return "";
        }
    }
}

package world.agentlink.transport.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import world.agentlink.AgentLinkMod;
import world.agentlink.config.AgentLinkConfig.Snapshot;
import world.agentlink.dispatch.RequestDispatcher;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * MCP Streamable HTTP transport using JDK's built-in {@link HttpServer}. Single endpoint
 * {@code POST /mcp}; we don't implement server-initiated SSE because all tool calls are
 * client-driven and our pull-mode event delivery means there's nothing to stream.
 */
public final class McpHttpServer implements HttpHandler {

    private static final String ENDPOINT = "/mcp";
    private static final String PAIR_ENDPOINT = "/pair";
    private static final int MAX_BODY_BYTES = 128 * 1024;
    private static final int PAIR_CODE_TTL_SECONDS = 10 * 60;
    private static final int MAX_EXPIRED_PAIR_CODES = 8;
    private static final int MAX_USED_PAIR_CODES = 8;
    private static final String REPO_URL = "https://github.com/Nothingness-Void/mc-agent-link";
    private static final String SETUP_LINK_BASE = REPO_URL + "/blob/main/AGENTS.md";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Snapshot cfg;
    private final RequestDispatcher dispatcher;
    private final JsonRpcHandler rpc;
    private final InetSocketAddress address;
    private final String publicMcpUrl;
    private final String publicPairUrl;
    private final ScheduledExecutorService pairRefreshExecutor;
    private final List<String> expiredPairCodes = new ArrayList<>();
    private final List<String> usedPairCodes = new ArrayList<>();
    private ScheduledFuture<?> pairRefreshTask;
    private String pairCode;
    private long pairExpiresAtMs;
    private boolean pairConsumed;
    private HttpServer server;

    public McpHttpServer(Snapshot cfg, RequestDispatcher dispatcher) {
        this.cfg = cfg;
        this.dispatcher = dispatcher;
        this.rpc = new JsonRpcHandler(dispatcher, cfg.version());
        String host = cfg.allowRemote() ? "0.0.0.0" : "127.0.0.1";
        this.address = new InetSocketAddress(host, cfg.mcpListenPort());
        String publicHost = "127.0.0.1";
        this.publicMcpUrl = "http://" + publicHost + ":" + cfg.mcpListenPort() + ENDPOINT;
        this.publicPairUrl = "http://" + publicHost + ":" + cfg.mcpListenPort() + PAIR_ENDPOINT;
        this.pairRefreshExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agent-link-pair-refresh");
            t.setDaemon(true);
            return t;
        });
        rotatePairCode();
    }

    public void start() throws IOException {
        server = HttpServer.create(address, 0);
        server.createContext(ENDPOINT, this);
        server.createContext(PAIR_ENDPOINT, this::handlePair);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "agent-link-mcp-http");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        schedulePairRefresh();
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(2);
            server = null;
        }
        pairRefreshExecutor.shutdownNow();
    }

    public InetSocketAddress address() {
        return address;
    }

    public synchronized String setupLink() {
        JsonObject payload = new JsonObject();
        payload.addProperty("v", 1);
        payload.addProperty("repo", REPO_URL);
        payload.addProperty("instructions_url", SETUP_LINK_BASE);
        payload.addProperty("mcp_url", publicMcpUrl);
        payload.addProperty("pair_url", publicPairUrl);
        payload.addProperty("pair_code", pairCode);
        payload.addProperty("expires_at", pairExpiresAtMs);
        payload.addProperty("expires_at_iso", Instant.ofEpochMilli(pairExpiresAtMs).toString());
        payload.addProperty("allow_remote", cfg.allowRemote());
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8));
        return SETUP_LINK_BASE + "#agent-link-setup=" + encoded;
    }

    public synchronized long pairExpiresAtMs() {
        return pairExpiresAtMs;
    }

    public SetupLink refreshSetupLink() {
        SetupLink fresh;
        synchronized (this) {
            rotatePairCode();
            fresh = new SetupLink(setupLink(), pairExpiresAtMs);
        }
        AgentLinkMod.LOG.info("agent-link setup link manually refreshed (send this to your AI agent, one use, expires at {}): {}",
                Instant.ofEpochMilli(fresh.expiresAtMs()), fresh.link());
        schedulePairRefresh();
        return fresh;
    }

    public record SetupLink(String link, long expiresAtMs) {}

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try (ex) {
            String method = ex.getRequestMethod();

            if ("GET".equalsIgnoreCase(method)) {
                // Spec allows servers to reject GET when they don't support server-initiated
                // streaming. We don't, so respond 405 with Allow header.
                ex.getResponseHeaders().add("Allow", "POST");
                writeStatus(ex, 405, "GET not supported on this endpoint");
                return;
            }
            if (!"POST".equalsIgnoreCase(method)) {
                ex.getResponseHeaders().add("Allow", "POST");
                writeStatus(ex, 405, "Only POST is supported");
                return;
            }

            String origin = firstHeader(ex, "Origin");
            if (!originAllowed(origin)) {
                writeStatus(ex, 403, "Origin not allowed");
                return;
            }

            String authz = firstHeader(ex, "Authorization");
            if (!tokenMatches(authz)) {
                ex.getResponseHeaders().add("WWW-Authenticate", "Bearer realm=\"agent-link\"");
                writeStatus(ex, 401, "Bearer token required");
                return;
            }

            String accept = firstHeader(ex, "Accept");
            if (accept != null && !accept.isBlank() && !acceptsJson(accept)) {
                writeStatus(ex, 406, "Accept must include application/json");
                return;
            }

            byte[] bodyBytes = readBody(ex.getRequestBody(), MAX_BODY_BYTES);
            if (bodyBytes == null) {
                writeStatus(ex, 413, "Request body too large");
                return;
            }
            String body = new String(bodyBytes, StandardCharsets.UTF_8);

            JsonElement parsed;
            try {
                parsed = JsonParser.parseString(body);
            } catch (Exception parseErr) {
                writeJson(ex, 400, JsonRpcHandler.error(null, -32700, "Parse error: " + parseErr.getMessage()));
                return;
            }
            if (!parsed.isJsonObject()) {
                // Batches are deprecated in 2025-06-18 — we don't support them.
                writeJson(ex, 400, JsonRpcHandler.error(null, -32600, "Expected a single JSON-RPC object"));
                return;
            }

            JsonObject reqObj = parsed.getAsJsonObject();
            JsonObject response;
            try {
                response = rpc.handle(reqObj).get();
            } catch (Exception e) {
                AgentLinkMod.LOG.warn("agent-link MCP handler error", e);
                writeJson(ex, 500, JsonRpcHandler.error(reqObj.get("id"), -32603, "Internal error: " + e.getMessage()));
                return;
            }

            if (response == null) {
                // Notification — no response body. Spec allows 202 Accepted.
                writeStatus(ex, 202, null);
                return;
            }
            writeJson(ex, 200, response);
        } catch (Exception e) {
            AgentLinkMod.LOG.warn("agent-link MCP request crashed", e);
        }
    }

    private void handlePair(HttpExchange ex) throws IOException {
        try (ex) {
            String method = ex.getRequestMethod();
            if (!"POST".equalsIgnoreCase(method)) {
                ex.getResponseHeaders().add("Allow", "POST");
                writeStatus(ex, 405, "Only POST is supported");
                return;
            }

            String origin = firstHeader(ex, "Origin");
            if (!originAllowed(origin)) {
                writeStatus(ex, 403, "Origin not allowed");
                return;
            }

            String accept = firstHeader(ex, "Accept");
            if (accept != null && !accept.isBlank() && !acceptsJson(accept)) {
                writeStatus(ex, 406, "Accept must include application/json");
                return;
            }

            byte[] bodyBytes = readBody(ex.getRequestBody(), MAX_BODY_BYTES);
            if (bodyBytes == null) {
                writeStatus(ex, 413, "Request body too large");
                return;
            }

            JsonElement parsed;
            try {
                parsed = JsonParser.parseString(new String(bodyBytes, StandardCharsets.UTF_8));
            } catch (Exception parseErr) {
                writeStatus(ex, 400, "Request body must be JSON");
                return;
            }
            if (!parsed.isJsonObject()) {
                writeStatus(ex, 400, "Request body must be a JSON object");
                return;
            }

            JsonObject body = parsed.getAsJsonObject();
            String code = "";
            if (body.has("pair_code") && !body.get("pair_code").isJsonNull()) {
                code = body.get("pair_code").getAsString();
            } else if (body.has("code") && !body.get("code").isJsonNull()) {
                code = body.get("code").getAsString();
            }

            PairResult pairResult = consumePairCode(code);
            if (pairResult.result() == null) {
                writePairError(ex, pairResult.reason());
                return;
            }
            writeJson(ex, 200, pairResult.result());
        } catch (Exception e) {
            AgentLinkMod.LOG.warn("agent-link pair request crashed", e);
        }
    }

    private record PairResult(JsonObject result, String reason) {}

    private synchronized PairResult consumePairCode(String code) {
        String normalized = code == null ? "" : code.trim();
        if (usedPairCodeMatches(normalized)) return new PairResult(null, "used");
        if (pairConsumed) {
            if (constantTimeEquals(normalized, pairCode)) return new PairResult(null, "used");
            return new PairResult(null, "unknown");
        }
        if (System.currentTimeMillis() > pairExpiresAtMs) {
            if (constantTimeEquals(normalized, pairCode)) return new PairResult(null, "expired");
            return new PairResult(null, expiredPairCodeMatches(normalized) ? "expired" : "unknown");
        }
        if (expiredPairCodeMatches(normalized)) return new PairResult(null, "expired");
        if (!constantTimeEquals(normalized, pairCode)) return new PairResult(null, "unknown");

        pairConsumed = true;
        addUsedPairCode(pairCode);

        JsonObject headers = new JsonObject();
        headers.addProperty("Authorization", "Bearer " + cfg.token());

        JsonObject mcp = new JsonObject();
        mcp.addProperty("type", "http");
        mcp.addProperty("url", publicMcpUrl);
        mcp.add("headers", headers);

        JsonObject server = new JsonObject();
        server.addProperty("minecraft", true);
        server.addProperty("version", cfg.version());

        JsonObject result = new JsonObject();
        result.add("mcp", mcp);
        result.add("server", server);
        result.addProperty("mcp_host_hint", "Add this object under mcpServers.minecraft in your MCP host config.");
        return new PairResult(result, "");
    }

    private synchronized void rotatePairCode() {
        if (pairCode != null && !pairConsumed) addExpiredPairCode(pairCode);
        pairCode = generatePairCode();
        pairExpiresAtMs = System.currentTimeMillis() + PAIR_CODE_TTL_SECONDS * 1000L;
        pairConsumed = false;
    }

    private void addExpiredPairCode(String code) {
        if (code == null || code.isBlank()) return;
        expiredPairCodes.add(code);
        while (expiredPairCodes.size() > MAX_EXPIRED_PAIR_CODES) {
            expiredPairCodes.remove(0);
        }
    }

    private boolean expiredPairCodeMatches(String code) {
        for (String expired : expiredPairCodes) {
            if (constantTimeEquals(code, expired)) return true;
        }
        return false;
    }

    private void addUsedPairCode(String code) {
        if (code == null || code.isBlank()) return;
        usedPairCodes.add(code);
        while (usedPairCodes.size() > MAX_USED_PAIR_CODES) {
            usedPairCodes.remove(0);
        }
    }

    private boolean usedPairCodeMatches(String code) {
        for (String used : usedPairCodes) {
            if (constantTimeEquals(code, used)) return true;
        }
        return false;
    }

    private void refreshPairCodeIfNeeded() {
        String link;
        long expires;
        synchronized (this) {
            if (pairConsumed || server == null) return;
            rotatePairCode();
            link = setupLink();
            expires = pairExpiresAtMs;
        }
        AgentLinkMod.LOG.info("agent-link setup link refreshed (send this to your AI agent, one use, expires at {}): {}",
                Instant.ofEpochMilli(expires), link);
        schedulePairRefresh();
    }

    private void schedulePairRefresh() {
        try {
            synchronized (this) {
                if (pairRefreshTask != null) pairRefreshTask.cancel(false);
                pairRefreshTask = pairRefreshExecutor.schedule(this::refreshPairCodeIfNeeded,
                        PAIR_CODE_TTL_SECONDS, TimeUnit.SECONDS);
            }
        } catch (RejectedExecutionException ignored) {
        }
    }

    private boolean originAllowed(String origin) {
        List<String> allowed = cfg.mcpAllowedOrigins();
        // No Origin header = native client (Claude Code, curl). Map to literal "null" so the
        // operator can include/exclude these via the allowlist.
        String key = origin == null ? "null" : origin;
        for (String pattern : allowed) {
            if (pattern.equals("*") || pattern.equalsIgnoreCase(key)) return true;
        }
        return false;
    }

    private static String generatePairCode() {
        int a = RANDOM.nextInt(10000);
        int b = RANDOM.nextInt(10000);
        return String.format(Locale.ROOT, "%04d-%04d", a, b);
    }

    private boolean tokenMatches(String authz) {
        if (authz == null) return false;
        String prefix = "Bearer ";
        if (authz.length() <= prefix.length()) return false;
        if (!authz.regionMatches(true, 0, prefix, 0, prefix.length())) return false;
        String token = authz.substring(prefix.length()).trim();
        return constantTimeEquals(token, cfg.token());
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }

    private static final Set<String> JSON_TYPES = Set.of("application/json", "*/*", "application/*");

    private static boolean acceptsJson(String accept) {
        for (String part : accept.split(",")) {
            String token = part.trim();
            int semi = token.indexOf(';');
            if (semi >= 0) token = token.substring(0, semi).trim();
            if (token.isEmpty()) continue;
            String lower = token.toLowerCase(Locale.ROOT);
            if (JSON_TYPES.contains(lower)) return true;
        }
        return false;
    }

    private static String firstHeader(HttpExchange ex, String name) {
        List<String> values = ex.getRequestHeaders().get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static byte[] readBody(InputStream in, int max) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(tmp)) != -1) {
            total += n;
            if (total > max) return null;
            buf.write(tmp, 0, n);
        }
        return buf.toByteArray();
    }

    private static void writeStatus(HttpExchange ex, int status, String message) throws IOException {
        byte[] body = message == null ? new byte[0] : message.getBytes(StandardCharsets.UTF_8);
        if (body.length > 0) {
            ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        }
        ex.sendResponseHeaders(status, body.length);
        if (body.length > 0) {
            try (var os = ex.getResponseBody()) {
                os.write(body);
            }
        }
    }

    private static void writeJson(HttpExchange ex, int status, JsonObject obj) throws IOException {
        byte[] body = obj.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, body.length);
        try (var os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private static void writePairError(HttpExchange ex, String reason) throws IOException {
        JsonObject error = new JsonObject();
        error.addProperty("error", "Invalid or expired pair code");
        error.addProperty("reason", reason == null || reason.isBlank() ? "unknown" : reason);
        writeJson(ex, 401, error);
    }
}

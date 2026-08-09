package world.agentlink.spigot;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** MCP Streamable HTTP server with one-use local setup-endpoint pairing. */
public final class SpigotMcpServer implements HttpHandler {
    private static final String REPO_URL = "https://github.com/Nothingness-Void/mc-agent-link";
    private static final String SETUP_LINK_BASE = REPO_URL + "/blob/main/AGENTS.md";
    private static final String SETUP_ENDPOINT = "/pair/setup";
    private static final int MAX_BODY_BYTES = 128 * 1024;
    private static final int PAIR_TTL_SECONDS = 10 * 60;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> JSON_ACCEPT = Set.of("application/json", "*/*", "application/*");

    private final JavaPlugin plugin;
    private final SpigotConfig.Snapshot config;
    private final SpigotDispatcher dispatcher;
    private final TokenStore tokens;
    private final SpigotJsonRpcHandler rpc;
    private final InetSocketAddress address;
    private final String mcpUrl;
    private final String pairUrl;
    private final ScheduledExecutorService scheduler;
    private final List<String> expiredCodes = new ArrayList<>();
    private final List<String> usedCodes = new ArrayList<>();
    private HttpServer server;
    private ScheduledFuture<?> refreshTask;
    private String pairCode;
    private String pairSetupId;
    private long pairExpiresAtMs;
    private boolean consumed;
    private boolean pairingActive;
    private TokenStore.Tier pairTier = TokenStore.Tier.CONSOLE;

    public SpigotMcpServer(JavaPlugin plugin, SpigotConfig.Snapshot config,
                           SpigotDispatcher dispatcher, TokenStore tokens) {
        this.plugin = plugin;
        this.config = config;
        this.dispatcher = dispatcher;
        this.tokens = tokens;
        this.rpc = new SpigotJsonRpcHandler(dispatcher, config.version());
        String host = config.allowRemote() ? "0.0.0.0" : "127.0.0.1";
        this.address = new InetSocketAddress(host, config.mcpPort());
        this.mcpUrl = "http://" + config.publicHost() + ":" + config.mcpPort() + "/mcp";
        this.pairUrl = "http://" + config.publicHost() + ":" + config.mcpPort() + "/pair";
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "agent-link-spigot-pair-refresh");
            thread.setDaemon(true);
            return thread;
        });
        this.pairingActive = !tokens.hasIssuedTokens();
        rotatePairCode();
    }

    public void start() throws IOException {
        server = HttpServer.create(address, 0);
        server.createContext("/mcp", this);
        server.createContext(SETUP_ENDPOINT, this::handleSetup);
        server.createContext("/pair", this::handlePair);
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "agent-link-spigot-mcp-http");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
        if (pairingActive) scheduleRefresh();
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(2);
            server = null;
        }
        scheduler.shutdownNow();
    }

    public synchronized String setupLink() {
        return pairUrl + "/setup/" + pairSetupId;
    }

    /** Legacy GitHub-fragment form kept for older agents and manual recovery. */
    public synchronized String legacySetupLink() {
        JsonObject payload = new JsonObject();
        payload.addProperty("v", 1);
        payload.addProperty("repo", REPO_URL);
        payload.addProperty("instructions_url", SETUP_LINK_BASE);
        payload.addProperty("mcp_url", mcpUrl);
        payload.addProperty("pair_url", pairUrl);
        payload.addProperty("pair_code", pairCode);
        payload.addProperty("expires_at", pairExpiresAtMs);
        payload.addProperty("expires_at_iso", Instant.ofEpochMilli(pairExpiresAtMs).toString());
        payload.addProperty("allow_remote", config.allowRemote());
        payload.addProperty("token_tier", pairTier.name().toLowerCase(Locale.ROOT));
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8));
        return SETUP_LINK_BASE + "#agent-link-setup=" + encoded;
    }

    /** True when this server has not persisted a successful pairing yet. */
    public synchronized boolean pairingNeeded() {
        return !tokens.hasIssuedTokens();
    }

    public synchronized long pairExpiresAtMs() {
        return pairExpiresAtMs;
    }

    public synchronized SetupLink refreshSetupLink(TokenStore.Tier tier) {
        pairTier = tier == null ? TokenStore.Tier.CONSOLE : tier;
        pairingActive = true;
        rotatePairCode();
        SetupLink link = new SetupLink(setupLink(), pairExpiresAtMs);
        scheduleRefresh();
        return link;
    }

    public SetupLink refreshSetupLink() {
        return refreshSetupLink(TokenStore.Tier.CONSOLE);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            handleRpc(exchange);
        } catch (Exception e) {
            plugin.getLogger().warning("MCP request failed: " + e.getMessage());
        }
    }

    private void handleRpc(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            writeStatus(exchange, 405, "Only POST is supported");
            return;
        }
        if (!originAllowed(firstHeader(exchange, "Origin"))) {
            writeStatus(exchange, 403, "Origin not allowed");
            return;
        }
        CallTier tier = resolveTier(firstHeader(exchange, "Authorization"));
        if (tier == null) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=agent-link");
            writeStatus(exchange, 401, "Bearer token required");
            return;
        }
        String accept = firstHeader(exchange, "Accept");
        if (accept != null && !accept.isBlank() && !acceptsJson(accept)) {
            writeStatus(exchange, 406, "Accept must include application/json");
            return;
        }
        byte[] bytes = readBody(exchange.getRequestBody());
        if (bytes == null) {
            writeStatus(exchange, 413, "Request body too large");
            return;
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            writeJson(exchange, 400, SpigotJsonRpcHandler.error(null, -32700, "Parse error"));
            return;
        }
        if (!parsed.isJsonObject()) {
            writeJson(exchange, 400, SpigotJsonRpcHandler.error(null, -32600, "Expected a single JSON-RPC object"));
            return;
        }
        JsonObject request = parsed.getAsJsonObject();
        JsonObject response;
        try {
            response = rpc.handle(request, tier, null).get();
        } catch (Exception e) {
            writeJson(exchange, 500, SpigotJsonRpcHandler.error(request.get("id"), -32603, "Internal error: " + e.getMessage()));
            return;
        }
        if (response == null) {
            writeStatus(exchange, 202, null);
        } else {
            writeJson(exchange, 200, response);
        }
    }

    private void handlePair(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                writeStatus(exchange, 405, "Only POST is supported");
                return;
            }
            if (!originAllowed(firstHeader(exchange, "Origin"))) {
                writeStatus(exchange, 403, "Origin not allowed");
                return;
            }
            byte[] bytes = readBody(exchange.getRequestBody());
            if (bytes == null) {
                writeStatus(exchange, 413, "Request body too large");
                return;
            }
            JsonElement parsed;
            try {
                parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            } catch (Exception e) {
                writeStatus(exchange, 400, "Request body must be JSON");
                return;
            }
            String code = parsed.isJsonObject() && parsed.getAsJsonObject().has("pair_code")
                    ? parsed.getAsJsonObject().get("pair_code").getAsString() : "";
            PairResult pair = consume(code);
            if (pair.result() == null) {
                JsonObject error = new JsonObject();
                error.addProperty("error", "Invalid or expired pair code");
                error.addProperty("reason", pair.reason());
                writeJson(exchange, 401, error);
            } else {
                writeJson(exchange, 200, pair.result());
            }
        }
    }

    private void handleSetup(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "GET");
                writeStatus(exchange, 405, "Only GET is supported");
                return;
            }
            if (!originAllowed(firstHeader(exchange, "Origin"))) {
                writeStatus(exchange, 403, "Origin not allowed");
                return;
            }

            String prefix = SETUP_ENDPOINT + "/";
            String path = exchange.getRequestURI().getPath();
            String suppliedId = path != null && path.startsWith(prefix)
                    ? path.substring(prefix.length()) : "";
            JsonObject descriptor;
            int status = 200;
            synchronized (this) {
                if (!constantTimeEquals(suppliedId, pairSetupId)) {
                    descriptor = setupError("unknown");
                    status = 404;
                } else if (!pairingActive) {
                    descriptor = setupError("pairing_inactive");
                    status = 410;
                } else if (consumed) {
                    descriptor = setupError("used");
                    status = 410;
                } else if (System.currentTimeMillis() > pairExpiresAtMs) {
                    descriptor = setupError("expired");
                    status = 410;
                } else {
                    descriptor = setupDescriptor();
                }
            }
            writeJson(exchange, status, descriptor);
        }
    }

    private synchronized JsonObject setupDescriptor() {
        JsonObject pairRequest = new JsonObject();
        pairRequest.addProperty("method", "POST");
        pairRequest.addProperty("url", pairUrl);
        JsonObject body = new JsonObject();
        body.addProperty("pair_code", pairCode);
        pairRequest.add("body", body);

        JsonObject result = new JsonObject();
        result.addProperty("kind", "agent-link-pairing");
        result.addProperty("version", 2);
        result.addProperty("mcp_url", mcpUrl);
        result.addProperty("pair_url", pairUrl);
        result.addProperty("setup_url", setupLink());
        result.addProperty("pair_code", pairCode);
        result.addProperty("expires_at", pairExpiresAtMs);
        result.addProperty("expires_at_iso", Instant.ofEpochMilli(pairExpiresAtMs).toString());
        result.addProperty("allow_remote", config.allowRemote());
        result.addProperty("token_tier", pairTier.name().toLowerCase(Locale.ROOT));
        result.add("pair_request", pairRequest);
        result.addProperty("instructions", "POST pair_url with pair_request.body, then use the returned mcp object as mcpServers.minecraft.");
        return result;
    }

    private static JsonObject setupError(String reason) {
        JsonObject result = new JsonObject();
        result.addProperty("kind", "agent-link-pairing-error");
        result.addProperty("error", "Invalid or expired setup endpoint");
        result.addProperty("reason", reason);
        return result;
    }

    private synchronized PairResult consume(String rawCode) {
        String code = rawCode == null ? "" : rawCode.trim();
        if (contains(usedCodes, code)) return new PairResult(null, "used");
        if (!pairingActive) return new PairResult(null, "unknown");
        if (consumed) return new PairResult(null, "unknown");
        if (System.currentTimeMillis() > pairExpiresAtMs) return new PairResult(null, "expired");
        if (!constantTimeEquals(code, pairCode)) return new PairResult(null, "unknown");
        consumed = true;
        pairingActive = false;
        usedCodes.add(pairCode);
        while (usedCodes.size() > 8) usedCodes.remove(0);
        String token = TokenStore.generate();
        tokens.register(token, pairTier, "pair-" + pairTier.name().toLowerCase(Locale.ROOT));
        JsonObject headers = new JsonObject();
        headers.addProperty("Authorization", "Bearer " + token);
        JsonObject mcp = new JsonObject();
        mcp.addProperty("type", "http");
        mcp.addProperty("url", mcpUrl);
        mcp.add("headers", headers);
        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("minecraft", true);
        serverInfo.addProperty("loader", "spigot");
        serverInfo.addProperty("version", config.version());
        serverInfo.addProperty("token_tier", pairTier.name().toLowerCase(Locale.ROOT));
        JsonObject result = new JsonObject();
        result.add("mcp", mcp);
        result.add("server", serverInfo);
        result.addProperty("mcp_host_hint", "Add this object under mcpServers.minecraft in your MCP host config.");
        return new PairResult(result, "");
    }

    private synchronized void rotatePairCode() {
        if (pairCode != null && !consumed) {
            expiredCodes.add(pairCode);
            while (expiredCodes.size() > 8) expiredCodes.remove(0);
        }
        pairCode = String.format(Locale.ROOT, "%04d-%04d", RANDOM.nextInt(10000), RANDOM.nextInt(10000));
        pairSetupId = generateSetupId();
        pairExpiresAtMs = System.currentTimeMillis() + PAIR_TTL_SECONDS * 1000L;
        consumed = false;
    }

    private static String generateSetupId() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void scheduleRefresh() {
        synchronized (this) {
            if (refreshTask != null) refreshTask.cancel(false);
            refreshTask = scheduler.schedule(this::refreshIfNeeded, PAIR_TTL_SECONDS, TimeUnit.SECONDS);
        }
    }

    private void refreshIfNeeded() {
        synchronized (this) {
            if (server == null || consumed || !pairingActive) return;
            rotatePairCode();
            plugin.getLogger().info("Agent Link local setup endpoint refreshed (expires at "
                    + Instant.ofEpochMilli(pairExpiresAtMs) + "): " + setupLink());
        }
        scheduleRefresh();
    }

    private CallTier resolveTier(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String token = authorization.substring(7).trim();
        TokenStore.Entry entry = tokens.lookup(token);
        if (entry != null) return entry.tier() == TokenStore.Tier.CONSOLE ? CallTier.CONSOLE : CallTier.GUEST;
        return constantTimeEquals(token, config.masterToken()) ? CallTier.GUEST : null;
    }

    private boolean originAllowed(String origin) {
        String value = origin == null ? "null" : origin;
        for (String allowed : config.allowedOrigins()) {
            if ("*".equals(allowed) || value.equalsIgnoreCase(allowed)) return true;
        }
        return false;
    }

    private static boolean acceptsJson(String accept) {
        for (String part : accept.split(",")) {
            String type = part.trim().split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (JSON_ACCEPT.contains(type)) return true;
        }
        return false;
    }

    private static boolean contains(List<String> values, String target) {
        for (String value : values) if (constantTimeEquals(value, target)) return true;
        return false;
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null || left.length() != right.length()) return false;
        int diff = 0;
        for (int i = 0; i < left.length(); i++) diff |= left.charAt(i) ^ right.charAt(i);
        return diff == 0;
    }

    private static String firstHeader(HttpExchange exchange, String name) {
        List<String> values = exchange.getRequestHeaders().get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static byte[] readBody(InputStream stream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = stream.read(buffer)) != -1) {
            total += read;
            if (total > MAX_BODY_BYTES) return null;
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void writeStatus(HttpExchange exchange, int status, String message) throws IOException {
        addNoStoreHeaders(exchange);
        byte[] body = message == null ? new byte[0] : message.getBytes(StandardCharsets.UTF_8);
        if (body.length > 0) exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        if (body.length > 0) try (var output = exchange.getResponseBody()) { output.write(body); }
    }

    private static void writeJson(HttpExchange exchange, int status, JsonObject json) throws IOException {
        addNoStoreHeaders(exchange);
        byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) { output.write(body); }
    }

    private static void addNoStoreHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Cache-Control", "no-store, no-cache, must-revalidate");
        exchange.getResponseHeaders().set("Pragma", "no-cache");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
    }

    public record SetupLink(String link, long expiresAtMs) {}
    private record PairResult(JsonObject result, String reason) {}
}

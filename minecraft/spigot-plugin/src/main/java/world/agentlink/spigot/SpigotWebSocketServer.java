package world.agentlink.spigot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** v0 WebSocket transport retained for existing agent-link clients. */
public final class SpigotWebSocketServer extends WebSocketServer {
    private static final Gson GSON = new Gson();

    private final JavaPlugin plugin;
    private final SpigotConfig.Snapshot config;
    private final SpigotDispatcher dispatcher;
    private final TokenStore tokens;
    private final EventBuffer events;
    private final Map<WebSocket, ClientSession> sessions = new ConcurrentHashMap<>();

    public SpigotWebSocketServer(JavaPlugin plugin, SpigotConfig.Snapshot config,
                                 SpigotDispatcher dispatcher, TokenStore tokens, EventBuffer events) {
        super(new InetSocketAddress(config.allowRemote() ? "0.0.0.0" : "127.0.0.1", config.listenPort()));
        this.plugin = plugin;
        this.config = config;
        this.dispatcher = dispatcher;
        this.tokens = tokens;
        this.events = events;
        setReuseAddr(true);
    }

    public void shutdown() throws InterruptedException {
        stop(2000);
    }

    @Override
    public void onOpen(WebSocket connection, ClientHandshake handshake) {
        sessions.put(connection, new ClientSession(connection));
        plugin.getLogger().fine("Agent Link WebSocket opened: " + connection.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket connection, int code, String reason, boolean remote) {
        ClientSession session = sessions.remove(connection);
        events.remove(session);
        plugin.getLogger().fine("Agent Link WebSocket closed: " + reason);
    }

    @Override
    public void onMessage(WebSocket connection, String message) {
        ClientSession session = sessions.get(connection);
        if (session == null) {
            connection.close(1011, "no session");
            return;
        }
        JsonObject frame;
        try {
            frame = GSON.fromJson(message, JsonObject.class);
        } catch (Exception e) {
            sendError(connection, null, "INVALID_ARGS", "Frame is not valid JSON");
            return;
        }
        if (frame == null) {
            sendError(connection, null, "INVALID_ARGS", "Empty frame");
            return;
        }
        int version = frame.has("v") ? frame.get("v").getAsInt() : 0;
        if (version != 0) {
            sendError(connection, frame.has("id") ? frame.get("id").getAsString() : null,
                    "UNSUPPORTED_VERSION", "Server speaks v0");
            return;
        }
        String type = frame.has("type") ? frame.get("type").getAsString() : "";
        if ("hello".equals(type)) {
            handleHello(session, frame);
        } else if ("request".equals(type)) {
            if (!session.authenticated()) {
                sendError(connection, frame.has("id") ? frame.get("id").getAsString() : null,
                        "UNAUTHENTICATED", "Send hello first");
            } else {
                dispatcher.dispatchWebSocket(session, frame);
            }
        } else {
            sendError(connection, frame.has("id") ? frame.get("id").getAsString() : null,
                    "INVALID_ARGS", "Unknown frame type: " + type);
        }
    }

    private void handleHello(ClientSession session, JsonObject frame) {
        String token = frame.has("token") ? frame.get("token").getAsString() : "";
        TokenStore.Entry entry = tokens.lookup(token);
        CallTier tier = entry == null && constantTimeEquals(token, config.masterToken())
                ? CallTier.GUEST : entry == null ? null
                : entry.tier() == TokenStore.Tier.CONSOLE ? CallTier.CONSOLE : CallTier.GUEST;
        if (tier == null) {
            session.send("{\"v\":0,\"type\":\"response\",\"ok\":false,\"error\":{\"code\":\"INVALID_TOKEN\",\"message\":\"Bad token\"}}");
            session.connection().close(4401, "INVALID_TOKEN");
            return;
        }
        session.setTier(tier);
        session.authenticate();
        JsonObject welcome = new JsonObject();
        welcome.addProperty("v", 0);
        welcome.addProperty("type", "welcome");
        JsonObject server = new JsonObject();
        server.addProperty("mc_version", Bukkit.getVersion());
        server.addProperty("loader", "spigot");
        server.addProperty("agent_link_version", config.version());
        server.addProperty("token_tier", tier.name().toLowerCase(java.util.Locale.ROOT));
        welcome.add("server", server);
        session.send(GSON.toJson(welcome));
    }

    @Override
    public void onError(WebSocket connection, Exception exception) {
        plugin.getLogger().warning("Agent Link WebSocket error: " + exception.getMessage());
    }

    @Override
    public void onStart() {
        setConnectionLostTimeout(60);
    }

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null || left.length() != right.length()) return false;
        int diff = 0;
        for (int i = 0; i < left.length(); i++) diff |= left.charAt(i) ^ right.charAt(i);
        return diff == 0;
    }

    private static void sendError(WebSocket connection, String id, String code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("v", 0);
        error.addProperty("type", "response");
        if (id != null) error.addProperty("id", id);
        error.addProperty("ok", false);
        JsonObject body = new JsonObject();
        body.addProperty("code", code);
        body.addProperty("message", message);
        error.add("error", body);
        if (connection.isOpen()) connection.send(GSON.toJson(error));
    }
}

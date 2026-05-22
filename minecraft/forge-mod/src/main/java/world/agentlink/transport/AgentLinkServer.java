package world.agentlink.transport;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import world.agentlink.AgentLinkMod;
import world.agentlink.config.AgentLinkConfig.Snapshot;
import world.agentlink.dispatch.RequestDispatcher;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AgentLinkServer extends WebSocketServer {

    private static final Gson GSON = new Gson();

    private final MinecraftServer mc;
    private final Snapshot cfg;
    private final RequestDispatcher dispatcher;
    private final Map<WebSocket, ClientSession> sessions = new ConcurrentHashMap<>();

    public AgentLinkServer(MinecraftServer mc, Snapshot cfg) {
        super(new InetSocketAddress(cfg.allowRemote() ? "0.0.0.0" : "127.0.0.1", cfg.listenPort()));
        this.mc = mc;
        this.cfg = cfg;
        this.dispatcher = new RequestDispatcher(mc);
        setReuseAddr(true);
    }

    public void shutdown() throws InterruptedException {
        stop(2000);
    }

    public java.util.Collection<ClientSession> sessions() {
        return sessions.values();
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        sessions.put(conn, new ClientSession(conn));
        AgentLinkMod.LOG.debug("agent-link: connection opened from {}", conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        sessions.remove(conn);
        AgentLinkMod.LOG.debug("agent-link: connection closed ({}): {}", code, reason);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        ClientSession session = sessions.get(conn);
        if (session == null) {
            conn.close(1011, "no session");
            return;
        }

        JsonObject frame;
        try {
            frame = GSON.fromJson(message, JsonObject.class);
        } catch (Exception e) {
            sendError(conn, null, "INVALID_ARGS", "Frame is not valid JSON");
            return;
        }
        if (frame == null) {
            sendError(conn, null, "INVALID_ARGS", "Empty frame");
            return;
        }

        int v = frame.has("v") ? frame.get("v").getAsInt() : 0;
        if (v != 0) {
            sendError(conn, frame.has("id") ? frame.get("id").getAsString() : null,
                    "UNSUPPORTED_VERSION", "Server speaks v0");
            return;
        }

        String type = frame.has("type") ? frame.get("type").getAsString() : "";
        switch (type) {
            case "hello" -> handleHello(session, frame);
            case "request" -> handleRequest(session, frame);
            default -> sendError(conn, frame.has("id") ? frame.get("id").getAsString() : null,
                    "INVALID_ARGS", "Unknown frame type: " + type);
        }
    }

    private void handleHello(ClientSession session, JsonObject frame) {
        String token = frame.has("token") ? frame.get("token").getAsString() : "";
        if (!token.equals(cfg.token())) {
            session.send("{\"v\":0,\"type\":\"response\",\"ok\":false,\"error\":{\"code\":\"INVALID_TOKEN\",\"message\":\"Bad token\"}}");
            session.conn().close(4401, "INVALID_TOKEN");
            return;
        }
        session.markAuthenticated();

        JsonObject welcome = new JsonObject();
        welcome.addProperty("v", 0);
        welcome.addProperty("type", "welcome");
        JsonObject server = new JsonObject();
        server.addProperty("mc_version", mc.getServerVersion());
        server.addProperty("loader", "forge");
        server.addProperty("loader_version", System.getProperty("forge.version", "unknown"));
        server.addProperty("agent_link_version", cfg.version());
        welcome.add("server", server);
        session.send(GSON.toJson(welcome));
    }

    private void handleRequest(ClientSession session, JsonObject frame) {
        if (!session.isAuthenticated()) {
            sendError(session.conn(),
                    frame.has("id") ? frame.get("id").getAsString() : null,
                    "UNAUTHENTICATED", "Send hello first");
            return;
        }
        dispatcher.dispatch(session, frame);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        AgentLinkMod.LOG.warn("agent-link: socket error", ex);
    }

    @Override
    public void onStart() {
        setConnectionLostTimeout(60);
    }

    private void sendError(WebSocket conn, String id, String code, String message) {
        JsonObject err = new JsonObject();
        err.addProperty("v", 0);
        err.addProperty("type", "response");
        if (id != null) err.addProperty("id", id);
        err.addProperty("ok", false);
        JsonObject body = new JsonObject();
        body.addProperty("code", code);
        body.addProperty("message", message);
        err.add("error", body);
        conn.send(GSON.toJson(err));
    }
}

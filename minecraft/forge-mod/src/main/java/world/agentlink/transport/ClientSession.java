package world.agentlink.transport;

import org.java_websocket.WebSocket;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ClientSession {
    private final WebSocket conn;
    private volatile boolean authenticated;
    private final Set<String> subscriptions = ConcurrentHashMap.newKeySet();

    public ClientSession(WebSocket conn) {
        this.conn = conn;
    }

    public WebSocket conn() {
        return conn;
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    public void markAuthenticated() {
        this.authenticated = true;
    }

    public Set<String> subscriptions() {
        return subscriptions;
    }

    public void send(String json) {
        if (conn.isOpen()) conn.send(json);
    }
}

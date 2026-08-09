package world.agentlink.spigot;

import org.java_websocket.WebSocket;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Per-WebSocket state. MCP HTTP remains stateless by design. */
public final class ClientSession {
    private final WebSocket connection;
    private final Set<String> subscriptions = ConcurrentHashMap.newKeySet();
    private volatile boolean authenticated;
    private volatile CallTier tier = CallTier.GUEST;

    public ClientSession(WebSocket connection) {
        this.connection = connection;
    }

    public WebSocket connection() {
        return connection;
    }

    public boolean authenticated() {
        return authenticated;
    }

    public void authenticate() {
        authenticated = true;
    }

    public CallTier tier() {
        return tier;
    }

    public void setTier(CallTier tier) {
        this.tier = tier == null ? CallTier.GUEST : tier;
    }

    public Set<String> subscriptions() {
        return subscriptions;
    }

    public void send(String message) {
        if (connection.isOpen()) {
            connection.send(message);
        }
    }
}

package world.agentlink.spigot;

/** Structured error returned to an MCP or WebSocket client. */
public final class ToolException extends Exception {
    private final String code;

    public ToolException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}

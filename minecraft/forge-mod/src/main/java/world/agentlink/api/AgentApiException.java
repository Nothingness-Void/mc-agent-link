package world.agentlink.api;

/** Structured failure returned by a public agent-link API operation. */
public class AgentApiException extends Exception {
    private final String code;

    public AgentApiException(String code, String message) {
        super(message);
        this.code = code == null || code.isBlank() ? "INTERNAL_ERROR" : code;
    }

    public AgentApiException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code == null || code.isBlank() ? "INTERNAL_ERROR" : code;
    }

    public String code() {
        return code;
    }
}

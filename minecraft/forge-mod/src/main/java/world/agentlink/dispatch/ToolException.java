package world.agentlink.dispatch;

public class ToolException extends Exception {
    private final String code;

    public ToolException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}

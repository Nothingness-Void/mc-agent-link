package world.agentlink.security;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Small, transport-independent redaction boundary for data exposed to guest agents. */
public final class SensitiveDataRedactor {
    private static final Pattern TOML_SECRET = Pattern.compile(
            "(?im)^(\\s*(?:token|secret|password|pair_code)\\s*=\\s*)[^#\\r\\n]+",
            Pattern.MULTILINE);
    private static final Pattern LOG_SETUP_LINK = Pattern.compile(
            "(?i)https?://(?:\\S*agent-link-setup=\\S+|\\S*/pair/setup/\\S+)");
    private static final Pattern LOG_TOKEN = Pattern.compile(
            "(?i)(agent-link(?: generated)? token\\s*:\\s*)\\S+");
    private static final Pattern LOG_PAIR_CODE = Pattern.compile(
            "(?i)(pair[_ -]?code\\s*[=:]\\s*)[A-Za-z0-9_-]+");
    private static final Set<String> IDENTITY_KEYS = Set.of("uuid", "name");

    private SensitiveDataRedactor() {}

    /** Redact credentials from a config text unless the caller has a console-tier token. */
    public static String config(String logicalName, String text, boolean privileged) {
        if (privileged || text == null) return text;
        String lower = logicalName == null ? "" : logicalName.toLowerCase(Locale.ROOT);
        String out = text;
        if (lower.contains("agent-link") || lower.endsWith(".toml")) {
            out = TOML_SECRET.matcher(out).replaceAll("$1\"<redacted>\"");
        }
        if (lower.equals("ops.json")) out = redactOpsJson(out);
        return out;
    }

    /** Redact setup links and credentials before a message enters the in-memory log ring. */
    public static String log(String message) {
        if (message == null || message.isEmpty()) return message;
        String out = LOG_SETUP_LINK.matcher(message).replaceAll("<redacted-setup-link>");
        out = LOG_TOKEN.matcher(out).replaceAll("$1<redacted-token>");
        return LOG_PAIR_CODE.matcher(out).replaceAll("$1<redacted-pair-code>");
    }

    private static String redactOpsJson(String text) {
        try {
            JsonElement parsed = JsonParser.parseString(text);
            redactIdentity(parsed);
            return parsed.toString();
        } catch (RuntimeException ignored) {
            // Keep malformed operator files inspectable without leaking obvious identity fields.
            return text.replaceAll("(?i)(\\\"(?:uuid|name)\\\"\\s*:\\s*)\\\"[^\\\"]*\\\"", "$1\"<redacted>\"");
        }
    }

    private static void redactIdentity(JsonElement element) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) redactIdentity(child);
            return;
        }
        if (!element.isJsonObject()) return;
        JsonObject object = element.getAsJsonObject();
        for (String key : IDENTITY_KEYS) {
            if (object.has(key)) object.addProperty(key, "<redacted>");
        }
        for (JsonElement child : object.entrySet().stream().map(java.util.Map.Entry::getValue).toList()) {
            redactIdentity(child);
        }
    }
}

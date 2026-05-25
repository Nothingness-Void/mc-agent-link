package world.agentlink.approval;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A trust entry persisted under {@code approval.trusted_tools}.
 *
 * <p>Two forms accepted:
 * <ul>
 *   <li>{@code "tool_name"} — every call to this tool is auto-approved (legacy form).</li>
 *   <li>{@code "tool_name(arg=glob)"} — only calls whose JSON arg {@code arg} matches the glob
 *       are auto-approved. {@code *} matches any run of characters; {@code ?} matches one.
 *       Example: {@code run_console_command(command=say *)} auto-approves any {@code /say ...}
 *       but still prompts for {@code /op} or {@code /stop}.</li>
 * </ul>
 *
 * <p>Inputs are normalized to lower-case for the tool name and arg key. The glob value is
 * matched case-sensitively (Minecraft commands are conventionally lower-case but arguments
 * can be mixed-case player names, paths etc.). Keep the rule string canonical so equality and
 * deduplication work via {@link #toString()}.
 */
public final class TrustRule {

    private final String tool;
    private final String paramKey;
    private final String paramGlob;
    private final Pattern compiled;

    private TrustRule(String tool, String paramKey, String paramGlob) {
        this.tool = tool;
        this.paramKey = paramKey;
        this.paramGlob = paramGlob;
        this.compiled = paramGlob == null ? null : compileGlob(paramGlob);
    }

    /** Whole-tool trust, equivalent to the legacy "tool_name" form. */
    public static TrustRule whole(String tool) {
        return new TrustRule(normalize(tool), null, null);
    }

    /** Parameter-scoped trust. {@code paramGlob} uses {@code *} / {@code ?} wildcards. */
    public static TrustRule parameterized(String tool, String paramKey, String paramGlob) {
        if (paramKey == null || paramKey.isBlank()) return whole(tool);
        if (paramGlob == null) paramGlob = "*";
        return new TrustRule(normalize(tool), normalize(paramKey), paramGlob);
    }

    /** Parses one entry from {@code approval.trusted_tools}. Returns null on garbage. */
    public static TrustRule parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        int open = s.indexOf('(');
        if (open < 0) {
            return whole(s);
        }
        if (!s.endsWith(")")) return null;
        String tool = s.substring(0, open).trim();
        if (tool.isEmpty()) return null;
        String inside = s.substring(open + 1, s.length() - 1).trim();
        int eq = inside.indexOf('=');
        if (eq <= 0) return null;
        String key = inside.substring(0, eq).trim();
        String glob = inside.substring(eq + 1);
        // Don't trim the glob value: trailing/leading spaces in command args are legitimate
        // (e.g. "say  ..." vs "say ...").
        if (key.isEmpty() || glob.isEmpty()) return null;
        return parameterized(tool, key, glob);
    }

    public String tool() {
        return tool;
    }

    public boolean isParameterized() {
        return paramKey != null;
    }

    public boolean matches(String normalizedTool, JsonObject args) {
        if (!tool.equals(normalizedTool)) return false;
        if (paramKey == null) return true;
        if (args == null) return false;
        JsonElement el = args.get(paramKey);
        if (el == null || el.isJsonNull()) return false;
        String value;
        if (el.isJsonPrimitive()) {
            value = el.getAsString();
        } else {
            value = el.toString();
        }
        return compiled.matcher(value).matches();
    }

    @Override
    public String toString() {
        if (paramKey == null) return tool;
        return tool + "(" + paramKey + "=" + paramGlob + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TrustRule other)) return false;
        return Objects.equals(tool, other.tool)
                && Objects.equals(paramKey, other.paramKey)
                && Objects.equals(paramGlob, other.paramGlob);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tool, paramKey, paramGlob);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /** glob → regex: {@code *} = {@code .*}, {@code ?} = {@code .}, everything else escaped. */
    private static Pattern compileGlob(String glob) {
        StringBuilder sb = new StringBuilder(glob.length() + 8);
        sb.append('^');
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                case '.', '\\', '+', '(', ')', '[', ']', '{', '}', '|', '^', '$' ->
                        sb.append('\\').append(c);
                default -> sb.append(c);
            }
        }
        sb.append('$');
        return Pattern.compile(sb.toString(), Pattern.DOTALL);
    }
}

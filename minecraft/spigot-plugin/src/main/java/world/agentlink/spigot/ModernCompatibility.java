package world.agentlink.spigot;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runtime platform information used by agents to adapt to modern Bukkit servers. */
public final class ModernCompatibility {
    public static final String SUPPORTED_RANGE = "1.20+";
    private static final Version MINIMUM_VERSION = new Version(1, 20, 0);
    private static final Pattern VERSION_PATTERN = Pattern.compile("(?<!\\d)(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    private ModernCompatibility() {}

    public static RuntimeInfo detect() {
        String bukkitVersion = safeBukkitVersion();
        String serverVersion = safeServerVersion();
        Version version = parseMinecraftVersion(bukkitVersion);
        if (version == null) version = parseMinecraftVersion(serverVersion);

        String status;
        if (version == null) {
            status = "unknown";
        } else if (version.compareTo(MINIMUM_VERSION) >= 0) {
            status = "supported";
        } else {
            status = "unsupported";
        }
        return new RuntimeInfo(
                safeServerName(),
                bukkitVersion,
                serverVersion,
                version,
                status,
                SUPPORTED_RANGE
        );
    }

    public static Version parseMinecraftVersion(String value) {
        if (value == null || value.isBlank()) return null;
        Matcher matcher = VERSION_PATTERN.matcher(value);
        if (!matcher.find()) return null;
        return new Version(
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3))
        );
    }

    private static String safeBukkitVersion() {
        try {
            return Bukkit.getBukkitVersion();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String safeServerVersion() {
        try {
            return Bukkit.getVersion();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String safeServerName() {
        try {
            return Bukkit.getName();
        } catch (RuntimeException ignored) {
            return "Bukkit";
        }
    }

    public record Version(int major, int minor, int patch) implements Comparable<Version> {
        @Override
        public int compareTo(Version other) {
            int result = Integer.compare(major, other.major);
            if (result != 0) return result;
            result = Integer.compare(minor, other.minor);
            if (result != 0) return result;
            return Integer.compare(patch, other.patch);
        }

        @Override
        public String toString() {
            return major + "." + minor + "." + patch;
        }
    }

    public record RuntimeInfo(
            String serverName,
            String bukkitVersion,
            String serverVersion,
            Version minecraftVersion,
            String status,
            String supportedRange
    ) {
        public boolean supported() {
            return "supported".equals(status);
        }

        public boolean known() {
            return minecraftVersion != null;
        }

        public String summary() {
            String version = minecraftVersion == null ? "unknown" : minecraftVersion.toString();
            return serverName + " " + version + " (support=" + status + ", range=" + supportedRange + ")";
        }

        public JsonObject toJson() {
            JsonObject result = new JsonObject();
            result.addProperty("server_name", serverName);
            result.addProperty("bukkit_version", bukkitVersion);
            result.addProperty("server_version", serverVersion);
            result.addProperty("minecraft_version", minecraftVersion == null ? "" : minecraftVersion.toString());
            result.addProperty("status", status);
            result.addProperty("supported_range", supportedRange);
            result.addProperty("nms_free", true);
            result.addProperty("spigot_paper_compatible", true);
            result.addProperty("api_line", "Bukkit 1.20+");
            result.addProperty("java_baseline", "17");
            return result;
        }
    }
}

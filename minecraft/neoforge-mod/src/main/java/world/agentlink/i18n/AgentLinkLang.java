package world.agentlink.i18n;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.level.ServerPlayer;
import world.agentlink.AgentLinkMod;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.IllegalFormatException;
import java.util.Locale;
import java.util.Map;

public final class AgentLinkLang {
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();
    private static final String DEFAULT_LOCALE = "zh_cn";
    private static final Map<String, Map<String, String>> BUNDLES = loadBundles();

    private AgentLinkLang() {}

    public static String tr(String key, Object... args) {
        return tr(DEFAULT_LOCALE, key, args);
    }

    public static String tr(ServerPlayer player, String key, Object... args) {
        return tr(readLanguage(player), key, args);
    }

    public static String tr(String locale, String key, Object... args) {
        String pattern = lookup(normalizeLocale(locale), key);
        try {
            return String.format(Locale.ROOT, pattern, args);
        } catch (IllegalFormatException ex) {
            AgentLinkMod.LOG.warn("agent-link lang format failed for key {}: {}", key, ex.getMessage());
            return pattern;
        }
    }

    private static String lookup(String locale, String key) {
        Map<String, String> exact = BUNDLES.get(locale);
        if (exact != null && exact.containsKey(key)) return exact.get(key);
        Map<String, String> fallbackEn = BUNDLES.get("en_us");
        if (fallbackEn != null && fallbackEn.containsKey(key)) return fallbackEn.get(key);
        Map<String, String> fallbackDefault = BUNDLES.get(DEFAULT_LOCALE);
        if (fallbackDefault != null && fallbackDefault.containsKey(key)) return fallbackDefault.get(key);
        return key;
    }

    private static Map<String, Map<String, String>> loadBundles() {
        Map<String, Map<String, String>> out = new HashMap<>();
        out.put("zh_cn", loadBundle("zh_cn"));
        out.put("en_us", loadBundle("en_us"));
        return Collections.unmodifiableMap(out);
    }

    private static Map<String, String> loadBundle(String locale) {
        String path = "assets/agentlink/lang/" + locale + ".json";
        try (InputStream in = AgentLinkLang.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) return Map.of();
            try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                Map<String, String> map = GSON.fromJson(reader, MAP_TYPE);
                return map == null ? Map.of() : Map.copyOf(map);
            }
        } catch (Exception ex) {
            AgentLinkMod.LOG.warn("agent-link failed to load lang bundle {}", path, ex);
            return Map.of();
        }
    }

    private static String readLanguage(ServerPlayer player) {
        if (player == null) return DEFAULT_LOCALE;
        try {
            Method clientInformation = player.getClass().getMethod("clientInformation");
            Object info = clientInformation.invoke(player);
            Method language = info.getClass().getMethod("language");
            Object value = language.invoke(info);
            return value == null ? DEFAULT_LOCALE : value.toString();
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return DEFAULT_LOCALE;
        }
    }

    private static String normalizeLocale(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_LOCALE;
        String value = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if (value.startsWith("zh")) return "zh_cn";
        if (value.startsWith("en")) return "en_us";
        return DEFAULT_LOCALE;
    }
}

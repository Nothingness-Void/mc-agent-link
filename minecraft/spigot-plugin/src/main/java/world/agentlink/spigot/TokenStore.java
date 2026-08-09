package world.agentlink.spigot;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;

/** Persistent hash-only registry for per-pair bearer tokens. */
public final class TokenStore {
    public enum Tier { CONSOLE, GUEST }

    public record Entry(String hash, Tier tier, String label, long issuedAtMs, long lastUsedAtMs) {}

    private static final Gson GSON = new Gson();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 24;

    private final Path file;
    private final Map<String, Entry> entries = new HashMap<>();

    public TokenStore(Path dataFolder) {
        this.file = dataFolder.resolve("issued_tokens.json");
        load();
    }

    public static String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public synchronized Entry register(String rawToken, Tier tier, String label) {
        Entry entry = new Entry(hash(rawToken), tier == null ? Tier.GUEST : tier,
                label == null ? "" : label, System.currentTimeMillis(), 0L);
        entries.put(entry.hash(), entry);
        save();
        return entry;
    }

    public synchronized Entry lookup(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return null;
        String hash = hash(rawToken);
        Entry entry = entries.get(hash);
        if (entry == null) return null;
        Entry updated = new Entry(entry.hash(), entry.tier(), entry.label(), entry.issuedAtMs(),
                System.currentTimeMillis());
        entries.put(hash, updated);
        return updated;
    }

    public synchronized List<Entry> list() {
        return new ArrayList<>(entries.values());
    }

    /** Whether at least one persistent bearer token has been issued. */
    public synchronized boolean hasIssuedTokens() {
        return !entries.isEmpty();
    }

    public synchronized int revokeByPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) return 0;
        String normalized = prefix.trim().toLowerCase(Locale.ROOT);
        List<String> remove = entries.keySet().stream()
                .filter(hash -> hash.toLowerCase(Locale.ROOT).startsWith(normalized))
                .toList();
        remove.forEach(entries::remove);
        if (!remove.isEmpty()) save();
        return remove.size();
    }

    public static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private void load() {
        if (!Files.isRegularFile(file)) return;
        try {
            JsonElement root = com.google.gson.JsonParser.parseString(Files.readString(file));
            if (!root.isJsonObject() || !root.getAsJsonObject().has("entries")) return;
            for (JsonElement item : root.getAsJsonObject().getAsJsonArray("entries")) {
                if (!item.isJsonObject()) continue;
                JsonObject object = item.getAsJsonObject();
                String hash = string(object, "hash");
                if (hash == null || hash.isBlank()) continue;
                Tier tier;
                try {
                    tier = Tier.valueOf(string(object, "tier").toUpperCase(Locale.ROOT));
                } catch (Exception ignored) {
                    tier = Tier.GUEST;
                }
                entries.put(hash, new Entry(hash, tier, stringOrEmpty(object, "label"),
                        number(object, "issued_at_ms"), number(object, "last_used_at_ms")));
            }
        } catch (Exception ignored) {
            // A corrupt optional registry must not prevent the server from starting.
        }
    }

    private synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            JsonArray array = new JsonArray();
            for (Entry entry : entries.values()) {
                JsonObject object = new JsonObject();
                object.addProperty("hash", entry.hash());
                object.addProperty("tier", entry.tier().name().toLowerCase(Locale.ROOT));
                object.addProperty("label", entry.label());
                object.addProperty("issued_at_ms", entry.issuedAtMs());
                object.addProperty("issued_at_iso", Instant.ofEpochMilli(entry.issuedAtMs()).toString());
                object.addProperty("last_used_at_ms", entry.lastUsedAtMs());
                array.add(object);
            }
            JsonObject root = new JsonObject();
            root.addProperty("v", 1);
            root.add("entries", array);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
            // Token plaintext is not lost; the active token remains in memory for this run.
        }
    }

    private static String string(JsonObject object, String key) {
        try {
            return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String stringOrEmpty(JsonObject object, String key) {
        String value = string(object, key);
        return value == null ? "" : value;
    }

    private static long number(JsonObject object, String key) {
        try {
            return object.has(key) ? object.get(key).getAsLong() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }
}

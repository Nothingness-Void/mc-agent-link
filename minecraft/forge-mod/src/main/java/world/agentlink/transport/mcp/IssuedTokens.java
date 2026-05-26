package world.agentlink.transport.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Persistent registry of issued bearer tokens.
 *
 * <p>Each token has a {@link Tier} that determines whether it skips the in-game approval flow:
 * <ul>
 *   <li>{@link Tier#CONSOLE} — issued via {@code /agentlink pair} by an OP with terminal access.
 *       The operator can supervise the agent in real time, so in-game approval is redundant; tools
 *       run immediately.</li>
 *   <li>{@link Tier#GUEST} — issued via {@code /agentlink pair-guest} or inherited by addon mods
 *       that read the master token. Approval is enforced as before.</li>
 * </ul>
 *
 * <p>Storage: {@code config/agent-link/issued_tokens.json}. Each entry stores a SHA-256 hash of
 * the raw token (so a leak of the file does not give an attacker the bearer token), plus tier,
 * label, issued_at, and last_used_at timestamps. The legacy master token from
 * {@code agent-link.toml} is treated as an implicit GUEST entry — it is never written to this
 * file, but {@link #lookup(String)} falls back to it when no other entry matches.
 */
public final class IssuedTokens {

    public enum Tier {
        CONSOLE,
        GUEST;

        public static Tier parse(String s, Tier fallback) {
            if (s == null) return fallback;
            try {
                return Tier.valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return fallback;
            }
        }
    }

    public record Entry(String tokenHash, Tier tier, String label, long issuedAtMs, long lastUsedAtMs) {
        public Entry withLastUsed(long ts) {
            return new Entry(tokenHash, tier, label, issuedAtMs, ts);
        }
    }

    private static final Logger LOG = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static final String FILE_NAME = "agent-link/issued_tokens.json";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 24;

    private static volatile IssuedTokens CURRENT;

    private final Path file;
    private final Map<String, Entry> byHash = new HashMap<>();

    private IssuedTokens(Path file) {
        this.file = file;
    }

    public static synchronized IssuedTokens current() {
        if (CURRENT == null) {
            Path file = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
            IssuedTokens inst = new IssuedTokens(file);
            inst.load();
            CURRENT = inst;
        }
        return CURRENT;
    }

    /** For tests / re-init in case CONFIGDIR shifts between runs. */
    public static synchronized void resetForTesting() {
        CURRENT = null;
    }

    /** Generate a fresh random bearer token. The plaintext is returned to the caller exactly once. */
    public static String generateToken() {
        byte[] buf = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }

    /**
     * Register {@code rawToken} under the given tier. Returns the resulting {@link Entry}.
     * Existing entries with the same hash are replaced.
     */
    public synchronized Entry register(String rawToken, Tier tier, String label) {
        String hash = sha256(rawToken);
        Entry entry = new Entry(hash, tier, label == null ? "" : label,
                System.currentTimeMillis(), 0L);
        byHash.put(hash, entry);
        save();
        return entry;
    }

    /**
     * Look up the entry matching {@code rawToken}. Returns null when the token is unknown.
     * Updates the entry's last_used timestamp on hit.
     */
    public synchronized Entry lookup(String rawToken) {
        if (rawToken == null || rawToken.isEmpty()) return null;
        String hash = sha256(rawToken);
        Entry entry = byHash.get(hash);
        if (entry == null) return null;
        Entry updated = entry.withLastUsed(System.currentTimeMillis());
        byHash.put(hash, updated);
        // Don't save on every lookup — it's hot. Save on register / revoke instead. Last-used is a
        // best-effort field that gets persisted next time something else writes the file.
        return updated;
    }

    /** Revoke any entry whose hash starts with {@code hashPrefix} (case-insensitive). */
    public synchronized int revokeByPrefix(String hashPrefix) {
        if (hashPrefix == null || hashPrefix.isBlank()) return 0;
        String prefix = hashPrefix.trim().toLowerCase(Locale.ROOT);
        List<String> kill = new ArrayList<>();
        for (String h : byHash.keySet()) {
            if (h.toLowerCase(Locale.ROOT).startsWith(prefix)) kill.add(h);
        }
        for (String h : kill) byHash.remove(h);
        if (!kill.isEmpty()) save();
        return kill.size();
    }

    /** Snapshot of currently-known entries. Plaintext tokens are NOT recoverable. */
    public synchronized List<Entry> list() {
        return new ArrayList<>(byHash.values());
    }

    /** Total entries, primarily for {@code /agentlink} status output. */
    public synchronized int size() {
        return byHash.size();
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            JsonElement el = JsonParser.parseString(text);
            if (!el.isJsonObject()) return;
            JsonElement arr = el.getAsJsonObject().get("entries");
            if (arr == null || !arr.isJsonArray()) return;
            for (JsonElement item : arr.getAsJsonArray()) {
                if (!item.isJsonObject()) continue;
                JsonObject o = item.getAsJsonObject();
                String hash = optString(o, "hash");
                if (hash == null || hash.isEmpty()) continue;
                Tier tier = Tier.parse(optString(o, "tier"), Tier.GUEST);
                String label = optString(o, "label");
                long issued = optLong(o, "issued_at_ms");
                long lastUsed = optLong(o, "last_used_at_ms");
                byHash.put(hash, new Entry(hash, tier, label == null ? "" : label, issued, lastUsed));
            }
        } catch (Exception e) {
            LOG.warn("agent-link: failed to load issued_tokens.json: {}", e.getMessage());
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            JsonArray arr = new JsonArray();
            List<Entry> snapshot = new ArrayList<>(byHash.values());
            Collections.sort(snapshot, (a, b) -> Long.compare(a.issuedAtMs(), b.issuedAtMs()));
            for (Entry e : snapshot) {
                JsonObject o = new JsonObject();
                o.addProperty("hash", e.tokenHash());
                o.addProperty("tier", e.tier().name().toLowerCase(Locale.ROOT));
                o.addProperty("label", e.label() == null ? "" : e.label());
                o.addProperty("issued_at_ms", e.issuedAtMs());
                o.addProperty("issued_at_iso", Instant.ofEpochMilli(e.issuedAtMs()).toString());
                o.addProperty("last_used_at_ms", e.lastUsedAtMs());
                if (e.lastUsedAtMs() > 0) {
                    o.addProperty("last_used_at_iso", Instant.ofEpochMilli(e.lastUsedAtMs()).toString());
                }
                arr.add(o);
            }
            JsonObject root = new JsonObject();
            root.addProperty("v", 1);
            root.addProperty("note", "Token plaintexts are NOT stored. Each entry holds a SHA-256 hash and tier metadata.");
            root.add("entries", arr);
            Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
            Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.warn("agent-link: failed to save issued_tokens.json: {}", e.getMessage());
        }
    }

    private static String optString(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) return null;
        try {
            return o.get(key).getAsString();
        } catch (Exception ex) {
            return null;
        }
    }

    private static long optLong(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) return 0L;
        try {
            return o.get(key).getAsLong();
        } catch (Exception ex) {
            return 0L;
        }
    }

    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

package world.agentlink.config;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import net.minecraftforge.fml.loading.FMLPaths;
import world.agentlink.AgentLinkMod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Loaded once on mod init. Hand-rolled instead of Forge's ForgeConfigSpec because
 * the spec hooks come too late for the WebSocket port we want available the moment
 * the integrated/dedicated server is up.
 */
public final class AgentLinkConfig {

    public record Snapshot(
            int listenPort,
            boolean allowRemote,
            String token,
            List<String> writeAllow,
            List<String> writeDeny,
            boolean mcpEnabled,
            int mcpListenPort,
            List<String> mcpAllowedOrigins,
            boolean approvalEnabled,
            int approvalTimeoutSeconds,
            List<String> approvalAutoAllowTools,
            List<String> approvalTrustedTools,
            List<String> approvalAdminOnlyTools,
            List<UUID> roleAdminUuids,
            List<UUID> roleGuestUuids,
            boolean auditEnabled,
            List<String> auditRedactArgs,
            int auditMaxArgChars,
            String version
    ) {}

    private static final String FILE_NAME = "agent-link.toml";
    private static final List<String> DEFAULT_WRITE_ALLOW = List.of("config/**");
    private static final List<String> DEFAULT_WRITE_DENY = List.of();
    private static final List<String> DEFAULT_MCP_ALLOWED_ORIGINS =
            List.of("null", "http://localhost", "http://127.0.0.1");
    private static final List<String> DEFAULT_APPROVAL_AUTO_ALLOW_TOOLS = List.of(
            // Protocol / queue layer.
            "ping", "agent_heartbeat", "get_agent_requests",
            "update_agent_request_status", "reply_agent_request",
            // Read-only world / server state — safe for "ordinary OP" use.
            "list_online_players", "get_player_info", "get_player_inventory",
            "list_mods", "get_server_stats", "get_world_info",
            "get_block", "get_blocks_region", "get_biome",
            "raycast", "list_entities_near",
            "list_dimensions", "list_block_ids", "list_item_ids", "list_entity_ids", "list_biome_ids",
            // Tier-A read-only tools.
            "command", "find_players", "get_item_info", "get_recipes_for", "get_block_drops",
            // Tier-C read-only / read-config / snapshot.
            "read_config", "save_block_snapshot", "get_scoreboard",
            // WorldEdit read-only.
            "we_status",
            // Diagnostics / logs / events.
            "get_recent_events", "get_recent_logs", "subscribe_events", "unsubscribe_events",
            "tick_profile", "thread_dump",
            // Spark read-only.
            "spark_status", "spark_stats", "spark_health_report"
    );
    private static final List<String> DEFAULT_APPROVAL_ADMIN_ONLY_TOOLS = List.of(
            // High-impact server mutation.
            "run_console_command", "write_config_file", "broadcast",
            // Performance-impacting profiler control.
            "spark_profiler_start", "spark_profiler_stop", "spark_profiler_cancel",
            // Sensitive filesystem access (can read agent-link.toml token, ops.json, world data).
            "read_server_file", "list_dir",
            // Container peek bypasses the open animation; treat as snooping and require admin.
            "get_container",
            // WorldEdit mutating operations (each call can flip thousands of blocks).
            "we_set", "we_replace", "we_sphere", "we_cyl", "we_undo"
    );
    private static final List<String> DEFAULT_AUDIT_REDACT_ARGS = List.of(
            // Console payload often contains tokens, op-set commands, /seed output, etc.
            "run_console_command.command",
            // write_config_file content can be a key file or anything
            "write_config_file.content",
            "write_config_file.base64",
            // read_config returns file content — masked at record time, but list it for traceability.
            "read_config.content"
    );
    private static volatile Snapshot CURRENT;

    private AgentLinkConfig() {}

    public static Snapshot get() {
        return CURRENT;
    }

    public static void load() {
        Path path = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
        boolean fresh = !Files.exists(path);
        try (CommentedFileConfig cfg = CommentedFileConfig.builder(path)
                .preserveInsertionOrder()
                .build()) {
            cfg.load();

            int port = cfg.getIntOrElse("listen_port", 25580);
            boolean allowRemote = cfg.getOrElse("allow_remote", false);
            String token = cfg.getOrElse("token", "");

            List<String> writeAllow = readStringList(cfg, "write_allow", DEFAULT_WRITE_ALLOW);
            List<String> writeDeny = readStringList(cfg, "write_deny", DEFAULT_WRITE_DENY);

            boolean mcpEnabled = cfg.getOrElse("mcp_enabled", true);
            int mcpPort = cfg.getIntOrElse("mcp_listen_port", 25581);
            List<String> mcpAllowedOrigins = readStringList(cfg, "mcp_allowed_origins", DEFAULT_MCP_ALLOWED_ORIGINS);
            boolean approvalEnabled = cfg.getOrElse("approval.enabled", true);
            int approvalTimeoutSeconds = Math.max(5, cfg.getIntOrElse("approval.timeout_seconds", 60));
            List<String> approvalAutoAllowTools = readStringList(cfg, "approval.auto_allow_tools", DEFAULT_APPROVAL_AUTO_ALLOW_TOOLS);
            List<String> approvalTrustedTools = readStringList(cfg, "approval.trusted_tools", List.of());
            List<String> approvalAdminOnlyTools = readStringList(cfg, "approval.admin_only_tools", DEFAULT_APPROVAL_ADMIN_ONLY_TOOLS);
            List<UUID> roleAdminUuids = readUuidList(cfg, "roles.admin_uuids");
            List<UUID> roleGuestUuids = readUuidList(cfg, "roles.guest_uuids");

            boolean auditEnabled = cfg.getOrElse("audit.enabled", true);
            List<String> auditRedactArgs = readStringList(cfg, "audit.redact_args", DEFAULT_AUDIT_REDACT_ARGS);
            int auditMaxArgChars = Math.max(64, cfg.getIntOrElse("audit.max_arg_chars", 2000));

            if (token.isBlank()) {
                token = generateToken();
                cfg.set("token", token);
                cfg.setComment("token", " Shared secret. Anyone with this token can run console commands. Keep it safe.");
            }
            cfg.set("listen_port", port);
            cfg.setComment("listen_port", " WebSocket port. Default 25580.");
            cfg.set("allow_remote", allowRemote);
            cfg.setComment("allow_remote", " false = bind to 127.0.0.1 only. true = bind to 0.0.0.0 (LAN/internet). Use a firewall if true.");

            cfg.set("write_allow", writeAllow);
            cfg.setComment("write_allow",
                    "\n write_config_file glob allowlist. A path is writable only if it matches at least one of these.\n" +
                    " Patterns are evaluated relative to the server root.\n" +
                    " Glob syntax: ** (any segments), * (any chars within a segment), ? (one char).\n" +
                    " Default: [\"config/**\"] — only config/ is writable.");
            cfg.set("write_deny", writeDeny);
            cfg.setComment("write_deny",
                    "\n write_config_file glob denylist. Evaluated BEFORE write_allow — anything matched here is rejected\n" +
                    " regardless of write_allow. Use to carve out exceptions, e.g. [\"config/security/**\"].\n" +
                    " Default: [] (nothing extra denied).");

            cfg.set("mcp_enabled", mcpEnabled);
            cfg.setComment("mcp_enabled",
                    "\n MCP HTTP transport. Lets MCP hosts (Claude Code, Cursor, ...) connect directly without the Node bridge.\n" +
                    " Default: true. Set to false if you only use the WebSocket transport.");
            cfg.set("mcp_listen_port", mcpPort);
            cfg.setComment("mcp_listen_port",
                    "\n Port for the MCP HTTP endpoint (POST /mcp). Default 25581.\n" +
                    " Binds the same host as listen_port (127.0.0.1 unless allow_remote is true).");
            cfg.set("mcp_allowed_origins", mcpAllowedOrigins);
            cfg.setComment("mcp_allowed_origins",
                    "\n Origin header allowlist for MCP HTTP. Browsers send Origin; native MCP hosts usually do not (or send \"null\").\n" +
                    " Default: [\"null\", \"http://localhost\", \"http://127.0.0.1\"] — safe for local hosts.\n" +
                    " If allow_remote = true, narrow this to your trusted clients to prevent DNS-rebinding attacks.");

            cfg.set("approval.enabled", approvalEnabled);
            cfg.setComment("approval.enabled", " In-game MCP tool approval. When true, non-auto-allowed tools wait for in-game chat approval.");
            cfg.set("approval.timeout_seconds", approvalTimeoutSeconds);
            cfg.setComment("approval.timeout_seconds", " Seconds before a pending in-game tool approval is denied automatically.");
            cfg.set("approval.auto_allow_tools", approvalAutoAllowTools);
            cfg.setComment("approval.auto_allow_tools",
                    "\n Tier 1: tools that bypass in-game approval entirely. Default = read-only world / server / log / spark-status\n" +
                    " inspection that ordinary OPs already have access to in vanilla. Add \"*\" only if you trust the MCP host fully.");
            cfg.set("approval.trusted_tools", approvalTrustedTools);
            cfg.setComment("approval.trusted_tools",
                    "\n Tier 2: rules trusted via the in-game [始终允许该工具] / [始终允许该命令] buttons.\n" +
                    " Two forms are accepted:\n" +
                    "   \"tool_name\"                    — every call to that tool is auto-approved.\n" +
                    "   \"tool_name(arg=glob)\"          — only calls whose JSON arg matches the glob are auto-approved.\n" +
                    " Glob: * = any chars, ? = one char. Example: \"run_console_command(command=say *)\".\n" +
                    " Auto-grown by the in-game buttons. Edit or clear this list to revoke.");
            cfg.set("approval.admin_only_tools", approvalAdminOnlyTools);
            cfg.setComment("approval.admin_only_tools",
                    "\n Tier 4: tools that ONLY [roles].admin_uuids may even REQUEST. A non-admin invocation is rejected immediately\n" +
                    " (no approval prompt sent). Default covers anything that mutates the server, runs console commands, or reads sensitive\n" +
                    " filesystem state (read_server_file / list_dir can leak ops.json or this very token file).\n" +
                    " Tools NOT in any of these three lists fall through to Tier 3: still gated by an in-game approval prompt that any\n" +
                    " admin / OP can click.");

            cfg.set("roles.admin_uuids", uuidsAsStrings(roleAdminUuids));
            cfg.setComment("roles.admin_uuids",
                    "\n Server admins (\"腐竹\"). Player UUIDs listed here are the only ones who can click [允许一次]/[拒绝]\n" +
                    " on in-game MCP tool approval prompts AND the only ones who may invoke approval.admin_only_tools.\n" +
                    " Empty list = fall back to ALL online OPs (legacy behavior; admin_only_tools cannot be enforced and will be denied for everyone).");
            cfg.set("roles.guest_uuids", uuidsAsStrings(roleGuestUuids));
            cfg.setComment("roles.guest_uuids",
                    "\n Explicit guests. Optional — when empty, anyone NOT in admin_uuids is treated as guest by add-on mods.\n" +
                    " Use this list to mark specific players as \"chat-only\", e.g. for trusted but non-admin testers.");

            cfg.set("audit.enabled", auditEnabled);
            cfg.setComment("audit.enabled",
                    "\n Per-call audit log. When true, every MCP tool invocation appends a JSONL line to logs/agentlink-audit.log\n" +
                    " (one JSON object per line). Includes timestamp, tool, outcome (auto_allow / trusted / approved / denied / timeout),\n" +
                    " approver actor + UUID when applicable, the matching trust rule when applicable, and the call args (with secrets\n" +
                    " redacted per audit.redact_args). Read with /agent audit tail.");
            cfg.set("audit.redact_args", auditRedactArgs);
            cfg.setComment("audit.redact_args",
                    "\n Args / result keys whose VALUES are replaced with {redacted=true,length=...} before being written to the audit\n" +
                    " log. Format: \"tool.argkey\". Default redacts run_console_command.command, write_config_file.content/base64, and\n" +
                    " read_config returned content — anything that can carry tokens, OP-set commands, or full file dumps. Add tool.argkey\n" +
                    " entries here for any addon tool whose arg is sensitive.");
            cfg.set("audit.max_arg_chars", auditMaxArgChars);
            cfg.setComment("audit.max_arg_chars",
                    "\n Hard truncation cap on the per-line args JSON in the audit log. Anything longer is truncated and a\n" +
                    " {truncated_at=N} marker is appended. Default 2000.");

            cfg.save();
            CURRENT = new Snapshot(port, allowRemote, token, writeAllow, writeDeny,
                    mcpEnabled, mcpPort, mcpAllowedOrigins,
                    approvalEnabled, approvalTimeoutSeconds, approvalAutoAllowTools, approvalTrustedTools,
                    java.util.Collections.unmodifiableList(approvalAdminOnlyTools),
                    java.util.Collections.unmodifiableList(roleAdminUuids),
                    java.util.Collections.unmodifiableList(roleGuestUuids),
                    auditEnabled,
                    java.util.Collections.unmodifiableList(auditRedactArgs),
                    auditMaxArgChars,
                    "0.4.0-alpha");

            if (fresh) {
                AgentLinkMod.LOG.info("agent-link wrote default config to {}", path);
                AgentLinkMod.LOG.info("agent-link generated token: {}", token);
            }
        }
    }

    private static List<String> readStringList(CommentedFileConfig cfg, String key, List<String> fallback) {
        Object raw = cfg.get(key);
        if (raw == null) return fallback;
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        AgentLinkMod.LOG.warn("agent-link: config key '{}' is not a list; using default", key);
        return fallback;
    }

    private static List<UUID> readUuidList(CommentedFileConfig cfg, String key) {
        Object raw = cfg.get(key);
        if (raw == null) return new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            AgentLinkMod.LOG.warn("agent-link: config key '{}' is not a list; ignoring", key);
            return new ArrayList<>();
        }
        List<UUID> out = new ArrayList<>();
        for (Object item : list) {
            if (item == null) continue;
            String text = String.valueOf(item).trim();
            if (text.isEmpty()) continue;
            try {
                UUID u = UUID.fromString(text);
                if (!out.contains(u)) out.add(u);
            } catch (IllegalArgumentException ex) {
                AgentLinkMod.LOG.warn("agent-link: ignoring invalid UUID in {}: {}", key, text);
            }
        }
        return out;
    }

    private static List<String> uuidsAsStrings(List<UUID> uuids) {
        List<String> out = new ArrayList<>(uuids.size());
        for (UUID u : uuids) out.add(u.toString());
        return out;
    }

    public static synchronized void addApprovalTrustedTool(String ruleString) {
        if (ruleString == null || ruleString.isBlank()) return;
        Path path = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
        try (CommentedFileConfig cfg = CommentedFileConfig.builder(path)
                .preserveInsertionOrder()
                .build()) {
            cfg.load();
            List<String> trusted = readStringList(cfg, "approval.trusted_tools", List.of());
            if (!trusted.contains(ruleString)) {
                trusted = new java.util.ArrayList<>(trusted);
                trusted.add(ruleString);
                cfg.set("approval.trusted_tools", trusted);
                cfg.save();
            }
            Snapshot snap = CURRENT;
            if (snap != null) {
                CURRENT = new Snapshot(snap.listenPort(), snap.allowRemote(), snap.token(),
                        snap.writeAllow(), snap.writeDeny(), snap.mcpEnabled(),
                        snap.mcpListenPort(), snap.mcpAllowedOrigins(), snap.approvalEnabled(),
                        snap.approvalTimeoutSeconds(), snap.approvalAutoAllowTools(), trusted,
                        snap.approvalAdminOnlyTools(),
                        snap.roleAdminUuids(), snap.roleGuestUuids(),
                        snap.auditEnabled(), snap.auditRedactArgs(), snap.auditMaxArgChars(),
                        snap.version());
            }
        }
    }

    public static synchronized boolean removeApprovalTrustedTool(String ruleString) {
        if (ruleString == null || ruleString.isBlank()) return false;
        Path path = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
        boolean removed;
        List<String> trusted;
        try (CommentedFileConfig cfg = CommentedFileConfig.builder(path)
                .preserveInsertionOrder()
                .build()) {
            cfg.load();
            trusted = new java.util.ArrayList<>(readStringList(cfg, "approval.trusted_tools", List.of()));
            removed = trusted.removeIf(s -> s.equalsIgnoreCase(ruleString));
            if (removed) {
                cfg.set("approval.trusted_tools", trusted);
                cfg.save();
            }
        }
        if (removed) {
            Snapshot snap = CURRENT;
            if (snap != null) {
                CURRENT = new Snapshot(snap.listenPort(), snap.allowRemote(), snap.token(),
                        snap.writeAllow(), snap.writeDeny(), snap.mcpEnabled(),
                        snap.mcpListenPort(), snap.mcpAllowedOrigins(), snap.approvalEnabled(),
                        snap.approvalTimeoutSeconds(), snap.approvalAutoAllowTools(), trusted,
                        snap.approvalAdminOnlyTools(),
                        snap.roleAdminUuids(), snap.roleGuestUuids(),
                        snap.auditEnabled(), snap.auditRedactArgs(), snap.auditMaxArgChars(),
                        snap.version());
            }
        }
        return removed;
    }

    private static String generateToken() {
        byte[] buf = new byte[24];
        new SecureRandom().nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }
}

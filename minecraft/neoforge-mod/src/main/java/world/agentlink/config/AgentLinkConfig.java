package world.agentlink.config;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import net.neoforged.fml.loading.FMLPaths;
import world.agentlink.AgentLinkMod;
import world.agentlink.sandbox.BuildZones;

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
            String mcpPublicHost,
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
            List<BuildZones.Zone> buildZones,
            int taskMaxConcurrent,
            int taskBlocksPerTick,
            List<String> eventVerboseTopics,
            String version
    ) {}

    /** Single source of truth for the version string reported over MCP and in the setup endpoint. */
    public static final String VERSION = "0.5.0-alpha";

    /**
     * Schema generation of the approval tables. Bumped whenever a release adds tools that need to be
     * merged into an already-written {@code agent-link.toml} — see {@link #TIER_MIGRATIONS}.
     */
    private static final int APPROVAL_TABLE_VERSION = 3;

    /**
     * Per-release deltas applied to a config written by an older version.
     *
     * <p>The problem this solves: {@code auto_allow_tools} and {@code admin_only_tools} are persisted
     * to the toml on first run. On an upgraded server the stored lists win, so tools added in a later
     * release land in neither list — which silently does the wrong thing in both directions. New
     * read-only tools become tier-3 (an OP must click for {@code whoami}, the very tool that explains
     * why things need clicking), and new mutating tools become tier-3 instead of tier-4, i.e. any OP
     * can approve {@code set_nbt} rather than only a configured admin. The second is a security
     * regression introduced purely by upgrading.
     *
     * <p>We migrate additively and record the applied generation, so an entry the operator deliberately
     * deleted is not resurrected on every boot. Only tools introduced *after* the stored generation are
     * considered.
     */
    private record TierMigration(int version, List<String> autoAllow, List<String> adminOnly) {}

    private static final List<TierMigration> TIER_MIGRATIONS = List.of(
            new TierMigration(1,
                    List.of("whoami", "find_blocks", "get_nbt", "list_snapshots",
                            "start_task", "get_task", "cancel_task", "list_tasks"),
                    List.of("set_block", "set_blocks", "fill_blocks", "undo_blocks",
                            "restore_block_snapshot", "set_nbt",
                            "teleport", "give_item", "set_gamemode", "apply_effect",
                            "spawn_entity", "remove_entities", "modify_entity",
                            "set_world_property", "force_load_chunks", "save_world")),
            new TierMigration(2,
                    List.of(),
                    List.of("manage_players", "manage_player_inventory", "manage_scoreboard",
                            "control_entity", "set_world_spawn", "set_world_border", "server_control",
                            "manage_container", "set_player_state", "manage_player_progression",
                            "manage_datapacks")),
            new TierMigration(3,
                    List.of("server_diagnose"),
                    List.of()));

    private static final String FILE_NAME = "agent-link.toml";
    private static final List<String> DEFAULT_WRITE_ALLOW = List.of("config/**");
    private static final List<String> DEFAULT_WRITE_DENY = List.of();
    private static final List<String> DEFAULT_MCP_ALLOWED_ORIGINS =
            List.of("null", "http://localhost", "http://127.0.0.1");
    private static final List<String> DEFAULT_APPROVAL_AUTO_ALLOW_TOOLS = List.of(
            // Protocol / queue layer.
            "ping", "agent_heartbeat", "get_agent_requests", "server_diagnose",
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
            "spark_status", "spark_stats", "spark_health_report",
            // 0.5.0 read-only additions.
            "whoami", "find_blocks", "get_nbt", "list_snapshots",
            // Async task bookkeeping. Starting a task re-checks the wrapped tool's own tier, so
            // these four are safe to auto-allow — see StartTaskTool.
            "start_task", "get_task", "cancel_task", "list_tasks"
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
            "we_set", "we_replace", "we_sphere", "we_cyl", "we_undo",
            // 0.5.0 native block writes. Subject to build_zones: a call whose whole footprint is
            // inside a configured zone skips the prompt.
            "set_block", "set_blocks", "fill_blocks", "undo_blocks", "restore_block_snapshot",
            // NBT writes can forge items, rewrite container contents, or corrupt an entity.
            "set_nbt",
            // Player / entity / world state mutation.
            "teleport", "give_item", "set_gamemode", "apply_effect",
            "spawn_entity", "remove_entities", "modify_entity",
            "set_world_property", "force_load_chunks", "save_world",
            // 0.5.0 structured operator controls.
            "manage_players", "manage_player_inventory", "manage_scoreboard", "control_entity",
            "set_world_spawn", "set_world_border", "server_control", "manage_container",
            "set_player_state", "manage_player_progression", "manage_datapacks"
    );
    private static final List<String> DEFAULT_AUDIT_REDACT_ARGS = List.of(
            // Console payload often contains tokens, op-set commands, /seed output, etc.
            "run_console_command.command",
            // write_config_file content can be a key file or anything
            "write_config_file.content",
            "write_config_file.base64",
            // read_config returns file content — masked at record time, but list it for traceability.
            "read_config.content",
            // set_nbt payloads can be arbitrarily large and carry item/entity internals.
            "set_nbt.snbt",
            "set_nbt.value"
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
            String mcpPublicHost = normalizeHost(cfg.getOrElse("mcp_public_host", "127.0.0.1"));
            List<String> mcpAllowedOrigins = readStringList(cfg, "mcp_allowed_origins", DEFAULT_MCP_ALLOWED_ORIGINS);
            boolean approvalEnabled = cfg.getOrElse("approval.enabled", true);
            int approvalTimeoutSeconds = Math.max(5, cfg.getIntOrElse("approval.timeout_seconds", 60));
            List<String> approvalAutoAllowTools = readStringList(cfg, "approval.auto_allow_tools", DEFAULT_APPROVAL_AUTO_ALLOW_TOOLS);
            List<String> approvalTrustedTools = readStringList(cfg, "approval.trusted_tools", List.of());
            List<String> approvalAdminOnlyTools = readStringList(cfg, "approval.admin_only_tools", DEFAULT_APPROVAL_ADMIN_ONLY_TOOLS);

            // Merge in tools added by releases newer than whatever wrote this file. On a fresh config
            // the defaults above already contain everything and this is a no-op.
            int storedTableVersion = fresh
                    ? APPROVAL_TABLE_VERSION
                    : cfg.getIntOrElse("approval.table_version", 0);
            if (storedTableVersion < APPROVAL_TABLE_VERSION) {
                approvalAutoAllowTools = new ArrayList<>(approvalAutoAllowTools);
                approvalAdminOnlyTools = new ArrayList<>(approvalAdminOnlyTools);
                List<String> addedAuto = new ArrayList<>();
                List<String> addedAdmin = new ArrayList<>();
                for (TierMigration m : TIER_MIGRATIONS) {
                    if (m.version() <= storedTableVersion) continue;
                    for (String tool : m.autoAllow()) {
                        if (!containsTool(approvalAutoAllowTools, tool)
                                && !containsTool(approvalAdminOnlyTools, tool)) {
                            approvalAutoAllowTools.add(tool);
                            addedAuto.add(tool);
                        }
                    }
                    for (String tool : m.adminOnly()) {
                        if (!containsTool(approvalAdminOnlyTools, tool)
                                && !containsTool(approvalAutoAllowTools, tool)) {
                            approvalAdminOnlyTools.add(tool);
                            addedAdmin.add(tool);
                        }
                    }
                }
                if (!addedAuto.isEmpty() || !addedAdmin.isEmpty()) {
                    AgentLinkMod.LOG.info("agent-link: migrated approval tables to generation {} "
                                    + "(auto_allow += {}, admin_only += {})",
                            APPROVAL_TABLE_VERSION, addedAuto, addedAdmin);
                }
            }
            List<UUID> roleAdminUuids = readUuidList(cfg, "roles.admin_uuids");
            List<UUID> roleGuestUuids = readUuidList(cfg, "roles.guest_uuids");

            boolean auditEnabled = cfg.getOrElse("audit.enabled", true);
            List<String> auditRedactArgs = readStringList(cfg, "audit.redact_args", DEFAULT_AUDIT_REDACT_ARGS);
            int auditMaxArgChars = Math.max(64, cfg.getIntOrElse("audit.max_arg_chars", 2000));

            List<BuildZones.Zone> buildZones = readBuildZones(cfg);
            int taskMaxConcurrent = Math.max(1, Math.min(8, cfg.getIntOrElse("tasks.max_concurrent", 2)));
            int taskBlocksPerTick = Math.max(64, Math.min(200_000, cfg.getIntOrElse("tasks.blocks_per_tick", 8000)));
            List<String> eventVerboseTopics = readStringList(cfg, "events.verbose_topics", List.of());

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
            cfg.set("mcp_public_host", mcpPublicHost);
            cfg.setComment("mcp_public_host",
                    "\n Hostname or IP embedded in the local setup endpoint and returned MCP URL.\n" +
                    " Keep 127.0.0.1 when the agent runs on this server; set the reachable server address\n" +
                    " for a remote agent. Do not include http:// or a trailing slash.");
            cfg.set("mcp_allowed_origins", mcpAllowedOrigins);
            cfg.setComment("mcp_allowed_origins",
                    "\n Origin header allowlist for MCP HTTP. Browsers send Origin; native MCP hosts usually do not (or send \"null\").\n" +
                    " Default: [\"null\", \"http://localhost\", \"http://127.0.0.1\"] — safe for local hosts.\n" +
                    " If allow_remote = true, narrow this to your trusted clients to prevent DNS-rebinding attacks.");

            cfg.set("approval.enabled", approvalEnabled);
            cfg.setComment("approval.enabled", " In-game MCP tool approval. When true, non-auto-allowed tools wait for in-game chat approval.");
            cfg.set("approval.timeout_seconds", approvalTimeoutSeconds);
            cfg.setComment("approval.timeout_seconds", " Seconds before a pending in-game tool approval is denied automatically.");
            cfg.set("approval.table_version", APPROVAL_TABLE_VERSION);
            cfg.setComment("approval.table_version",
                    "\n Generation marker for the two tables below. agent-link merges tools added by newer releases\n" +
                    " into them once, then records the generation here. Without this, upgrading would leave new tools\n" +
                    " in neither list: new read-only tools would start demanding an OP click, and new mutating tools\n" +
                    " would be approvable by any OP instead of only a configured admin.\n" +
                    " Entries you delete stay deleted. Lower this number to re-apply a migration.");
            cfg.set("approval.auto_allow_tools", approvalAutoAllowTools);
            cfg.setComment("approval.auto_allow_tools",
                    "\n Tier 1: tools that bypass in-game approval entirely. Default = read-only world / server / log / spark-status\n" +
                    " inspection that in-game admins and OPs already have access to. Add \"*\" only if you trust the MCP host fully.");
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
                    " assigned admin / OP can click.");

            cfg.set("roles.admin_uuids", uuidsAsStrings(roleAdminUuids));
            cfg.setComment("roles.admin_uuids",
                    "\n Server admins (\"腐竹\"). Player UUIDs listed here are assigned the in-game ADMIN role. They may use\n" +
                    " the Agent command/GUI and are the only ones who may invoke approval.admin_only_tools. They may approve\n" +
                    " MCP prompts even if they are not OP.\n" +
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

            cfg.set("tasks.max_concurrent", taskMaxConcurrent);
            cfg.setComment("tasks.max_concurrent",
                    "\n How many async tasks (start_task) may run at once. Long edits are sliced across ticks, so more\n" +
                    " concurrency means more work competing for the same per-tick budget. Default 2, max 8.");
            cfg.set("tasks.blocks_per_tick", taskBlocksPerTick);
            cfg.setComment("tasks.blocks_per_tick",
                    "\n Per-tick block budget for a sliced async edit. Lower = gentler on TPS but slower; higher = the\n" +
                    " reverse. Default 8000 (~a few ms/tick on typical hardware). Range 64..200000.");

            cfg.set("events.verbose_topics", eventVerboseTopics);
            cfg.setComment("events.verbose_topics",
                    "\n High-frequency event topics to record. Empty (default) = off.\n" +
                    " Allowed: \"block_place\", \"block_break\", \"item_pickup\", \"item_drop\".\n" +
                    "\n" +
                    " These are what an investigation usually wants (\"who broke this?\"), and also what a single\n" +
                    " player with an efficiency pickaxe emits hundreds of per minute — enabling them all can push\n" +
                    " chat/join/death out of the 4096-entry ring buffer within a minute. Even when enabled, each\n" +
                    " player is capped at 40 verbose events per 10s window; drops are reported on the \"server\" topic\n" +
                    " so an agent knows the counts are incomplete.");

            writeBuildZonesComment(cfg, buildZones);

            cfg.save();
            CURRENT = new Snapshot(port, allowRemote, token, writeAllow, writeDeny,
                    mcpEnabled, mcpPort, mcpPublicHost, mcpAllowedOrigins,
                    approvalEnabled, approvalTimeoutSeconds, approvalAutoAllowTools, approvalTrustedTools,
                    java.util.Collections.unmodifiableList(approvalAdminOnlyTools),
                    java.util.Collections.unmodifiableList(roleAdminUuids),
                    java.util.Collections.unmodifiableList(roleGuestUuids),
                    auditEnabled,
                    java.util.Collections.unmodifiableList(auditRedactArgs),
                    auditMaxArgChars,
                    java.util.Collections.unmodifiableList(buildZones),
                    taskMaxConcurrent,
                    taskBlocksPerTick,
                    java.util.Collections.unmodifiableList(eventVerboseTopics),
                    VERSION);

            if (fresh) {
                AgentLinkMod.LOG.info("agent-link wrote default config to {}", path);
                AgentLinkMod.LOG.info("agent-link generated token: {}", token);
            }
        }
    }

    /**
     * Read {@code build_zones}. A malformed entry is logged and skipped rather than failing the
     * load — a typo must never widen the agent's permissions, and it must not brick startup either.
     */
    private static List<BuildZones.Zone> readBuildZones(CommentedFileConfig cfg) {
        Object raw = cfg.get("build_zones");
        if (raw == null) return new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            AgentLinkMod.LOG.warn("agent-link: config key 'build_zones' is not a list of tables; ignoring");
            return new ArrayList<>();
        }
        List<BuildZones.Zone> out = new ArrayList<>();
        for (Object item : list) {
            Object normalized = item instanceof com.electronwill.nightconfig.core.Config c
                    ? c.valueMap()
                    : item;
            BuildZones.Zone zone = BuildZones.parse(normalized);
            if (zone == null) {
                AgentLinkMod.LOG.warn("agent-link: ignoring malformed build_zones entry: {}", item);
                continue;
            }
            out.add(zone);
            AgentLinkMod.LOG.info("agent-link: build zone '{}' {} [{},{},{}]..[{},{},{}] ({} blocks)",
                    zone.label(), zone.dimension(),
                    zone.box().minX(), zone.box().minY(), zone.box().minZ(),
                    zone.box().maxX(), zone.box().maxY(), zone.box().maxZ(),
                    zone.box().volume());
        }
        return out;
    }

    /**
     * We only document {@code build_zones} in a comment instead of writing a default entry. An
     * auto-created zone would be a permission grant nobody asked for.
     */
    private static void writeBuildZonesComment(CommentedFileConfig cfg, List<BuildZones.Zone> zones) {
        if (cfg.get("build_zones") == null) {
            cfg.set("build_zones", new ArrayList<>());
        }
        cfg.setComment("build_zones",
                "\n Regions where the agent may build WITHOUT a per-call in-game approval prompt.\n" +
                " Empty (default) = every mutating spatial tool goes through approval, as in 0.4.x.\n" +
                "\n" +
                " A call is exempted only when its ENTIRE affected region fits inside one zone. An edit that\n" +
                " straddles a boundary still prompts — it is never silently clipped.\n" +
                "\n" +
                " Applies to: set_block, set_blocks, fill_blocks, restore_block_snapshot, we_set, we_replace,\n" +
                " we_sphere, we_cyl, spawn_entity. It does NOT exempt run_console_command, write_config_file,\n" +
                " set_nbt, or anything non-spatial.\n" +
                "\n" +
                " Example:\n" +
                "   [[build_zones]]\n" +
                "     label = \"agent plot\"\n" +
                "     dim = \"minecraft:overworld\"\n" +
                "     min = [100, -64, 100]\n" +
                "     max = [200, 320, 200]");
    }

    /** Case-insensitive membership test, matching how the approval layer normalizes tool names. */
    private static boolean containsTool(List<String> list, String tool) {
        if (list == null) return false;
        for (String entry : list) {
            if (entry == null) continue;
            String normalized = entry.trim();
            if ("*".equals(normalized) || normalized.equalsIgnoreCase(tool)) return true;
        }
        return false;
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
                CURRENT = withTrusted(snap, trusted);
            }
        }
    }

    /** Rebuild a snapshot with a new trusted-tools list, preserving every other field. */
    private static Snapshot withTrusted(Snapshot snap, List<String> trusted) {
        return new Snapshot(snap.listenPort(), snap.allowRemote(), snap.token(),
                snap.writeAllow(), snap.writeDeny(), snap.mcpEnabled(),
                snap.mcpListenPort(), snap.mcpPublicHost(), snap.mcpAllowedOrigins(), snap.approvalEnabled(),
                snap.approvalTimeoutSeconds(), snap.approvalAutoAllowTools(), trusted,
                snap.approvalAdminOnlyTools(),
                snap.roleAdminUuids(), snap.roleGuestUuids(),
                snap.auditEnabled(), snap.auditRedactArgs(), snap.auditMaxArgChars(),
                snap.buildZones(), snap.taskMaxConcurrent(), snap.taskBlocksPerTick(),
                snap.eventVerboseTopics(), snap.version());
    }

    private static String normalizeHost(String value) {
        if (value == null || value.isBlank()) return "127.0.0.1";
        String host = value.trim();
        if (host.startsWith("http://")) host = host.substring("http://".length());
        if (host.startsWith("https://")) host = host.substring("https://".length());
        while (host.endsWith("/")) host = host.substring(0, host.length() - 1);
        return host.isBlank() ? "127.0.0.1" : host;
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
                CURRENT = withTrusted(snap, trusted);
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

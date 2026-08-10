# mc-agent-link

Connect Claude Code, Codex, Cursor, or another MCP agent to a running Minecraft server.
The project exposes structured server inspection, diagnostics, player/entity/world control, block editing,
long-running tasks, and events, so most operations do not need `run_console_command`.

> Status: `0.5.0-alpha`. Forge 1.20.1, NeoForge 1.21.1, and a Spigot/Paper 1.20+ base-link plugin are maintained.

[中文 README](README.md) · [Installation](INSTALL.md) · [Pairing](docs/pairing.md) · [Tool catalog](docs/tools.md) · [API reference](docs/api.md) · [Addon architecture](docs/addons.md) · [Diagnostics tools](tools/README.en.md)

## Version support

| Minecraft | Platform | Java / runtime | Directory | In-game addon |
|---|---|---:|---|---|
| 1.20.1 | Forge | 17 | `minecraft/forge-mod` | [mc-agent-link-agent](https://github.com/Nothingness-Void/mc-agent-link-agent) |
| 1.21.1 | NeoForge | 21 | `minecraft/neoforge-mod` | [mc-agent-link-agent](https://github.com/Nothingness-Void/mc-agent-link-agent) |
| 1.20+ | Spigot/Paper | Java 17 plugin; server follows MC version | `minecraft/spigot-plugin` | None; base agent-link only |

The modern Spigot/Paper compatibility build targets 1.20.1, 1.20.6, 1.21.1, and 1.21.11; use `agent-link-spigot-modern-*.jar` by default.
The plugin is compiled to Java 17 bytecode, but the server still follows Minecraft's own runtime requirement: 1.20.1 commonly uses Java 17, while 1.20.5+/1.21 commonly use Java 21.

Forge and NeoForge share the MCP, pairing, permission, request-queue, async-task, diagnostics, and `AgentLinkApi` surfaces, but their loader implementations are separate; do not mix jars.
The base mod provides `/agentlink`, MCP tools, and the addon API. `/agent`, the GUI, steer, and a local Claude process bridge belong to external addon mods.
The Spigot/Paper plugin is an independent base-link implementation with Bukkit-compatible inspection, diagnostics, events, filesystem, and basic world-operation tools. It does not include `/agent`, the GUI, the in-game request queue, or `steer`.

The in-game addon is maintained outside this repository and depends on the matching Forge/NeoForge base-mod API. Install an addon separately when you need `/agent`, the GUI, in-game message queues, or steer.

## Quick start

### One-minute installation

1. Download the jar matching the server platform and put it in the right directory:
   - Forge 1.20.1: `agent-link-forge-1.20.1-*.jar` in `mods/`
   - NeoForge 1.21.1: `agent-link-neoforge-1.21.1-*-all.jar` in `mods/`
   - Spigot/Paper 1.20+: `agent-link-spigot-modern-*.jar` in `plugins/`
2. Start the server. In the console, find and copy the complete local setup endpoint:

   ```text
   agent-link local setup endpoint ...: http://127.0.0.1:25581/pair/setup/<random-id>
   ```

   New pairing does not require opening GitHub. Do not copy a token from `config/agent-link.toml`.
3. Send the complete endpoint to Claude Code, Codex, Cursor, or another MCP agent. The agent reads the endpoint, completes one pairing,
   merges the returned `mcp` object into `mcpServers.minecraft`, then calls `whoami` followed by `ping` to verify the connection.

The setup endpoint is one-use and valid for 10 minutes. If first pairing has not succeeded, the server prints a fresh local endpoint after expiry.
An administrator can also run `/agentlink pair` for a CONSOLE pairing, or `/agentlink pair-guest` for a GUEST pairing that uses in-game approval.

After pairing, the bearer token persists across server restarts. The server will not keep refreshing or reprinting a setup endpoint after restart;
that is expected. To add another agent, or if the host configuration was lost, run the appropriate pairing command again. If the response says `used`,
check the existing MCP host configuration first; it usually means pairing already succeeded.

When the server and agent are on different machines, configure `allow_remote`, `mcp_public_host`, and `mcp_allowed_origins` in
`config/agent-link.toml` for Forge/NeoForge. For Spigot/Paper, configure `mcp.allow-remote`, `mcp.public-host`, and `mcp.allowed-origins`
in `plugins/AgentLink/config.yml`, then restart the server and use the newly printed endpoint. Do not replace a `127.0.0.1` MCP URL by hand.

Older builds may print a GitHub fragment setup link. It remains supported for compatibility, but new installations should use the local endpoint
printed directly by the server.

See [docs/pairing.md](docs/pairing.md) for local, remote, and recovery flows.

## Core capabilities

- Connection and introspection: MCP Streamable HTTP, one-use local pairing, persistent bearer tokens, a legacy WebSocket path, and `whoami` boundary inspection.
- Server inspection and diagnosis: players, entities, worlds, blocks, dimensions, mods, logs, crash reports, TPS/MSPT, tick incidents, JVM thread dumps, and bounded `server_diagnose` snapshots.
- Structured control: players, entities, worlds, items, containers, scoreboards, datapacks, world borders/spawns, whitelist/OP state, NBT, and save/reload operations.
- Building and recovery: native batch block writes, searches, snapshots, and undo without WorldEdit; with WorldEdit/FAWE installed, `we_*` tools use a separate WorldEdit undo stack.
- Long work and events: `start_task`, `get_task`, `cancel_task`, and `list_tasks` decouple large operations from one RPC; events support pull and WebSocket-compatible subscriptions.
- Permissions and audit: CONSOLE/GUEST pairing tiers, ADMIN/OP/PLAYER in-game roles, four approval tiers, `build_zones`, a filesystem sandbox, sensitive-argument redaction, and JSONL audit records.
- Extension: `AgentLinkApi` exposes request, event, task, diagnostics, server-thread, block, NBT, player, entity, world, container, progression, and server-control facades; addons can register namespaced MCP tools.
- Optional integrations: Forge/NeoForge can detect spark and WorldEdit/FAWE; after the JVM exits, `tools/agent-link-watchdog.ps1` preserves logs, crash artifacts, and incident evidence.

Use [docs/tools.md](docs/tools.md) for complete schemas. See [docs/protocol.md](docs/protocol.md) for wire errors and filesystem sandbox rules.

## Architecture

```text
Claude Code / Codex / Cursor
        │ MCP Streamable HTTP
        ▼
agent-link mod / plugin inside the Minecraft server
        ├── MCP :25581
        ├── WebSocket :25580 (compatibility path)
        └── approvals, permissions, queues, tasks, and tool execution
```

World state access is dispatched back to the Minecraft server thread; waiting, file I/O, and large sliced writes use bounded worker/task paths.
`start_task` is asynchronous server-operation bookkeeping, not a general asynchronous agent-conversation scheduler; MCP hosts still use request/response.
This repository is the server connection layer; it does not include a real-player Bot runtime. The external addon consumes the base request queue for `/agent`, GUI, and steer.

## Repository layout

```text
mc-agent-link/
├── minecraft/forge-mod/       # Forge 1.20.1 base mod
├── minecraft/neoforge-mod/    # NeoForge 1.21.1 base mod
├── minecraft/spigot-plugin/   # Spigot/Paper 1.20+ base-link plugin
├── packages/mcp-server/       # Legacy Node stdio/WebSocket bridge
├── docs/                      # Protocol, pairing, tools, API, addon architecture, and releases
├── tools/                     # JVM-exit watchdog and contract validation
├── .claude/skills/            # Claude Code diagnostic skills
├── INSTALL.md                 # Full install flow for agents
└── AGENTS.md                  # Setup-link and safety rules
```

## Security boundaries

- Bind to localhost by default. Remote access requires explicit `allow_remote`, `mcp_public_host`, a narrow Origin allowlist, and firewall rules.
- Pairing has CONSOLE and GUEST tiers: `/agentlink pair` is for an administrator supervising the backend connection; `/agentlink pair-guest` retains in-game approval.
- The in-game addon uses ADMIN, OP, and PLAYER roles. Ordinary players cannot submit or operate `/agent` requests. When `roles.admin_uuids` is configured, sensitive approvals require an assigned ADMIN.
- GUEST tools use four tiers: auto-allow, trusted, normal approval, and admin-only. `build_zones` can exempt a complete spatial footprint; edits crossing a boundary are never silently clipped.
- Call `whoami` before any write or server-control operation. Treat `run_console_command` as a high-privilege fallback; prefer structured tools. Server stop and entity-kill operations require explicit confirmation.
- File writes default to `config/**`, create backups, reject path traversal, and redact tokens, pair codes, identities, and sensitive arguments from guest reads and audit logs.
- Use `start_task` for long operations; do not guess about work that continues after an RPC timeout. Cancellation may leave partial writes, so use the returned undo path when applicable.

## Roadmap

- [x] Forge 1.20.1 base mod
- [x] NeoForge 1.21.1 base mod
- [x] Spigot/Paper 1.20+ base-link plugin
- [x] Persistent pairing, async tasks, structured controls, diagnostics, permissions, audit, build zones, and addon API
- [x] Multi-target modern Spigot/Paper API builds
- [ ] Fabric implementation
- [ ] CI and broader automated tests
- [ ] Standalone real-player Bot runtime

## License

[Apache-2.0](LICENSE)

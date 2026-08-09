# mc-agent-link

Connect Claude Code, Codex, Cursor, or another MCP agent to a running Minecraft server.
The project exposes structured server inspection, diagnostics, player/entity/world control, block editing,
long-running tasks, and events, so most operations do not need `run_console_command`.

> Status: early release. Forge 1.20.1, NeoForge 1.21.1, and a Spigot/Paper 1.20+ base-link plugin are maintained.

[中文 README](README.md) · [Installation](INSTALL.md) · [Pairing](docs/pairing.md) · [Tool catalog](docs/tools.md) · [API reference](docs/api.md)

## Version support

| Minecraft | Platform | Java | Directory | In-game addon |
|---|---|---:|---|---|
| 1.20.1 | Forge | 17 | `minecraft/forge-mod` | [mc-agent-link-agent](https://github.com/Nothingness-Void/mc-agent-link-agent) |
| 1.21.1 | NeoForge | 21 | `minecraft/neoforge-mod` | [mc-agent-link-agent](https://github.com/Nothingness-Void/mc-agent-link-agent) |
| 1.20+ | Spigot/Paper | 17+ | `minecraft/spigot-plugin` | None; base link only |

Forge and NeoForge share the MCP, pairing, permission, request-queue, async-task, and addon APIs, but their loader implementations are separate; do not mix jars.
The Spigot/Paper plugin is an independent base-link implementation. It does not include `/agent`, the GUI, the in-game request queue, or `steer`.

The in-game addon is maintained outside this repository and depends on the base mod API. Install the matching base mod and addon when you need `/agent`, the GUI, or in-game message queues.

## Quick start

1. Download the jar matching the server platform from a GitHub Release, or build it using [INSTALL.md](INSTALL.md). Put Forge/NeoForge jars in `mods/`; put the Spigot/Paper jar in `plugins/`.
2. Start the server and copy the `agent-link local setup endpoint` printed by the console. New pairing does not require GitHub access.
3. Send the complete endpoint to Claude Code, Codex, or another MCP agent. It will pair, merge only `mcpServers.minecraft`, then call `whoami` followed by `ping`.

After pairing, the bearer token persists across server restarts. To pair another agent, an administrator runs `/agentlink pair` or `/agentlink pair-guest`.
See [docs/pairing.md](docs/pairing.md) for local, remote, and recovery flows.

## Core capabilities

- Inspect server health, players, entities, blocks, dimensions, logs, crash reports, and mods.
- Control players, entities, worlds, items, containers, scoreboards, and datapacks through typed tools.
- Edit blocks natively with batching, snapshots, and undo; use `start_task` for sliced, cancellable long operations.
- Pull events, submit in-game requests, enforce approvals, write audit records, and inspect permission boundaries.
- Optionally integrate spark, WorldEdit, and third-party addon tools.

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

World state access is dispatched back to the Minecraft server thread. Long tasks and file operations do not remain inside one blocking RPC.
This repository is the server connection layer; it does not yet include a real-player Bot runtime for an Agent.

## Repository layout

```text
mc-agent-link/
├── minecraft/forge-mod/       # Forge 1.20.1 base mod
├── minecraft/neoforge-mod/    # NeoForge 1.21.1 base mod
├── minecraft/spigot-plugin/   # Spigot/Paper 1.20+ base-link plugin
├── packages/mcp-server/       # Legacy Node stdio/WebSocket bridge
├── docs/                      # Protocol, pairing, tools, API, and release notes
├── .claude/skills/            # Claude Code diagnostic skills
├── INSTALL.md                 # Full install flow for agents
└── AGENTS.md                  # Setup-link and safety rules
```

## Security boundaries

- Bind to localhost by default. Remote access requires explicit `allow_remote`, a public host, trusted origins, and firewall rules.
- Call `whoami` before any write or server-control operation.
- Treat `run_console_command` as a high-privilege fallback; prefer structured tools.
- File writes default to `config/**`, create backups, and reject path traversal.
- Use `start_task` for long operations; do not guess about work that continues after an RPC timeout.

## Roadmap

- [x] Forge 1.20.1 base mod
- [x] NeoForge 1.21.1 base mod
- [x] Spigot/Paper 1.20+ base-link plugin
- [x] Persistent pairing, async tasks, diagnostics, permissions, and addon API
- [ ] Fabric implementation
- [ ] CI and broader automated tests
- [ ] Standalone real-player Bot runtime

## License

[Apache-2.0](LICENSE)

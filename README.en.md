# mc-agent-link

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

Lets AI agents (via the [Model Context Protocol](https://modelcontextprotocol.io)) connect to a running Minecraft server. Run commands, query players, stream events, read mods/configs/crash-reports, profile tick spikes, and tune `config/*` files — anything an op could do at the console, an agent can do.

> **Status:** early. Forge 1.20.1 server-side first. NeoForge / Fabric / Paper planned.

[中文 README](README.md) · [Installation guide for agents](INSTALL.md) · [Wire protocol](docs/protocol.md)

## Why

Modern AI agents (Claude Code, Cursor, custom agents) speak MCP. Minecraft servers don't. RCON works but is request-only and limited. This project bridges the gap with a real-time WebSocket protocol on the server side and an MCP server on the agent side, so multiple agents can connect, observe, and act concurrently.

## What it does

| Group | Tools | Purpose |
|---|---|---|
| Operations | `ping` `run_console_command` `list_online_players` `get_player_info` `broadcast` `get_server_stats` | Run console commands, query players, broadcast |
| Observation (pull) | `get_recent_events` `get_recent_logs` | Recent chat / join / leave / death events and the full server console log |
| Diagnosis | `tick_profile` `thread_dump` `list_mods` | Tick distribution, JVM thread dump, installed mods |
| Filesystem (sandboxed) | `list_dir` `read_server_file` `write_config_file` | Read anywhere under server root; writes are limited to `config/**` with auto-backup |
| Spark integration (optional) | `spark_status` `spark_stats` `spark_profiler_*` `spark_health_report` | When the [spark](https://spark.lucko.me) mod is installed, agents get flame graphs, GC details, and viewer URLs |

Full wire-protocol fields, error codes, and sandbox boundaries: [docs/protocol.md](docs/protocol.md).

## Architecture

```
 ┌──────────────────────┐    Streamable HTTP   ┌─────────────────────────────┐
 │  Agent (Claude Code, │ ───────────────────► │  Forge mod (Java)           │
 │  Cursor, custom...)  │   POST /mcp + JSON   │  minecraft/forge-mod        │
 └──────────────────────┘   Bearer auth        │  in-process inside MC JVM   │
                                               └─────────────────────────────┘
                                                  │
                                                  │ also exposes
                                                  ▼
                            ┌──────────────────────────────────────┐
                            │  WebSocket :25580 (non-MCP clients)  │
                            │  + Node bridge (legacy host fallback)│
                            └──────────────────────────────────────┘
```

- **Forge mod** runs *inside* the Minecraft server JVM and listens on two sockets:
  - `:25581/mcp` — MCP Streamable HTTP. Hosts (Claude Code, Cursor, …) connect directly.
  - `:25580` — agent-link WebSocket protocol for non-MCP clients (moderation bots, the stdio bridge).
- All work is dispatched on the main server thread to stay thread-safe with world state.
- **Multi-agent**: HTTP and WebSocket each accept many concurrent connections.

## Repo layout

```
mc-agent-link/
├── .claude/skills/           # Claude Code slash commands (mc-overview, mc-diagnose, mc-crash, mc-health-check)
├── docs/
│   └── protocol.md            # agent-link wire protocol spec
├── minecraft/
│   └── forge-mod/             # Forge 1.20.1 mod (Java 17, Gradle)
├── packages/
│   └── mcp-server/            # Node MCP bridge (TypeScript, stdio fallback)
└── INSTALL.md                 # Step-by-step install guide (designed to be read by an agent)
```

## Quick start

Three steps:

1. **Install the mod**: drop `agent-link-forge-1.20.1-*.jar` into your server's `mods/` directory and start the server once.
2. **Get the token**: it's generated in `<server>/config/agent-link.toml` after first start. The console also prints `agent-link generated token: ...` once.
3. **Configure your MCP host** (Claude Code example, in `~/.claude.json` or project `.mcp.json`):

   ```json
   {
     "mcpServers": {
       "minecraft": {
         "type": "http",
         "url": "http://127.0.0.1:25581/mcp",
         "headers": {
           "Authorization": "Bearer <token from step 2>"
         }
       }
     }
   }
   ```

For remote servers, replace `127.0.0.1` with the server's IP, set `allow_remote = true` in `agent-link.toml`, narrow `mcp_allowed_origins` to your trusted clients, and restart.

**Older host that doesn't support HTTP transport?** The Node bridge in `packages/mcp-server` still works over stdio + WebSocket — see [INSTALL.md Appendix A](INSTALL.md#附录-a--node-bridgestdio兼容路径).

**Want an agent to install it for you?** Hand it [INSTALL.md](INSTALL.md) — it's written to be self-executing.

## How agents discover what to do

The mod ships an `instructions` string in its `initialize` response (both HTTP and the bridge surface the same text). Any compliant host (Claude Code, Cursor, Zed) feeds it to the model automatically — agents see a description of the server, the tool grouping, and the recommended diagnosis loop without any extra prompt engineering.

The repo also ships four Claude Code skills (under `.claude/skills/`, committed in-repo):

| Command | Purpose |
|---|---|
| `/mc-health-check` | Quick connectivity check after switching servers or hosts |
| `/mc-overview` | One-round snapshot: health + players + events + errors + mods, ends with one suggested next step |
| `/mc-diagnose` | Lag diagnosis loop: tick_profile → thread_dump → optional spark → mod attribution → config recommendation |
| `/mc-crash` | Reads the newest `crash-reports/*.txt`, correlates with mods + recent error logs, proposes a fix |

For agents without skill support, feed `.claude/skills/<name>/SKILL.md` directly as a prompt template.

## Safety boundaries

- **Writes** are gated by `write_allow` / `write_deny` glob lists in `config/agent-link.toml` (defaults: `write_allow = ["config/**"]`, `write_deny = []`). Edit the file and restart the server to widen or tighten what agents can write. `write_deny` takes precedence over `write_allow`. Full rules, glob syntax, and examples are in [docs/protocol.md](docs/protocol.md) under "Filesystem sandbox".
- **Other write guarantees**: auto-backup to `config/.agent-link-backup/<encoded-path>.<timestamp>.bak`, atomic rename, 4 MiB hard cap. The backup directory itself is never writable.
- **Reads** can touch anywhere under server root, capped at 256 KiB by default and 4 MiB hard. Binary files come back base64.
- **Never allowed**: deleting files, running OS shell, changing the token, escaping the root with `..`.
- **Op commands**: `run_console_command` runs at op level 4. The bundled `instructions` tell agents to require explicit user confirmation before `stop`, `/op`, `/ban`, etc.
- Default bind is `127.0.0.1`; `allow_remote = true` flips to `0.0.0.0` — firewall accordingly.

## Wire-protocol gist

- Frame = UTF-8 JSON, `v=0`. Three types: `request` / `response` (correlated by `id`, may arrive out of order) / `event` (only after `subscribe_events`).
- **Pull is the default**: `get_recent_events` returns a slice of recent events from a server-side ring buffer. The agent only spends LLM tokens on what it asks for.
- **Push** is retained for non-MCP clients (moderation bots, dashboards) via `subscribe_events`.
- Error codes: `UNAUTHENTICATED` `INVALID_TOKEN` `UNSUPPORTED_VERSION` `UNKNOWN_TOOL` `INVALID_ARGS` `INTERNAL_ERROR` `TIMEOUT` `SPARK_UNAVAILABLE`.

## Roadmap

- [ ] NeoForge implementation
- [ ] Fabric implementation
- [ ] Paper implementation
- [ ] Auto-apply patches (currently agents only suggest; users confirm)
- [ ] CI + unit tests

Track / contribute: [issues](https://github.com/Nothingness-Void/mc-agent-link/issues).

## License

[Apache-2.0](LICENSE)

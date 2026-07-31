# mc-agent-link

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

Lets AI agents (via the [Model Context Protocol](https://modelcontextprotocol.io)) connect to a running Minecraft server. Run commands, query players, stream events, read mods/configs/crash-reports, profile tick spikes, tune `config/*` files — plus **native block writing, NBT read/write, player/entity/world control, and async long-running tasks**. Anything an op could do at the console, an agent can do, and most of it no longer needs `run_console_command`.

> **Status:** early. Forge 1.20.1 server-side first. NeoForge / Fabric / Paper planned.

[中文 README](README.md) · [Installation guide for agents](INSTALL.md) · [Wire protocol](docs/protocol.md)

## Why

Modern AI agents (Claude Code, Cursor, custom agents) speak MCP. Minecraft servers don't. RCON works but is request-only and limited. This project bridges the gap with an in-mod MCP HTTP endpoint, while retaining a WebSocket protocol for non-MCP clients and legacy bridges, so multiple agents can connect, observe, and act concurrently.

## What it does

| Group | Tools | Purpose |
|---|---|---|
| Self-check | `whoami` | The first call an agent should make: its token tier, which tools bypass approval, whether an OP is online to approve anything, write globs, build zones, per-tool volume limits, and which optional integrations are installed |
| Operations | `ping` `run_console_command` `list_online_players` `get_player_info` `get_player_inventory` `broadcast` `get_server_stats` | Run console commands, query players, broadcast |
| World reading | `get_world_info` `get_block` `get_blocks_region` `find_blocks` `get_biome` `raycast` `list_entities_near` `list_dimensions` | Time/weather/seed, single blocks, region RLE (≤4096), **large-region block search returning only hits**, biomes, free rays, nearby entities |
| World writing (native, no WorldEdit) | `set_block` `fill_blocks` `set_blocks` `undo_blocks` `save_block_snapshot` `list_snapshots` `restore_block_snapshot` | Single / cuboid / arbitrary-position-set writes with blockstate properties and block-entity NBT; own undo stack; snapshot round-trip (with `offset`, a working copy-paste) |
| NBT | `get_nbt` `set_nbt` | Raw NBT on block entities, entities, players and inventory slots with NBT-path support — enchantments, villager trades, spawners, modded internals |
| Player & entity control | `teleport` `give_item` `set_gamemode` `apply_effect` `spawn_entity` `remove_entities` `modify_entity` | Teleport (cross-dimension, snap-to-surface), give items with NBT, game modes, status effects, spawn / clean up / edit entities |
| World control | `set_world_property` `force_load_chunks` `save_world` | Time / weather / difficulty / gamerule writes, chunk force-loading, explicit flush to disk |
| Async tasks | `start_task` `get_task` `cancel_task` `list_tasks` | Decouples long work from a single RPC: returns a task id immediately, slices across ticks, reports progress, cancellable |
| Registries | `list_block_ids` `list_item_ids` `list_entity_ids` `list_biome_ids` | Paginated with substring filter |
| In-game request API | `agent_heartbeat` `get_agent_requests` `update_agent_request_status` `reply_agent_request` | The base mod provides the queue and MCP API; the in-game `/agent` command is provided by the optional `mc-agent-link-agent` addon |
| Observation (pull) | `get_recent_events` `get_recent_logs` | Chat / join / leave / death, plus **command / container_open / entity_death / explosion / player_hurt / advancement / dimension_change**; high-frequency topics like `block_place` are opt-in |
| Diagnosis | `tick_profile` `thread_dump` `list_mods` | Tick distribution, JVM thread dump, installed mods |
| Filesystem (sandboxed) | `list_dir` `read_server_file` `read_config` `write_config_file` | Read anywhere under server root; writes are limited to `config/**` with auto-backup |
| Spark integration (optional) | `spark_status` `spark_stats` `spark_profiler_*` `spark_health_report` | When the [spark](https://spark.lucko.me) mod is installed, agents get flame graphs, GC details, and viewer URLs |
| WorldEdit integration (optional) | `we_status` `we_set` `we_replace` `we_sphere` `we_cyl` `we_undo` | Faster on very large selections, and the only source of sphere/cylinder generation; separate undo stack from `undo_blocks` |
| Stable addon API | `world.agentlink.api.AgentLinkApi` `BaseAddonTool` | Optional addon mods can register their own MCP tools, get an automatic `<modid>__` prefix, and appear in HTTP `tools/list` |

Full tool catalog with per-tool arguments and return fields: [docs/tools.md](docs/tools.md). Wire-protocol fields, error codes, and sandbox boundaries: [docs/protocol.md](docs/protocol.md).

### Two boundaries worth stating up front

**Long operations must go through `start_task`.** Exceeding a synchronous volume limit returns
`VOLUME_TOO_LARGE` with the exact `start_task` call to use instead — the agent never has to invent its
own region splitting. Tasks run on a dedicated executor and slice at `tasks.blocks_per_tick`, so a
single large fill does not freeze the server.

**`build_zones` keeps frequent writes from spamming approval prompts.** Declare a bounding box in
`config/agent-link.toml` and spatial writes whose **entire** footprint lands inside it skip approval.
An edit straddling the boundary still prompts and is never silently clipped. Empty by default, so
behaviour matches 0.4.x until an operator opts in.

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
- World access is always dispatched on the main server thread to stay thread-safe. Tools that declare `offThread()` (file I/O, waiting on approval, sliced writes) run on a worker pool and hop back on-thread via `ServerThread.call` for the parts that need it — so a slow tool no longer stalls the tick loop.
- **Multi-agent**: HTTP and WebSocket each accept many concurrent connections.
- **In-game approval**: MCP tool calls can ask online OPs in Minecraft chat with clickable `[allow once] [deny] [always allow this tool] [copy details]` buttons.
- **Extensible**: addon mods can register custom tools through `world.agentlink.api.AgentLinkApi.registerTool(...)`; names are automatically namespaced as `<modid>__<tool>`.

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

Foolproof path:

1. **Install the mod**: drop `agent-link-forge-1.20.1-*.jar` into your server's `mods/` directory and start the server.
2. **Copy the setup link**: the console prints an `agent-link setup link (...)` line. It is one-use and valid for 10 minutes; if pairing has not succeeded, the mod refreshes and prints a new link automatically. OPs or the console can also run `/agentlink pair` to generate a new link immediately.
3. **Send it to your agent**: paste the full setup link into Claude Code / Cursor / your custom agent. The agent exchanges it through `/pair`, writes the MCP host config, then calls `ping` to verify.

The setup link looks like this:

```text
https://github.com/Nothingness-Void/mc-agent-link/blob/main/AGENTS.md#agent-link-setup=...
```

If the pairing code expires, use the latest refreshed setup link from the console, or run `/agentlink pair` to refresh it manually. If it has already been used, pairing has already succeeded.

For remote servers, set `allow_remote = true` in `agent-link.toml`, narrow `mcp_allowed_origins` to your trusted clients, restart, and make sure your firewall allows `mcp_listen_port`.

**Older host that doesn't support HTTP transport?** The Node bridge in `packages/mcp-server` still works over stdio + WebSocket — see [INSTALL.md Appendix A](INSTALL.md#附录-a--node-bridgestdio兼容路径). This is the legacy path; new installs should use setup link + HTTP.

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

## Addon API

- **Stable entry point**: `world.agentlink.api.AgentLinkApi`
- **Register tools**: `AgentLinkApi.registerTool(modId, tool)`
- **Convenience base class**: `world.agentlink.api.BaseAddonTool`
- **Naming rule**: registered tools are automatically rewritten to `<modid>__<tool_name>` to avoid clashes with built-ins or other addons
- **Discovery**: HTTP MCP `tools/list` returns addon tools together with built-in tools

This keeps the base mod focused on generic MCP transport, approval, and request-queue behavior while Claude bridge, avatar, or custom features live in optional addons.

## Safety boundaries

- **Writes** are gated by `write_allow` / `write_deny` glob lists in `config/agent-link.toml` (defaults: `write_allow = ["config/**"]`, `write_deny = []`). Edit the file and restart the server to widen or tighten what agents can write. `write_deny` takes precedence over `write_allow`. Full rules, glob syntax, and examples are in [docs/protocol.md](docs/protocol.md) under "Filesystem sandbox".
- **Other write guarantees**: auto-backup to `config/.agent-link-backup/<encoded-path>.<timestamp>.bak`, atomic rename, 4 MiB hard cap. The backup directory itself is never writable.
- **Reads** can touch anywhere under server root, capped at 256 KiB by default and 4 MiB hard. Binary files come back base64.
- **Never allowed**: deleting files, running OS shell, changing the token, escaping the root with `..`.
- **Op commands**: `run_console_command` runs at op level 4. The bundled `instructions` tell agents to require explicit user confirmation before `stop`, `/op`, `/ban`, etc.
- **In-game tool approval** is enabled by default and split into 4 tiers.
  - `approval.auto_allow_tools`: allowed immediately without a prompt
  - `approval.trusted_tools`: remembered from the `[always allow this tool]` button
  - ordinary approval: shown to all online OPs; clickable buttons and manual `/agentlink approve|deny|trust` use the same authorization checks
  - `approval.admin_only_tools`: only players listed in `[roles].admin_uuids` may approve; when `admin_uuids` is empty, this falls back to all online OPs for legacy servers
- **High-impact tools** such as `run_console_command`, `write_config_file`, `broadcast`, and `spark_profiler_*` can never be permanently trusted; they require per-call approval.
- Default bind is `127.0.0.1`; `allow_remote = true` flips to `0.0.0.0` — firewall accordingly.

## Wire-protocol gist

- Frame = UTF-8 JSON, `v=0`. Three types: `request` / `response` (correlated by `id`, may arrive out of order) / `event` (only after `subscribe_events`).
- **Pull is the default**: `get_recent_events` returns a slice of recent events from a server-side ring buffer. The agent only spends LLM tokens on what it asks for.
- **Push** is retained for non-MCP clients (moderation bots, dashboards) via `subscribe_events`.
- Error codes: `UNAUTHENTICATED` `INVALID_TOKEN` `UNSUPPORTED_VERSION` `UNKNOWN_TOOL` `INVALID_ARGS` `APPROVAL_DENIED` `INTERNAL_ERROR` `TIMEOUT` `SPARK_UNAVAILABLE`.

## Roadmap

- [ ] NeoForge implementation
- [ ] Fabric implementation
- [ ] Paper implementation
- [ ] Auto-apply patches (currently agents only suggest; users confirm)
- [ ] CI + unit tests

Track / contribute: [issues](https://github.com/Nothingness-Void/mc-agent-link/issues).

## License

[Apache-2.0](LICENSE)

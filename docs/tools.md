# MCP Tools reference

This page lists every MCP tool registered by `mc-agent-link` (base mod) at startup and what each one does. Versions covered: **0.4.0-alpha** (base + `mc-agent-link-agent` addon — version numbers unified starting this release).

> Tools that an addon adds via `AgentLinkApi.registerTool(modId, tool)` get a `<modid>__` prefix and appear in MCP `tools/list` automatically; this page only documents the base mod set.

## Approval tiers (config in `config/agent-link.toml`)

Two independent dimensions decide whether a tool call goes through in-game approval:

1. **Token tier** (which bearer token the caller is using):
   - **CONSOLE** — minted by `/agentlink pair`. Whoever runs that command in the terminal/console is presumed to supervise the agent in real time. Every tool call from a CONSOLE token bypasses in-game approval entirely (including `admin_only_tools`). Audit log records `outcome: console_trusted`.
   - **GUEST** — minted by `/agentlink pair-guest`, OR the legacy master `token` field in `agent-link.toml` (auto-implicit). Goes through the per-tool table below. Used for in-game `/agent` calls (the addon Claude bridge inherits this), or any third-party MCP host you don't fully control.

2. **Per-tool tier** (only consulted when the token is GUEST):

| Tier | Reaches Claude | Who can approve | Default members |
|---|---|---|---|
| 1 · auto-allow | always, no prompt | n/a | low-risk reads (see `approval.auto_allow_tools`) |
| 2 · trusted (whole or `tool(arg=glob)`) | trusted hits skip the prompt | n/a | grown via in-game `[始终允许 …]` buttons |
| 3 · normal approval | requires in-game button | any online OP | every tool not in 1/2/4 |
| 4 · admin-only | requires button **and** OP must be in `[roles].admin_uuids` | only configured admins | mutating / sensitive tools (see `approval.admin_only_tools`) |

A non-OP player cannot approve anything. The `/agentlink` command tree itself requires `hasPermission(2)`.

---

## Protocol / queue layer (auto-allowed)

| Tool | Args | Returns |
|---|---|---|
| `ping` | none | `{ uptime_ms }` |
| `agent_heartbeat` | `{ agent_name?, action? }` | `{ ok }` — refreshes `lastAgentSeenAt` so `/agent status` reports activity |
| `get_agent_requests` | `{ since_seq?, limit?, include_done? }` | recent `AgentRequestBuffer` entries |
| `update_agent_request_status` | `{ id, status, message? }` | updated entry |
| `reply_agent_request` | `{ id, reply, mark_done? }` | updated entry; pushes the reply to the player |

## Players & online state (auto-allowed)

| Tool | Args | Returns |
|---|---|---|
| `list_online_players` | none | `[{ name, uuid, ping, dim }]` |
| `get_player_info` | `{ name }` | full snapshot: pos, block_pos, dim, yaw, pitch, **facing** (`north/south/east/west`), **look_vector**, **look_axis**, vitals (health/food/saturation/air/fire), flags (on_fire/on_ground/in_water/sneaking/sprinting), xp_level/progress/total, gamemode, ping, op, main_arm, **selected_slot**, **held_main**, **held_off**, **effects[]** (potion id/amplifier/duration), **look_target** (5-block raycast: pos/block/face/distance) |
| `get_player_inventory` | `{ name }` | `{ name, uuid, selected_slot, main[], armor[], offhand[], free_slots, total_slots }` — each slot has `slot, id, count, damage?, custom_name?, has_nbt?` |

## World state (auto-allowed)

| Tool | Args | Returns |
|---|---|---|
| `get_world_info` | none | game/day time, moon phase, day/night flags, weather, difficulty, hardcore, default gametype, level name, seed, overworld spawn, overworld border, gamerules{}, dimensions[] |
| `list_dimensions` | none | per-dimension id, min_y, height, sea_level, loaded_chunks |
| `get_block` | `{ x, y, z, dim? }` | id, properties (blockstate), is_air, is_solid, light_emission, biome, has_block_entity, block_entity? |
| `get_blocks_region` | `{ min:{x,y,z}, max:{x,y,z}, dim? }` (volume ≤ 4096) | `{ palette[], runs[[idx,count],…], order:"y_z_x", min, max, volume, dim }` |
| `get_biome` | `{ x, y, z, dim? }` | biome id, base_temperature, downfall, has_precipitation |
| `raycast` | `{ origin, direction, max_distance?, hit_fluids?, dim? }` (max 64) | `{ hit, pos?, block?, face?, distance?, hit_point? }` |
| `list_entities_near` | `{ center, radius? (≤64), limit? (≤256), types?[], ids?[], dim? }` | filtered list of entities with uuid, type, category, name, custom_name, pos, distance, vitals, vehicle relations |
| `list_block_ids` / `list_item_ids` / `list_entity_ids` / `list_biome_ids` | `{ filter?, page?, page_size? (default 100, max 500) }` | paginated registry ids |

## Diagnostics (auto-allowed)

| Tool | Args | Returns |
|---|---|---|
| `get_server_stats` | none | `{ tps, mspt, mem_used_mb, mem_max_mb, loaded_chunks, online, max_players }` |
| `tick_profile` | none | rolling 100-tick distribution (avg/max/p50/p95/p99 mspt) |
| `thread_dump` | none | server thread dump |
| `get_recent_events` | `{ since_seq?, topics?, limit? }` | event-bus tail |
| `get_recent_logs` | `{ since_seq?, level?, limit? }` | server log tail |
| `subscribe_events` / `unsubscribe_events` | `{ topics }` | per-WebSocket subscription (no-op on stateless MCP HTTP) |

## Mod inventory (auto-allowed)

| Tool | Args | Returns |
|---|---|---|
| `list_mods` | none | every loaded Forge mod with id, version, license, display name, side |

## Spark integration (read-only auto-allowed; profiler is admin-only)

| Tool | Tier | Args | Notes |
|---|---|---|---|
| `spark_status` | 1 | none | spark presence + version |
| `spark_stats` | 1 | none | spark's snapshot (TPS/CPU/memory) |
| `spark_health_report` | 1 | `{ wait_url_ms? }` | uploads a spark health report and returns the URL |
| `spark_profiler_start` | 4 | `{ timeout?, interval_ms?, only_ticks_over_ms?, threads? }` | starts CPU profiler |
| `spark_profiler_stop` | 4 | `{ wait_url_ms? }` | stops + uploads |
| `spark_profiler_cancel` | 4 | none | cancels without upload |

## High-impact / admin-only

| Tool | Tier | Args | Notes |
|---|---|---|---|
| `run_console_command` | 4 | `{ command }` | full op-level-4 console; `command` accepts `say *`/`tellraw *`/etc. and the `[始终允许 run_console_command(command=say *)]` button records a parameter-glob trust rule |
| `broadcast` | 4 | `{ message?, components?, color?, target? }` | system message; provide either `message` (+ optional `color`) or `components` (raw tellraw JSON). `target` accepts `@a` (default), a player name, or UUID |
| `write_config_file` | 4 | `{ path, content, base64? }` | sandboxed by `write_allow` / `write_deny` globs in `agent-link.toml` |
| `read_server_file` | 4 | `{ path, offset?, max_bytes? }` | reads under server root; can leak secrets so admin-only by default |
| `list_dir` | 4 | `{ path }` | same scope as `read_server_file` |
| `get_container` | 4 | `{ x, y, z, dim? }` | reads container contents without opening the block; writes one audit-log line |

## Tier-A read-only catalog & dry-run (auto-allowed)

| Tool | Args | Returns |
|---|---|---|
| `command` | `{ command, mode?, cursor? }` | Brigadier dry-run. `mode="suggest"` returns Tab suggestions at `cursor`; `mode="parse"` (default) returns syntax errors and any unused trailing input. |
| `find_players` | `{ selector, include_entities?, anchor?, anchor_player? }` | resolves vanilla `@a/@e/@p/@r` selectors. Optional anchor sets the source for relative selectors (`distance=..16`). |
| `get_item_info` | `{ id }` | static catalog: stack size, durability, fuel time, edibility, fire resistance, tags; default-state block info if id is a block. |
| `get_recipes_for` | `{ id }` | every recipe whose result is `id` (crafting / smelting / blasting / smoking / campfire / stonecutting / smithing). |
| `get_block_drops` | `{ x, y, z, dim?, tool?, fortune?, silk_touch?, rolls? }` | one-or-many randomized loot rolls. Returns total + average per break per drop id. |

## Tier-C config & snapshot (auto-allowed read; mutating snapshot is separate)

| Tool | Args | Returns |
|---|---|---|
| `read_config` | `{ name?, mode? }` | reads one of an allowlisted config name (`server.properties`, `agent-link.toml`, `whitelist.json`, `ops.json`, ...). `mode="list"` returns the allowed names. |
| `save_block_snapshot` | `{ name, min, max, dim? }` (volume ≤ 65536) | saves palette+RLE to `config/agent-link/snapshots/<name>.json`. No restore yet. |
| `get_scoreboard` | `{ mode? = "objectives" / "objective" / "teams", name? }` | objectives summary, full per-player scores for one objective, or team list with members. |

## WorldEdit / FAWE integration (read-only auto-allowed; mutating ops are admin-only)

Bridge to [WorldEdit](https://enginehub.org/worldedit) (and FAWE when present). `we_status`
reports whether either is installed; the rest stay disabled with a clear error otherwise.
Each successful mutating op pushes its `EditSession` onto a shared agent undo stack so
`we_undo` can reverse the last N ops.

| Tool | Tier | Args | Notes |
|---|---|---|---|
| `we_status` | 1 | none | `{ available, implementation: "WorldEdit"/"FastAsyncWorldEdit", version, fawe_version?, undo_depth }` |
| `we_set` | 4 | `{ min, max, block, dim? }` | fill cuboid (volume ≤ 200 000); pure WE replace, undoable |
| `we_replace` | 4 | `{ min, max, from, to, dim? }` | `from` = string or array of block ids; replace each match with `to` |
| `we_sphere` | 4 | `{ center, radius (≤50), block, hollow?, dim? }` | sphere via WE `makeSphere` |
| `we_cyl` | 4 | `{ center, radius (≤50), height (≤256), block, hollow?, dim? }` | bottom-centered cylinder via WE `makeCylinder` |
| `we_undo` | 4 | `{ steps? (1-10), dim? }` | pops the most recent N edit sessions and reverses them |

---

## Operator commands

| Command | Effect |
|---|---|
| `/agent <text>` | OP: queue an in-game request to the connected agent (provided by `mc-agent-link-agent`) |
| `/agent status` / `cancel <id>` / `reload` | inspect / cancel / reload addon config |
| `/agentlink pair` | rotate the one-time MCP setup link as a **CONSOLE** token (skips in-game approval) |
| `/agentlink pair-guest` | rotate the one-time MCP setup link as a **GUEST** token (always goes through in-game approval) |
| `/agentlink approve <id>` / `deny <id>` | resolve the chat approval prompt |
| `/agentlink trust <id>` | add a **whole-tool** rule to `approval.trusted_tools` |
| `/agentlink trustpattern <id>` | add a **parameter-glob** rule (auto-derived from current call) |
| `/agentlink trustlist` | list all current trust rules |
| `/agentlink untrust <rule>` | remove one rule (use the exact string from `trustlist`) |
| `/agentlink approvals` | summary of pending approvals |
| `/agentlink tokens` | list issued tokens (hash prefix, tier, label, issued_at, last_used_at) |
| `/agentlink tokens revoke <hash-prefix>` | revoke one or more issued tokens by SHA-256 hash prefix |
| `/agentlink audit path` | print absolute path of `logs/agentlink-audit.log` (admin-only when `admin_uuids` is configured) |
| `/agentlink audit tail [N]` | print the last N (default 20, max 200) audit lines (admin-only when `admin_uuids` is configured) |

All `/agentlink` subcommands require `hasPermission(2)`. Admin-only approvals additionally require the actor's UUID to be in `[roles].admin_uuids`.

## Audit log

When `audit.enabled = true` (default), every MCP tool invocation appends a single JSON line to `logs/agentlink-audit.log`. Read with `/agentlink audit tail` or feed the file into `jq`:

```jsonl
{"ts":"2026-05-26T18:33:00Z","tool":"run_console_command","outcome":"approved","actor":"void","actor_uuid":"...","reason":"approved by void","result_ok":true,"args_json":"{\"command\":{\"redacted\":true,\"length\":18}}"}
```

Sensitive fields are redacted via `audit.redact_args` (`tool.argkey` format). Defaults: `run_console_command.command`, `write_config_file.content`, `write_config_file.base64`, `read_config.content`. Extend the list for any addon tool whose arg or returned content is sensitive.

## Adding a tool from your own mod

```java
AgentLinkApi.registerTool("myaddon", new BaseAddonTool(
    "echo",
    "Echo args back as result.",
    /* inputSchema */ schema()
) {
    @Override
    protected JsonObject invoke(JsonObject args) {
        return args;
    }
});
```

The registered name becomes `myaddon__echo`. It shows up in `tools/list`, goes through the same approval pipeline, and respects the same role tiers. Sensitive addon tools should be added to `approval.admin_only_tools` in `agent-link.toml`.

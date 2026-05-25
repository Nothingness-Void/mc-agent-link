# MCP Tools reference

This page lists every MCP tool registered by `mc-agent-link` (base mod) at startup and what each one does. Versions covered: **0.2.3-alpha** (base) + **0.3.2-alpha** (`mc-agent-link-agent` addon).

> Tools that an addon adds via `AgentLinkApi.registerTool(modId, tool)` get a `<modid>__` prefix and appear in MCP `tools/list` automatically; this page only documents the base mod set.

## Approval tiers (config in `config/agent-link.toml`)

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
| `broadcast` | 4 | `{ message, color? }` | system message to every online player |
| `write_config_file` | 4 | `{ path, content, base64? }` | sandboxed by `write_allow` / `write_deny` globs in `agent-link.toml` |
| `read_server_file` | 4 | `{ path, offset?, max_bytes? }` | reads under server root; can leak secrets so admin-only by default |
| `list_dir` | 4 | `{ path }` | same scope as `read_server_file` |

---

## Operator commands

| Command | Effect |
|---|---|
| `/agent <text>` | OP: queue an in-game request to the connected agent (provided by `mc-agent-link-agent`) |
| `/agent status` / `cancel <id>` / `reload` | inspect / cancel / reload addon config |
| `/agentlink pair` | rotate the one-time MCP setup link |
| `/agentlink approve <id>` / `deny <id>` | resolve the chat approval prompt |
| `/agentlink trust <id>` | add a **whole-tool** rule to `approval.trusted_tools` |
| `/agentlink trustpattern <id>` | add a **parameter-glob** rule (auto-derived from current call) |
| `/agentlink trustlist` | list all current trust rules |
| `/agentlink untrust <rule>` | remove one rule (use the exact string from `trustlist`) |
| `/agentlink approvals` | summary of pending approvals |

All `/agentlink` subcommands require `hasPermission(2)`. Admin-only approvals additionally require the actor's UUID to be in `[roles].admin_uuids`.

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

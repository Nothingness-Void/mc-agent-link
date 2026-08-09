# MCP Tools reference

This page lists every MCP tool registered by `mc-agent-link` (base mod) at startup and what each one does. Versions covered: **0.5.0-alpha** (base + `mc-agent-link-agent` addon — version numbers unified since 0.4.0).

> Tools that an addon adds via `AgentLinkApi.registerTool(modId, tool)` get a `<modid>__` prefix and appear in MCP `tools/list` automatically; this page only documents the base mod set.

## Start here: `whoami`

Before doing anything on an unfamiliar server, call `whoami`. It returns the caller's token tier,
the full auto-allow / admin-only / trusted lists, whether an eligible approver is currently online,
the file-write globs, the configured build zones, the per-tool volume limits, and which optional
integrations are installed. That replaces discovering the boundaries by triggering denials — and it
distinguishes "this token can never do that" from "no OP is online to approve it", which produce very
different advice to the user.

## Permission model

There are two independent permission levels:

1. **Backend / transport level** — a **CONSOLE** token minted by `/agentlink pair` is the
   administrator's backend connection. It bypasses in-game approval and can use every tool.
   Keep this token in the administrator's trusted Codex/Claude host only.
2. **In-game level** — the addon `/agent` command and GUI accept only these player roles:
   - **ADMIN** — UUID listed in `[roles].admin_uuids`. This role is assigned by the server
     administrator and may be used even when the player is not OP. Admins can approve ordinary
     and admin-only MCP prompts.
   - **OP** — vanilla permission level 2 or higher, but not an assigned admin. OPs can use the
     Agent command/GUI and approve ordinary prompts; admin-only tools still require an ADMIN.
   - **PLAYER** — everyone else. Ordinary players cannot open or operate the Agent GUI, submit
     `/agent` requests, steer, cancel, stop, or reset sessions.

The server enforces this on the command tree, every GUI packet, and the request submitter. Client
side hiding is only a convenience; it is not the security boundary.

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
| 3 · normal approval | requires in-game button | any online OP or assigned admin | every tool not in 1/2/4 |
| 4 · admin-only | requires button | only assigned admins | mutating / sensitive tools (see `approval.admin_only_tools`) |

A PLAYER cannot approve anything. The `/agentlink` command tree is available to the console, OPs,
and assigned admins; sensitive subcommands still follow the same role checks.

### Build zones — the geometric exemption (0.5.0)

Tier 4 alone made building unworkable: every `fill_blocks` call needed a click, a build is hundreds of
calls, and an operator clicking "allow" that many times is not exercising judgment. Blanket-trusting
the tool is the opposite failure — the agent could then flatten spawn.

`build_zones` bounds the permission spatially instead of per-call:

```toml
[[build_zones]]
  label = "agent plot"
  dim = "minecraft:overworld"
  min = [100, -64, 100]
  max = [200, 320, 200]
```

A mutating spatial call is exempted only when its **entire** affected region fits inside one zone.
An edit straddling a boundary still prompts — it is never silently clipped to fit, because doing
something other than what was asked is worse than asking.

Applies to `set_block`, `set_blocks`, `fill_blocks`, `restore_block_snapshot`, `we_set`, `we_replace`,
`we_sphere`, `we_cyl`, `spawn_entity`. It deliberately does **not** exempt `run_console_command`
(a command's footprint cannot be inferred from text we did not parse), `write_config_file`, `set_nbt`,
or anything non-spatial. Empty by default, so 0.4.x behaviour is unchanged until an operator opts in.

Tools declare their footprint via `Tool.declareScope`, which the dispatcher calls on the transport
thread before the approval decision. A tool that does not override it is never exempted — an
undeclared footprint is treated as unbounded.

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
| `server_diagnose` | optional component flags, bounded limits, `timeout_ms` | schema version, total budget, `complete`, component status (including `timeout`), server/tick/world snapshot, incident ledger, recent error logs, server threads, mods, newest crash-report summary, optional spark stats, and conservative `diagnosis.findings` |
| `get_server_stats` | none | `{ tps, mspt, mem_used_mb, mem_max_mb, loaded_chunks, online, max_players }` |
| `tick_profile` | none | rolling 100-tick distribution (avg/max/p50/p95/p99 mspt) |
| `tick_incidents` | `limit?` (1..32, default 16) | timing-only history of ticks above 50 ms, with recent sample count over the last 10 seconds; continuous pressure is coalesced and causal attribution is intentionally left to spark |
| `thread_dump` | none | server thread dump |
| `get_recent_events` | `{ since_seq?, topics?, limit? }` | event-bus tail — see the topic list below |
| `get_recent_logs` | `{ since_seq?, level?, limit? }` | server log tail |
| `subscribe_events` / `unsubscribe_events` | `{ topics }` | per-WebSocket subscription (no-op on stateless MCP HTTP) |

### Event topics

0.4.x recorded four topics. That is enough to know who is around and not enough to know what happened:
"who griefed spawn" and "why did the shop chest empty" had no answer, because the events that would say
so were never recorded.

| Topic | Since | Notes |
|---|---|---|
| `chat` / `player_join` / `player_leave` / `player_death` | 0.1 | `player_death` now also carries the rendered `death_message` and the killer's type/UUID |
| `command` | 0.5.0 | The highest-value audit topic — "who ran `/gamemode creative`" is the first question in most incident reports |
| `container_open` | 0.5.0 | Player, position, and menu type |
| `entity_death` | 0.5.0 | Non-player deaths, **only when a player caused them** — otherwise every zombie burning at dawn floods the buffer |
| `player_respawn` / `dimension_change` / `player_hurt` | 0.5.0 | `player_hurt` filters out sub-1.0 chip damage |
| `advancement` | 0.5.0 | Recipe unlocks excluded; they fire constantly and are not progression signals |
| `explosion` | 0.5.0 | Epicenter, blocks destroyed, entities affected, and the indirect cause (the player who lit it) |
| `server` | 0.5.0 | agent-link's own notes, including throttle warnings |
| `block_place` / `block_break` / `item_pickup` / `item_drop` | 0.5.0, **opt-in** | Usually what an investigation wants, and also what one player with an efficiency-V pickaxe emits hundreds of per minute |

The verbose four are gated behind `events.verbose_topics` in the config. Recording them
unconditionally would push everything else out of the ring buffer within a minute, which defeats the
"what happened a few minutes ago" use case the pull model exists for. Even when enabled, each player is
capped at 40 verbose events per 10 s window, and a drop emits one note on the `server` topic — so an
agent knows counts are incomplete rather than assuming nothing happened.

The ring buffer grew from 1024 to 4096 entries to accommodate the wider topic set.

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
| `read_config` | `{ name?, mode? }` | reads one of an allowlisted config name (`server.properties`, `agent-link.toml`, `whitelist.json`, `ops.json`, ...). `mode="list"` returns the allowed names. Guest results redact credentials and operator identities; console-tier reads preserve the original text. |
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

WorldEdit and the native writers keep **separate undo stacks**. `we_undo` reverses `we_*` edits;
`undo_blocks` reverses `set_block` / `fill_blocks` / `set_blocks` / `restore_block_snapshot`. Merging
them would make "undo the last thing" ambiguous about which subsystem it belonged to.

---

## Self-introspection (0.5.0, auto-allowed)

| Tool | Args | Returns |
|---|---|---|
| `whoami` | none | `caller` (token tier, transport, whether approval is bypassed), `approval` (all four tier lists, admin config, online OPs/admins, eligible approvers, and a `warning` when a GUEST token has nobody who could approve), `writes` (file globs, build zones, native undo depth), `limits` (per-tool sync/task volume caps, task settings), `integrations` (WorldEdit impl + version, spark), `runtime` (task count, audit state, registered addon tools) |

## Async tasks (0.5.0, auto-allowed)

The problem: MCP is request/response and every layer in between has a timeout — the MCP host, the HTTP
client, an in-game bridge. A three-minute build trips one of them, and when the client gives up the
work keeps running with nobody reading the result. Wrapping the call in a task inverts that.

| Tool | Args | Returns |
|---|---|---|
| `start_task` | `{ tool, args? }` | `{ task_id, status, sliceable, estimated_units?, approval_outcome?, poll_with }` |
| `get_task` | `{ task_id, wait_ms? (≤30000) }` | full record: `status`, `progress{done,total,fraction,percent,message}`, `partial?`, `result?`, `error?`, `terminal` |
| `cancel_task` | `{ task_id }` | the record plus `cancelled` and a note about partial writes |
| `list_tasks` | `{ status?, limit? (default 20, max 200) }` | newest-first summaries; results omitted to keep the listing small |

Two properties worth stating plainly:

- **Approval is not laundered.** `start_task` runs the wrapped tool through the ordinary approval
  pipeline *before* queueing it, and returns `APPROVAL_DENIED` synchronously if the answer is no.
  Otherwise it would be a universal bypass: wrap `run_console_command`, skip the prompt.
- **Sliceable vs not.** Tools implementing `TaskContext.Sliceable` (`fill_blocks`, `set_blocks`,
  `find_blocks`, `restore_block_snapshot`) hop on-thread per slice at `tasks.blocks_per_tick`, so ticks
  keep flowing and progress is reported. Anything else runs as one on-thread unit — the MCP call still
  returns immediately, but tick time is not protected, and the response says so.

Task records are mirrored to `config/agent-link/tasks/<id>.json`. They are deliberately **not** resumed
after a restart: replaying a half-finished edit against a world that may have changed offline is more
dangerous than losing the task.

## Native block writing (0.5.0)

Works without WorldEdit. Before this, every mutating capability was either `run_console_command
"/setblock ..."` (no undo, one block per console call, output to parse, and an arbitrary-console
approval) or required WorldEdit to be installed.

| Tool | Tier | Args | Notes |
|---|---|---|---|
| `set_block` | 4 † | `{ pos \| x,y,z, block, dim? }` | Full vanilla block grammar including properties and block-entity NBT, so `chest[facing=north]{Items:[…]}` places an oriented, pre-filled chest in one call. Returns `previous_block`. |
| `fill_blocks` | 4 † | `{ min, max, block, replace?, mode?, dim? }` | `mode`: `replace_all` (default) / `hollow` (shell only) / `outline` (shell + interior cleared to air). `replace` takes one id or a list; a bare id matches any blockstate, adding properties narrows it. Sync ≤ 32 768; via `start_task` ≤ 4 000 000. |
| `set_blocks` | 4 † | `{ blocks[], palette?, dim? }` | Arbitrary position sets — for anything that is not a box. Each entry is `{pos, block}` or `{pos, p}` indexing `palette`. The palette form exists because repeating the block id on every entry dominates the request body, which the agent pays for in tokens. Sync ≤ 20 000; via task ≤ 1 000 000. |
| `undo_blocks` | 4 | `{ mode? ("undo"/"list"), steps? (1-32) }` | Reverses native writes. `mode:"list"` inspects the stack without touching the world. In-memory only: does not survive a restart. |
| `restore_block_snapshot` | 4 † | `{ name, offset?, dim?, skip_air? }` | Closes the loop `save_block_snapshot` opened in 0.2.4. With `offset` it is a working copy-paste. Caveat surfaced in the response: the snapshot format stores bare block ids, so properties restore as defaults and block entities are not captured. |
| `list_snapshots` | 1 | none | Names, dimension, bounds, volume, creation time. |
| `find_blocks` | 1 | `{ min, max, blocks, limit?, count_only?, group_by_block?, dim? }` | Scans server-side and returns only hits plus `counts_by_block`. "Where are the chests in this base" is one call here versus ~1200 paged `get_blocks_region` calls and client-side RLE decoding. Sync ≤ 4 000 000; via task ≤ 64 000 000. |

† Exempt from the prompt when the whole footprint sits inside a `build_zones` region.

Every native write records the prior `BlockState` (plus block-entity NBT) for each position it actually
changed, and pushes the batch as **one** undo entry. Identical states are skipped rather than
rewritten, which keeps the snapshot honest and avoids pointless chunk dirtying. Bulk fills defer
neighbour updates to a single pass at the end — sending one per block is what makes naive `/setblock`
loops melt a server.

## NBT read/write (0.5.0)

The general escape hatch. The structured readers each expose a hand-picked projection —
`get_container` gives item ids and counts, `get_player_info` gives vitals — which covers common
questions and nothing else. Enchantments, villager trades, spawner contents, banner patterns, and every
modded block entity's internals were simply unreachable.

| Tool | Tier | Args | Notes |
|---|---|---|---|
| `get_nbt` | 1 | `{ target? ("block"/"entity"/"player"/"item"), pos \| x,y,z, uuid?, name?, slot?, path?, dim? }` | Returns both `snbt` (canonical, lossless) and `nbt` (JSON projection with a `__types` sibling map so a byte is distinguishable from an int). `path` uses vanilla's own NBT-path grammar. |
| `set_nbt` | 4 | `{ target?, mode? ("merge"/"set"), snbt \| value, path?, … }` | `merge` (default) deep-merges and leaves unmentioned keys alone; `set` replaces the value at `path` and requires one. |

Two design points:

- **Prefer `snbt` for writes.** JSON cannot express a byte versus an int, and `Count:1` where vanilla
  expects `Count:1b` yields an item that silently vanishes. The JSON `value` path is accepted for
  convenience (integral → int, fractional → double) and the round-trip of our own output is faithful
  because `__types` is honoured, but the lossless path is the documented one.
- **Structural keys are refused.** `UUID`, `id`, `Pos`, `Dimension`, `Passengers`, `RootVehicle` on
  entities; `x`/`y`/`z`/`id` on block entities. Rewriting those doesn't edit an object, it produces a
  broken one that may fail on the next save — and the right tools for those effects (`teleport`,
  `modify_entity`) exist alongside.
- Lists are **replaced**, not merged element-wise. Index 0 of an inventory is not "the same object" as
  index 0 of the payload, so replacement is the honest behaviour; use a targeted `path` to edit one
  element.

## Player / entity / world control (0.5.0)

All of these were previously reachable only by asking for arbitrary-console permission and parsing chat
output. Each one here takes typed arguments, validates them, and returns a structured before/after.

| Tool | Tier | Args | Notes |
|---|---|---|---|
| `teleport` | 4 | `{ name \| uuid, to \| to_player, to_surface?, dim?, yaw?, pitch? }` | Handles cross-dimension moves without the `/execute in <dim> run tp` dance. `to_surface` snaps Y to the highest solid block — useful when the agent has good X/Z from a map and no idea what Y is standing room. Rejects a Y far outside the build range, which would otherwise be a slow void death rather than an error. |
| `give_item` | 4 | `{ name, item, count?, drop_overflow? }` | Full vanilla item grammar, so enchantments and custom names work. Reports `added_to_inventory` / `dropped_on_ground` / `not_delivered` instead of silently scattering the remainder like `/give`. |
| `set_gamemode` | 4 | `{ name, gamemode }` | Returns `previous_gamemode` so the agent can restore it. |
| `apply_effect` | 4 | `{ name \| uuid, mode?, effect?, seconds?, amplifier?, ambient?, show_particles?, show_icon? }` | Protecting a subject while working (night vision + fire resistance in the nether, slow falling before a teleport) or clearing a debuff the agent caused. `mode:"clear"` with no `effect` removes everything. Instant effects ignore duration and say so. |
| `spawn_entity` | 4 † | `{ type, pos, count?, nbt?, custom_name?, name_visible?, no_ai?, persistent?, dim? }` | NBT support means a named, equipped, no-AI armour stand is one call rather than spawn-then-patch. Count capped at 64; `minecraft:player` refused. |
| `remove_entities` | 4 | `{ uuid \| center + radius, types?, categories?, mode?, dry_run?, limit?, dim? }` | The standard fix for lag from dropped items or piled-up mobs. **Players are never removed** and there is no flag for it. An unfiltered bulk call defaults to a preview returning `counts_by_type`. `discard` removes silently with no drops; `kill` runs normal death handling. Radius capped at 128 — there is no "everything, everywhere" mode. |
| `modify_entity` | 4 | `{ name \| uuid, custom_name?, name_visible?, silent?, invulnerable?, glowing?, no_gravity?, fire_seconds?, health?, no_ai?, persistent? }` | Validated alternative to hand-writing NBT tag types for the handful of things actually asked for. Every field optional; reports before/after per field. `set_nbt` remains the escape hatch. |
| `set_world_property` | 4 | `{ property: "time"/"weather"/"difficulty"/"gamerule", value, rule?, seconds?, add?, all_dimensions?, dim? }` | The write side of `get_world_info`, which could previously only report these. Gamerule names and value types are checked against the real registry, so a typo or a boolean in an integer rule is an error here rather than a silently ignored command. |
| `force_load_chunks` | 4 | `{ mode? ("add"/"remove"/"list"/"clear"), min+max \| chunk+chunk_radius, dim? }` | Almost every world tool silently depends on the chunk being loaded; without this, reads return "not found" and writes can be discarded when the chunk generates later. Capped at 1024 chunks/call, and the response reminds the caller that forced chunks tick forever and persist across restarts. |
| `save_world` | 4 | `{ flush? }` | Makes a checkpoint explicit after a large build, so a crash cannot falsify a "done" report. Deliberately narrow — it does not stop the server or toggle autosave. Warns when the save took long enough that players felt it. |
| `manage_players` | 4 | `{ action, name? \| uuid?, player_name?, ip?, reason?, duration_seconds?, level?, bypass_player_limit? }` | Structured kick, player/IP ban and pardon, OP/deOP, whitelist add/remove/enable/disable/reload, and list inspection. Offline profiles must be online, cached, or identified by `uuid` plus `player_name`. |
| `manage_player_inventory` | 4 | `{ action, name, slot?, item?, count?, first_slot?, second_slot? }` | Clear inventory or a slot, replace a slot with full item grammar, swap slots, select a hotbar slot, or intentionally drop a slot. This complements `give_item`; it does not silently drop overflow. |
| `control_entity` | 4 | `{ action, name? \| uuid?, rider/vehicle refs?, amount?, x/y/z?, yaw/pitch?, slot?, item?, attribute?, base_value?, confirmed?, allow_player? }` | Mount/dismount, typed damage/heal, kill/discard, velocity, rotation, equipment, and base attributes. `kill`/`discard` require `confirmed:true`; player targets additionally require `allow_player:true`. |
| `set_world_spawn` | 4 | `{ pos \| x+y+z, angle?, dim?, all_dimensions? }` | Set the shared spawn point for one dimension or all loaded dimensions. |
| `set_world_border` | 4 | `{ action?, dim?, center?, x?, z?, size?, from?, to?, duration_ticks?, damage_per_block?, safe_zone?, warning_blocks?, warning_seconds? }` | Typed world-border read, center/size/lerp, damage/warning settings, and reset. |
| `server_control` | 4 | `{ action, flush?, confirmed?, chunks?, enabled? }` | Save, reload selected resources, change view/simulation distance, toggle cheats for all players, or stop. `stop` requires `confirmed:true`; JVM restart belongs to an external supervisor. |
| `manage_container` | 4 | `{ action, dim?, pos?, x/y/z?, slot?, item?, count?, first_slot?, second_slot? }` | Typed writes to chest/hopper/dispenser/barrel/shulker slots: replace, clear, clear all, or swap. `get_container` remains the read/audit view. |
| `set_player_state` | 4 | `{ name, health?, absorption?, food?, saturation?, air?, experience_points?, experience_levels?, fire_seconds?, invulnerable?, mayfly?, flying? }` | Typed vitals, XP and ability flags with a before/after snapshot. |
| `manage_player_progression` | 4 | `{ action, name?, recipe(s)?, advancement?, criterion?, contains?, limit? }` | List recipes; grant/revoke recipe ids; inspect or grant/revoke advancement criteria. `criterion:"*"` means all criteria. |
| `manage_datapacks` | 4 | `{ action?, id?, ids? }` | List available/selected data packs, enable/disable selection, and reload selected resources. At least one pack must remain selected. |

The new operator tools are deliberately grouped by domain rather than exposing one giant command
proxy. They are all admin-only by default and are not whole-tool-trustable. `run_console_command`
remains the escape hatch for mod-specific commands and vanilla features that cannot be represented
without importing another mod's private API.

† Exempt from the prompt when inside a `build_zones` region.

---

## Operator commands

| Command | Effect |
|---|---|
| `/agent <text>` | ADMIN or OP: queue an in-game request to the connected agent (provided by `mc-agent-link-agent`) |
| `/agent status` / `cancel <id>` / `reload` | inspect / cancel / reload addon config |
| `/agentlink pair` | generate/reopen a one-time local MCP setup endpoint as a **CONSOLE** token (skips in-game approval) |
| `/agentlink pair-guest` | generate/reopen a one-time local MCP setup endpoint as a **GUEST** token (always goes through in-game approval) |
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

`/agentlink` subcommands are available to the console, OPs, and assigned admins. Admin-only approvals additionally require the actor's UUID to be in `[roles].admin_uuids`.

## Audit log

When `audit.enabled = true` (default), every MCP tool invocation appends a single JSON line to `logs/agentlink-audit.log`. Read with `/agentlink audit tail` or feed the file into `jq`:

```jsonl
{"ts":"2026-05-26T18:33:00Z","tool":"run_console_command","outcome":"approved","actor":"void","actor_uuid":"...","reason":"approved by void","result_ok":true,"args_json":"{\"command\":{\"redacted\":true,\"length\":18}}"}
```

Sensitive fields are redacted via `audit.redact_args` (`tool.argkey` format). Defaults: `run_console_command.command`, `write_config_file.content`, `write_config_file.base64`, `read_config.content`, `set_nbt.snbt`, `set_nbt.value`. Extend the list for any addon tool whose arg or returned content is sensitive.

The `outcome` field gained `build_zone` in 0.5.0, alongside the existing `auto_allow` / `trusted` /
`console_trusted` / `approved` / `denied` / `timed_out`. The `tier` field is now recorded correctly for
every call — before 0.5.0 it was captured after the dispatcher's thread hop, so it always read `guest`
regardless of which token was used.

## New config keys in 0.5.0

| Key | Default | Purpose |
|---|---|---|
| `build_zones` | `[]` | Array of `{label, dim, min, max}` tables. Regions where spatial writes skip approval. Empty = 0.4.x behaviour. |
| `tasks.max_concurrent` | `2` | How many async tasks run at once (max 8). More concurrency means more work competing for the same per-tick budget. |
| `tasks.blocks_per_tick` | `8000` | Per-tick block budget for a sliced edit (64..200000). Lower is gentler on TPS and slower. |
| `events.verbose_topics` | `[]` | Which high-frequency topics to record: `block_place`, `block_break`, `item_pickup`, `item_drop`. |

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

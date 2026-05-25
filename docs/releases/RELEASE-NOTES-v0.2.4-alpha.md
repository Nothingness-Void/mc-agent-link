# mc-agent-link v0.2.4-alpha

## Highlights

10 new tools that round out the read-only "agent looks around" surface and add a couple of tier-4 admin features. All read-only additions ship in `auto_allow_tools` by default.

### Tier-A · catalog & command help (auto-allowed)

| Tool | Why |
|---|---|
| `command` | Brigadier dry-run. `mode="suggest"` returns Tab suggestions; `mode="parse"` reports syntax errors. Lets agents validate `/give Player ` before running it instead of trial-and-error through `run_console_command`. |
| `find_players` | Resolve a vanilla `@a[...]` selector. Optional `anchor` / `anchor_player` for relative selectors like `@a[distance=..16]`. |
| `get_item_info` | Static catalog for one item or block id: stack size, durability, fuel value, edibility, fire resistance, tags, default block state. |
| `get_recipes_for` | Every recipe whose result is the given item — crafting (shaped/shapeless), smelting/blasting/smoking/campfire, stonecutting, smithing. Datapack changes are picked up live. |
| `get_block_drops` | Compute loot table drops under a configured tool/fortune/silk_touch. `rolls` (1-1000) averages over multiple iterations to surface a probability distribution. |

### Tier-C · admin convenience

| Tool | Tier | Notes |
|---|---|---|
| `get_container` | 4 (admin-only) | Read chest/furnace/hopper/etc. contents without triggering the open animation. Writes a one-line audit log entry per call. |
| `read_config` | 1 | Allowlisted reads of `server.properties`, `agent-link.toml`, `whitelist.json`, `ops.json`, ... `mode="list"` returns the allowed names. Bypasses the more permissive `read_server_file` sandbox by exposing only well-known files. |
| `save_block_snapshot` | 1 | Save a region (volume ≤ 65536) palette+RLE to `config/agent-link/snapshots/<name>.json`. Pure read side. |
| `get_scoreboard` | 1 | Read objectives, scores under one objective, or teams + members. |

### Broadcast extended

`broadcast` now accepts:

- `message` + optional `color` (existing behavior, still supported)
- `components` — raw tellraw component JSON (object or array). Lets agents send clickable / multi-style messages without composing `/tellraw` syntax.
- `target` — `@a` (default), a player name, or a UUID. Single-player whispers without `run_console_command`.

Exactly one of `message` / `components` must be provided.

### `get_world_info` accepts `dim`

Optional `dim` argument lets you query a specific dimension's time/weather/border/spawn. Useful on multi-dim modded servers (Twilight Forest, Mining Dimension, ...). Without `dim` it still returns the overworld + per-dimension summary as before.

## Approval defaults

`approval.auto_allow_tools` extended with `command`, `find_players`, `get_item_info`, `get_recipes_for`, `get_block_drops`, `read_config`, `save_block_snapshot`, `get_scoreboard`.

`approval.admin_only_tools` extended with `get_container` (sniffing block contents bypasses the visible open animation; treat as snooping).

Existing tomls keep their old `auto_allow_tools` list — to opt into the new defaults, delete that line and let the mod rewrite it.

## Compatibility

- Old `agent-link.toml` keeps working. New tools default to "approve every call" until you either add them to `auto_allow_tools` (or `admin_only_tools` for `get_container`) or use the in-game `[始终允许该工具]` button.
- `mc-agent-link-agent 0.3.4-alpha` bumps `base_agentlink_version_range` to `[0.2.4-alpha,)`. The addon itself is unchanged; the new tools are server-side only and the agent picks them up through MCP `tools/list` automatically.

## Reference

`docs/tools.md` has the full tool catalog with every arg/return.

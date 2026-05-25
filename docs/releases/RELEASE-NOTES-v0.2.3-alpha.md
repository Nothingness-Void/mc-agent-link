# mc-agent-link v0.2.3-alpha

## Highlights

A "**look around**" pass: the base mod now exposes enough world / player / entity / registry tools that an agent can know where the player stands, what they're looking at, what's around them, what the world looks like — without ever touching `run_console_command`. All of these tools are auto-allowed by default (read-only; ordinary OPs already have equivalent vanilla access).

### `get_player_info` (extended)

Existing shape stays compatible. New fields:

- `block_pos`, `facing` (`north/south/east/west`), `look_vector`, `look_axis`
- `held_main`, `held_off`, `selected_slot`, `main_arm`, `op` flag
- vitals: `absorption`, `saturation`, `air`, `max_air`, `on_fire`, `fire_ticks`, `on_ground`, `in_water`, `sneaking`, `sprinting`
- progression: `xp_progress`, `xp_total`
- `effects[]` — every active potion effect (id/amplifier/duration/ambient/visible)
- `look_target` — 5-block raycast describing the block the player is looking at (pos/block/face/distance), or `null`

### New tools

| Tool | What it answers |
|---|---|
| `get_player_inventory` | full inventory snapshot (hotbar + main + armor + offhand) |
| `get_world_info` | game time, day, moon phase, weather, difficulty, hardcore, gametype, level name, seed, overworld spawn + border, gamerules, dimensions[] |
| `get_block` | one block at (x,y,z) with state properties, biome, light, block entity |
| `get_blocks_region` | cuboid up to 4096 blocks, palette + RLE in y-z-x order |
| `get_biome` | biome id + climate at a point |
| `raycast` | generic block raycast with caller-chosen origin/direction (≤64 blocks) |
| `list_entities_near` | sphere scan, optional types/ids filter, ≤64 radius, ≤256 hits |
| `list_dimensions` | every loaded dimension's bounds |
| `list_block_ids` / `list_item_ids` / `list_entity_ids` / `list_biome_ids` | paginated registry browsing with substring filter |

All new tools are added to `approval.auto_allow_tools` defaults so a fresh install just works.

## Approval defaults

`approval.auto_allow_tools` now includes the 13 new read-only tools above. Existing toml files keep their old list — to opt into the new defaults, delete the `auto_allow_tools` line and let the mod rewrite it.

`approval.admin_only_tools` unchanged: `run_console_command`, `write_config_file`, `broadcast`, `spark_profiler_*`, `read_server_file`, `list_dir`.

## Compatibility

- Old `agent-link.toml` keeps working. New tools default to "approve every call" until you either add them to `auto_allow_tools` or use the in-game `[始终允许该工具]` button.
- MCP `tools/list` advertises every new tool with full JSON Schema, so MCP hosts (Claude Code, Cursor, …) discover them automatically.
- Companion addon `mc-agent-link-agent 0.3.2-alpha` is unchanged and continues to work; bump its `base_agentlink_version_range` to `[0.2.3-alpha,)` if you want to require these tools.

## Reference

See `docs/tools.md` for the full tool catalog with args/returns.

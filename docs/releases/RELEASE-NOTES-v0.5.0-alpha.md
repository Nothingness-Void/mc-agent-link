# mc-agent-link v0.5.0-alpha

**Theme: the agent can now change the world, not just describe it.**

0.4.x could read almost everything and write almost nothing. Every mutating capability was either
`run_console_command "/setblock …"` — no undo, one block per call, chat text to parse, and a
permission grant equivalent to a root shell — or required the operator to have installed WorldEdit.
This release adds a first-class write surface that always exists, an async task system so long work
survives client timeouts, NBT as a general escape hatch, and a geometric permission model so
building doesn't mean clicking "allow" three hundred times.

30 new tools (44 → 75). Base and addon version numbers stay unified.

---

## Async tasks — `start_task` / `get_task` / `cancel_task` / `list_tasks`

MCP is request/response, and every layer in between has a timeout: the MCP host, the HTTP client, an
in-game bridge. A three-minute build trips one of them, and when the client gives up the work keeps
running with nobody reading the result. That is the failure that motivated this whole release.

- `start_task { tool, args }` returns a `task_id` in milliseconds.
- `get_task { task_id, wait_ms? }` polls; `wait_ms` blocks server-side (max 30 s) so one call
  replaces ten.
- Sliceable tools (`fill_blocks`, `set_blocks`, `find_blocks`, `restore_block_snapshot`) additionally
  spread work across ticks at `tasks.blocks_per_tick`, reporting progress. Measured: a 270 000-block
  fill finished in ~3 s with p99 tick time at 0.81 ms — indistinguishable from idle.
- **Approval is not laundered.** The wrapped tool goes through the normal approval pipeline *before*
  queueing, synchronously. Wrapping `run_console_command` in a task returns `APPROVAL_DENIED` from
  `start_task` itself. Without this the tool would be a universal bypass.
- Cancellation is cooperative: a task stops at a slice boundary, and blocks already written stay
  written — recoverable via `undo_blocks`, which is better than an interrupted thread leaving a chunk
  inconsistent.
- Records persist to `config/agent-link/tasks/<id>.json` as history. Tasks are deliberately **not**
  resumed after a restart: replaying a half-finished edit against a world that may have changed
  offline is more dangerous than losing the task.

## Native block writing — no WorldEdit required

`set_block`, `fill_blocks`, `set_blocks`, `undo_blocks`, `restore_block_snapshot`, `list_snapshots`,
`find_blocks`.

- Full vanilla block grammar including blockstate properties and block-entity NBT, so
  `chest[facing=north]{Items:[…]}` places an oriented, pre-filled chest in one call.
- `fill_blocks` modes: `replace_all` / `hollow` (shell only) / `outline` (shell + interior cleared).
  `replace` filters by current block; a bare id matches any blockstate, adding properties narrows it.
- `set_blocks` writes an arbitrary position set as **one** operation — for anything that isn't a box.
  A palette form avoids repeating block ids on every entry, which otherwise dominates the request
  body and costs the agent tokens.
- `restore_block_snapshot` closes the loop `save_block_snapshot` opened in 0.2.4. With `offset` it's a
  working copy-paste. The response states the format's limits rather than hiding them: bare block ids
  mean properties restore as defaults and block entities aren't captured.
- `find_blocks` searches server-side and returns only hits plus per-block counts. "Where are the
  chests in this base" was ~1200 paged `get_blocks_region` calls plus client-side RLE decoding; it's
  now one call.
- Undo: each operation records the prior `BlockState` (plus BE NBT) for every position it actually
  changed and pushes the batch as one entry. Identical states are skipped, keeping the snapshot
  honest. Neighbour updates are deferred and drained per slice — issuing one per block is what makes
  naive `/setblock` loops melt a server.

Volume limits are explicit and self-documenting: exceeding a synchronous cap returns
`VOLUME_TOO_LARGE` **with the exact `start_task` call to use instead**, so the agent doesn't invent
its own region splitting.

## NBT — `get_nbt` / `set_nbt`

The structured readers each exposed a hand-picked projection, which covered common questions and
nothing else. Enchantments, villager trades, spawner contents, banner patterns, and every modded
block entity's internals were unreachable.

- Reads return both `snbt` (canonical, lossless) and `nbt` (JSON projection with a `__types` sibling
  map, so a byte is distinguishable from an int). `path` uses vanilla's own NBT-path grammar.
- Writes prefer `snbt`. JSON cannot express a byte versus an int, and `Count:1` where vanilla wants
  `Count:1b` yields an item that silently vanishes — so the lossless path is the documented one, with
  JSON accepted as a convenience.
- `merge` deep-merges; `set` replaces at a `path` and requires one. Lists are replaced, not merged
  element-wise — index 0 of an inventory is not "the same object" as index 0 of the payload.
- Structural keys are refused (`UUID`, `id`, `Pos`, `Dimension`, `Passengers`, `RootVehicle` on
  entities; `x`/`y`/`z`/`id` on block entities). Rewriting those produces a broken object that fails
  on the next save, and the right tools for those effects already exist.

## Player / entity / world control

`teleport`, `give_item`, `set_gamemode`, `apply_effect`, `spawn_entity`, `remove_entities`,
`modify_entity`, `set_world_property`, `force_load_chunks`, `save_world`.

Each replaces an arbitrary-console workaround with typed arguments, validation, and a structured
before/after. Details worth calling out:

- `teleport` handles cross-dimension moves without the `/execute in <dim> run tp` dance, and
  `to_surface` snaps Y to solid ground — the common case when the agent has good X/Z from a map.
- `remove_entities` **never** removes players, and there is no flag for it. An unfiltered bulk call
  defaults to a preview returning `counts_by_type`; radius is capped at 128. There is no
  "everything, everywhere" mode, which is the shape of `/kill @e` that ruins servers.
- `set_world_property` validates gamerule names and value types against the real registry, so a typo
  or a boolean in an integer rule is an error rather than a silently ignored command.
- `force_load_chunks` exists because almost every world tool silently depends on the chunk being
  loaded — reads return "not found", writes can be discarded. The response reminds the caller that
  forced chunks tick forever and persist across restarts.
- `spawn_entity` warns when the target chunk isn't loaded: the entity is created but absent from the
  live index, so a follow-up `get_nbt` by UUID would report `NOT_FOUND` and look like a bug
  elsewhere.

## `whoami` — the agent can read its own boundaries

Previously an agent discovered its limits by hitting them: call a tool, get `APPROVAL_DENIED`, guess
whether the fix was "wait for an OP", "ask the operator to widen `write_allow`", or "this token can
never do that". One read-only call now returns token tier, all four approval tiers, whether an
eligible approver is even online, file-write globs, build zones, per-tool volume limits, and which
optional integrations are installed. It distinguishes "not permitted" from "nobody online to permit
it" — advice to the user differs completely between those.

## Build zones — bounding the permission geometrically

Every mutating spatial tool sat in `admin_only_tools`, so a build was hundreds of approval clicks;
an operator clicking that many times isn't exercising judgment. Blanket-trusting the tool is the
opposite failure — the agent could then flatten spawn.

```toml
build_zones = [{label = "agent plot", dim = "minecraft:overworld", min = [300, -64, 300], max = [340, 320, 340]}]
```

A call is exempt only when its **entire** footprint fits inside one zone. An edit straddling the
boundary still prompts and is **never** clipped to fit. Applies to spatial writes only; deliberately
not to `run_console_command` (a footprint can't be inferred from a string the server didn't parse),
`write_config_file`, or `set_nbt`. Empty by default, so 0.4.x behaviour is unchanged until an operator
opts in. Audited as `outcome: build_zone`.

## Event coverage

0.4.x recorded four topics — enough to know who is around, not enough to know what happened. "Who
griefed spawn?" had no answer because the events that would say so were never recorded.

Added: `command` (the highest-value audit topic), `container_open`, `entity_death`, `player_respawn`,
`dimension_change`, `advancement`, `explosion`, `player_hurt`, `server`. Opt-in via
`events.verbose_topics`: `block_place`, `block_break`, `item_pickup`, `item_drop`.

The verbose four are gated because one player with an efficiency-V pickaxe emits hundreds per minute
and would evict everything else within a minute. Even when enabled, each player is capped at 40 per
10 s window and a drop emits a note on the `server` topic — so an agent knows counts are incomplete
rather than concluding nothing happened. Ring buffer raised 1024 → 4096.

`run_console_command` now also publishes a `command` event. It executes through the Brigadier
dispatcher directly (to capture output), which bypasses Forge's `CommandEvent`, so without this the
agent's own commands were the only ones missing from the history it reviews.

## Fixes

- **`tier` in the audit log was always `guest`.** It was read after the dispatcher's thread hop, so
  a CONSOLE token's calls were recorded as GUEST — i.e. the audit trail misattributed privilege on
  every single line. Now captured on the transport thread and propagated.
- **Coordinate arguments accept `[x,y,z]` everywhere.** `get_blocks_region`, `save_block_snapshot`,
  `list_entities_near`, `raycast`, `we_set`, `we_replace`, `we_sphere`, `we_cyl` previously required
  the `{x,y,z}` object form while the new tools took both. An agent that restored with arrays got
  `INVALID_ARGS` trying to save the same way.
- **Upgrade migration for the approval tables.** `auto_allow_tools` / `admin_only_tools` persist to
  the toml, so on an upgraded server the stored lists win and tools added later land in neither.
  That silently did the wrong thing in both directions: new read-only tools became tier-3 (an OP had
  to click for `whoami`, the tool that explains why things need clicking), and new mutating tools
  became tier-3 rather than tier-4 — meaning any OP could approve `set_nbt` instead of only a
  configured admin. A security regression caused purely by upgrading. Migrations are additive,
  generation-stamped in `approval.table_version`, and respect entries the operator deleted.
- `set_world_property` reports the requested end state for time and weather rather than reading the
  live flags back in the same tick, which returned the state just replaced and looked like a failed
  write.

## Threading

Tools may now declare `offThread()`, in which case the dispatcher runs them on a small worker pool
instead of the tick thread — used for file I/O, waiting on an approval future, and sliced writes that
hop back on-thread per slice via `ServerThread.call`. World access itself is still always on the tick
thread; `ServerLevel` is not thread-safe.

## New config keys

| Key | Default | Purpose |
|---|---|---|
| `build_zones` | `[]` | Regions where spatial writes skip approval |
| `tasks.max_concurrent` | `2` | Concurrent async tasks (max 8) |
| `tasks.blocks_per_tick` | `8000` | Per-tick budget for a sliced edit (64..200000) |
| `events.verbose_topics` | `[]` | Which high-frequency topics to record |
| `approval.table_version` | `1` | Migration generation marker |

## Verified on the test server

Forge 1.20.1 + spark 1.10.53 + WorldEdit 7.2.15. All 75 tools present in `tools/list` with
descriptions and schemas. Exercised: native writes and undo round-trip, block-entity NBT placement
and path reads/writes, snapshot save/restore with offset, a 270 000-block sliced task (p99 0.81 ms),
cancellation with partial-write rollback, build-zone exemption plus boundary and non-spatial
rejection, `start_task` refusing to launder `run_console_command`, gamerule type validation, the
approval-table migration on an existing 0.4.0 config, and WorldEdit regression across all six `we_*`
tools. No exceptions in the server log.

# mc-agent-link v0.2.6-alpha

## Highlights

WorldEdit / FAWE integration. 6 new `we_*` tools let agents build large structures
(spheres, cylinders, fills, replacements) and undo them — all routed through
WorldEdit so FAWE's async writer is used when present, and undo is real (not just
re-running our own snapshot logic).

This is the foundation for the "agent builds sculptures" use case discussed in chat.
A separate single-call `paint_image_from_file` is still queued for a later release;
this one focuses on the geometry primitives plus the safety net of `we_undo`.

## What's new

### `we_status` — tier 1, auto-allowed

Probe WE installation. Returns:

```json
{
  "available": true,
  "implementation": "FastAsyncWorldEdit",
  "version": "7.3.0",
  "fawe_version": "...",
  "undo_depth": 3
}
```

When neither WE nor FAWE is installed, `available=false` and a `hint` field tells
the agent how to install it. Every other `we_*` tool refuses with `WE_NOT_AVAILABLE`
in that case.

### `we_set` — fill cuboid (tier 4)

```json
{ "min": {"x":0,"y":64,"z":0}, "max": {"x":50,"y":80,"z":50}, "block": "minecraft:stone" }
```

Volume cap 200 000. Goes through WE's `replaceBlocks` so the operation is undoable
and FAWE's async chunk writer is used when available.

### `we_replace` — conditional fill (tier 4)

```json
{ "min": {...}, "max": {...},
  "from": ["minecraft:stone","minecraft:cobblestone"],
  "to":   "minecraft:deepslate" }
```

`from` can be a single block id or an array. Volume cap 200 000.

### `we_sphere` / `we_cyl` — geometry primitives (tier 4)

Sphere: `{ center, radius (≤50), block, hollow? }` — calls WE `makeSphere`.

Cylinder: `{ center, radius (≤50), height (≤256), block, hollow? }` — calls WE
`makeCylinder`. `center` is the bottom-center.

These two are the killer feature for sculpting — `we_sphere` + `we_replace` is
how you carve a stone giant's head with a recess for the eye in three calls
instead of three thousand.

### `we_undo` — undo the agent's recent ops (tier 4)

```json
{ "steps": 3 }
```

Pops the most recent N (1-10) `EditSession`s off the agent-shared undo stack and
reverses each via `EditSession.undo(EditSession)`. Returns total blocks restored
and the remaining stack depth.

The stack is **shared across all agent calls** — there's no per-MCP-session
isolation. Assumption: one agent per server. The stack is bounded at 50.

## Architecture

`world.agentlink.we.WorldEditBridge` mirrors the spark integration shape:

- `compileOnly 'com.sk89q.worldedit:worldedit-core:7.3.0'` — type checking only
- All runtime touches go through reflection
- Probe flow: `Class.forName("com.sk89q.worldedit.WorldEdit")` → `getInstance()`,
  cached
- FAWE detection: presence of `com.fastasyncworldedit.core.Fawe` flips
  `implementation()` to `"FastAsyncWorldEdit"`

EditSession lifecycle:

1. `WorldEditBridge.performEdit(level, op)` adapts the Forge `ServerLevel` to a
   WE `World` via the platform adapter (`ForgeAdapter` / `NeoForgeAdapter` / FAWE
   variant)
2. New `EditSession` built via `WorldEdit.newEditSessionBuilder().world(...).build()`
3. The functional `op` runs against an `EditContext` that exposes `setBlock`,
   `replaceBlocks`, `makeSphere`, `makeCylinder` (all reflective)
4. On success the session is `close()`d and pushed onto the undo stack

## Approval defaults

`approval.auto_allow_tools` extended with `we_status`.

`approval.admin_only_tools` extended with `we_set`, `we_replace`, `we_sphere`,
`we_cyl`, `we_undo`.

Existing tomls keep their old lists — to opt into the new defaults, delete the
`auto_allow_tools` / `admin_only_tools` lines and let the mod rewrite them.

## Audit

Every `we_*` call is captured by the existing 0.2.5 audit log. Args appear in
`args_json` (untruncated for typical fills since the schemas are small), so an
admin running `/agentlink audit tail` can see exactly what an agent built.

## Compatibility

- Old `agent-link.toml` keeps working. WE tools simply return `available=false`
  via `we_status` and refuse via `WE_NOT_AVAILABLE` until WE is installed.
- WE 7.3+ is the tested target; 7.2 should also work via reflective fallbacks
  (the bridge prefers `newEditSessionBuilder()` and falls back to `flushSession()`
  if `close()` isn't there).
- `mc-agent-link-agent 0.3.6-alpha` bumps `base_agentlink_version_range` to
  `[0.2.6-alpha,)`. The addon itself is unchanged.

## Known gaps still open

- **`we_copy` / `we_paste` / `we_schematic_load`** — clipboard ops deferred to
  a follow-up release; the geometry primitives are the higher-leverage starting
  point.
- **No per-call cooldown / rate limit yet** — TESTBOOK item still open. The
  200 000 volume cap and admin-only gating are the current safeguards.

## Reference

`docs/tools.md` updated with the WorldEdit section.

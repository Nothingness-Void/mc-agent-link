---
name: mc-diagnose
description: Diagnose Minecraft server lag end-to-end. Walks tick_profile → thread_dump → optional spark profiler → mod attribution → config recommendation. Use when the user reports lag, low TPS, stutters, "feels slow", high mspt, or anything performance-related on the server connected via agent-link.
---

# Minecraft lag diagnosis

Goal: identify **what** is causing tick spikes and **which mod** is responsible, then propose a concrete fix. The user always confirms before any config write.

## Required tools

From the `agent-link` MCP server: `tick_profile`, `thread_dump`, `list_mods`, `get_recent_logs`, `run_console_command`, `read_server_file`, `list_dir`, `write_config_file`.

If MCP isn't connected, say so and stop.

## Phase 1 — Characterize the spike (parallel)

Call in one round:

- `tick_profile`
- `thread_dump` with `only_server: true`, `max_frames: 30`
- `get_recent_logs` with `levels: ["WARN", "ERROR"]`, `limit: 50`

Decision tree from `tick_profile`:

- **avg ≤ 50 ms, p99 ≤ 50 ms** → server isn't actually lagging. Tell the user what you see and ask whether the lag was player-perceived (network/render) vs. server (TPS).
- **avg > 50 ms** → steady-state overload. Likely a mod doing too much per tick, or memory pressure. Continue.
- **avg fine, p99 ≫ avg** → spikes. Something fires occasionally and stalls a tick. Continue.

From `thread_dump` (`Server thread`): note the top 3-5 frames. If you can already attribute to a mod's package (e.g. `com.foo.bar.SomeMod.onTick`), you have a hypothesis.

## Phase 2 — Sample with spark if available

Try `run_console_command { command: "spark profiler --help" }`.

- **Returns help text** → spark is installed.
  1. Tell the user: "I'll run a 30s spark sample. The server will be under tiny extra load."
  2. `run_console_command { command: "spark profiler start" }`
  3. Wait ~30s (tell the user you're waiting; don't spin-call tools).
  4. `run_console_command { command: "spark profiler stop" }`
  5. The output usually contains a URL (e.g. `https://spark.lucko.me/<id>`). Surface it to the user.
  6. Optionally `list_dir { path: "spark" }` and `read_server_file` on the newest dump if they want offline analysis.
- **Returns "Unknown command"** → no spark. Continue with thread_dump evidence only and mention spark as an optional install.

## Phase 3 — Attribute and recommend

1. `list_mods` (call once; cache for the rest of the conversation).
2. Map the hot package(s) to a mod id from step 1. The mod id usually matches the top-level package.
3. `list_dir { path: "config" }` to see what config files exist for that mod, then `read_server_file` on the most likely candidate (`config/<modid>.toml`, `config/<modid>-common.toml`, etc.).
4. Identify a setting that plausibly relates (entity tick rate, async generation, mob caps, etc.).

## Phase 4 — Propose

Write a short summary:

- **Symptom**: e.g. "p99 mspt = 180 ms, avg = 22 ms — spikes about every 30s"
- **Likely cause**: "stack samples point to `com.foo.SomeMod.WorldTickHandler`"
- **Proposed change**: "set `entityTickRate = 4` (currently `1`) in `config/foo-common.toml`"
- **Tradeoff**: one sentence on what the user gives up

Then ask: "want me to apply that? I'll back up the current file first."

## Phase 5 — Apply (only after explicit yes)

`write_config_file` does the backup automatically. After writing:

1. Tell the user the backup path returned by the tool.
2. Tell them the change won't take effect until reload/restart.
3. Suggest, but don't run: `run_console_command { command: "reload" }` (some servers, this is enough; others need a full restart).

## What not to do

- **Never** run `stop`, `/op`, `/deop`, `/ban`, `/whitelist`, world-mutating commands (`/fill`, `/kill @e`) without an explicit, specific user request.
- **Never** restart the server on your own.
- **Never** broadcast to players unless the user asks.
- If `write_config_file` returns `INVALID_ARGS`, the path was outside `config/`. Don't try to bypass — explain to the user.

---
name: mc-diagnose
description: Diagnose Minecraft server lag end-to-end. Starts with server_diagnose, then uses optional spark profiling, mod attribution, and config recommendation. Use when the user reports lag, low TPS, stutters, "feels slow", high mspt, or anything performance-related on the server connected via agent-link.
---

# Minecraft lag diagnosis

Goal: identify **what** is causing tick spikes and **which mod** is responsible, then propose a concrete fix. The user always confirms before any config write.

## Required tools

From the `agent-link` MCP server: `server_diagnose`, `tick_profile`, `thread_dump`, `list_mods`, `get_recent_logs`, `run_console_command`, `read_server_file`, `list_dir`, `write_config_file`, `spark_status`, `spark_stats`, `spark_profiler_start`, `spark_profiler_stop`, `spark_profiler_cancel`, `spark_health_report`.

If MCP isn't connected, say so and stop.

## Phase 1 — Characterize the spike (parallel)

Call `server_diagnose` once with the defaults. It returns the tick distribution, server-thread
snapshot, recent warning/error logs, installed mods, newest crash summary, and a conservative
`diagnosis.findings` list in one bounded response. Its `collection_duration_ms` is useful when
deciding whether a slow response came from the server or the MCP host.

Decision tree from `server_diagnose.tick_profile`:

- **avg ≤ 50 ms, p99 ≤ 50 ms** → server isn't actually lagging. Tell the user what you see and ask whether the lag was player-perceived (network/render) vs. server (TPS).
- **avg > 50 ms** → steady-state overload. Likely a mod doing too much per tick, or memory pressure. Continue.
- **avg fine, p99 ≫ avg** → spikes. Something fires occasionally and stalls a tick. Continue.

If the snapshot shows spark is installed, call `spark_stats` for richer multi-window TPS/MSPT/CPU/GC data — read-only, very cheap.

From `server_diagnose.threads` (`Server thread`): note the top 3-5 frames. If you can already attribute to a mod's package (e.g. `com.foo.bar.SomeMod.onTick`), you have a hypothesis.

## Phase 2 — Sample with spark if available

If `spark_status` from Phase 1 reported `installed: true`:

1. Tell the user: "Starting a 30s spark sample. I'll auto-stop and bring back the URL."
2. `spark_profiler_start { timeout: 30 }` — `timeout` makes spark auto-stop and upload, so you don't need to spin-poll.
3. After ~30s (or on the user's next message), call `spark_profiler_stop { wait_url_ms: 15000 }`. If spark already auto-stopped, this still retrieves the URL. The response includes `url` when the upload landed.
4. Surface the URL to the user. If `url_present: false`, the upload is still in flight — try `spark_profiler_stop` again with a larger `wait_url_ms`, or read `spark/` via `list_dir` + `read_server_file`.
5. For spike-only sampling, prefer `spark_profiler_start { timeout: 60, only_ticks_over_ms: 50 }` — much shorter, higher signal.
6. Use `spark_profiler_cancel` to abort a sample without uploading.

For a quick TPS/CPU/memory snapshot without a full sample, `spark_health_report` returns a one-shot URL.

If spark is not installed, tell the user spark would give better data and continue with the snapshot's thread evidence only.

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

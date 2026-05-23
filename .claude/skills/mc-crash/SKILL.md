---
name: mc-crash
description: Investigate Minecraft server crashes. Reads the newest crash-reports/*.txt, correlates with installed mods and recent error logs, and proposes a fix. Use when the user mentions a crash, the server died, players got disconnected, or asks "why did it crash?".
---

# Minecraft crash investigation

Goal: read the newest crash report, identify the failing mod and root-cause exception, and propose a fix that the user can act on.

## Required tools

From the `agent-link` MCP server: `list_dir`, `read_server_file`, `list_mods`, `get_recent_logs`, `get_server_stats`.

## Procedure

### 1. Find the report

`list_dir { path: "crash-reports" }`. If empty:

- Try `list_dir { path: "logs" }` and look for `hs_err_pid*.log` (JVM-level) or check `read_server_file` on `logs/latest.log` for unhandled exceptions. The server may have died without writing a Forge-style crash report (e.g. OOM kill).
- If nothing useful, tell the user there's no crash report to read and ask when the crash happened — you may need them to reproduce or share host-side dmesg.

If reports exist, sort by `mtime_ms` descending and pick the newest. Confirm with the user: "Reading `crash-reports/<file>` (timestamp: <human time>)" before reading the full file — older reports are usually not what they want.

### 2. Read it

`read_server_file { path: "crash-reports/<file>", max_bytes: 262144 }`. The default cap is enough for almost all crash reports; if `truncated: true`, fetch additional ranges with `offset`.

Crash reports have stable sections — extract:

- **Description / Time / Stacktrace** at the top: the immediate exception.
- **Suspected mods**: Forge usually lists them.
- **System Details / Mods**: mod versions at crash time.
- **Affected level / entity / block**: where it happened.

### 3. Correlate

Parallel calls:

- `list_mods` — current mod list. Compare versions if the report is from an earlier session.
- `get_recent_logs` with `levels: ["ERROR", "WARN"]`, `limit: 100` — what was happening just before. Look especially for repeated errors (same exception multiple times before the fatal one).

### 4. Diagnose

In the report, the **top non-Minecraft, non-Forge stack frame** is usually the culprit. Map it back to a mod id from `list_mods`.

Common patterns to call out:

- `OutOfMemoryError` or `Java heap space` → JVM heap too small. Recommend bumping `-Xmx`.
- Same NPE or `IllegalStateException` repeating in `get_recent_logs` before crash → known-bad interaction; check mod's issue tracker.
- `ConcurrentModificationException` on `Server thread` → mod doing async world access.
- Crashes inside `chunkGenerate` or worldgen → conflicting worldgen mods or corrupt chunk.
- `Method not found` / `NoClassDefFoundError` → version mismatch between dependent mods.

### 5. Report

Give the user:

- **What crashed**: exception class + one-line message.
- **Where**: top user-frame, mod id mapped from `list_mods`.
- **Context**: any matching pre-crash errors from `get_recent_logs`.
- **Fix options**, in order of safety:
  1. Update or downgrade the implicated mod (link the user to the mod page; do not download).
  2. Adjust a relevant config value (offer `/mc-diagnose` to apply with backup).
  3. Remove the mod (manual — you cannot touch `mods/`).
  4. Increase JVM heap if it's an OOM (manual — you cannot edit launch scripts).

### 6. Don't

- Don't claim a fix is verified — you can read the report but you can't reproduce or test.
- Don't auto-write any config based on a crash unless the user explicitly asks and you've gone through `/mc-diagnose` proper.
- Don't suggest deleting the mod jar. Filesystem writes outside `config/` aren't allowed; that's intentional.

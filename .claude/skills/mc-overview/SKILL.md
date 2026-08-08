---
name: mc-overview
description: One-shot snapshot of the connected Minecraft server — players, TPS/MSPT, recent events, recent errors, mod count. Use when the user asks "what's going on with the server?", "is the server healthy?", "what's happening right now?", or wants a quick status read before deciding what to investigate.
---

# Minecraft server overview

Goal: in **one round of parallel tool calls**, give the user a digestible snapshot of the server's state right now. Don't recurse into deep analysis here — that's `/mc-diagnose` and `/mc-crash`.

## Required tools

These come from the `agent-link` MCP server. If they aren't available, tell the user the MCP server isn't connected and stop.

- `server_diagnose`
- `list_online_players`
- `get_recent_events` (last 30, no topic filter)

## Procedure

1. **Call `server_diagnose`, `list_online_players`, and `get_recent_events` in parallel.**
   `server_diagnose` already contains bounded tick, world/entity/chunk, error-log, thread, mod,
   and crash-summary data; do not repeat those calls unless the snapshot reports an error.
2. Compose a short report (under ~150 words) with sections:
   - **Health**: TPS, avg/p95/p99 mspt, mem used / max, loaded chunks, and `diagnosis.status`. Flag anything that looks off (TPS < 19.5, p99 > 50 ms, mem > 90% of max).
   - **Players**: count + names. If 0, say "no one online".
   - **Recent activity**: 1-2 sentences summarizing chat / joins / leaves / deaths from `get_recent_events`. Mention `head_seq` so the user can ask for incremental updates later.
   - **Recent issues**: summarize the top 1-2 `diagnosis.findings` or WARN/ERROR patterns (don't dump raw lines). If clean, say "no warnings or errors in buffer".
   - **Mods**: count + loader from the snapshot. Don't list them all — only call out ones that look unusual or relevant if there's a recent error.
3. End with **one** suggested next step appropriate to what you found:
   - p99 high or errors present → "want me to run `/mc-diagnose`?"
   - crash reports recently created → "want me to run `/mc-crash`?"
   - everything clean → "looks healthy. anything specific?"

## Style

- No tool-call narration. The user sees the summary, not the JSON.
- Numbers, not adjectives: "TPS 19.97, p99 18ms" beats "TPS is good".
- Don't paginate or offer follow-ups beyond the one suggested next step.

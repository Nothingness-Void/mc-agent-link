---
name: mc-overview
description: One-shot snapshot of the connected Minecraft server — players, TPS/MSPT, recent events, recent errors, mod count. Use when the user asks "what's going on with the server?", "is the server healthy?", "what's happening right now?", or wants a quick status read before deciding what to investigate.
---

# Minecraft server overview

Goal: in **one round of parallel tool calls**, give the user a digestible snapshot of the server's state right now. Don't recurse into deep analysis here — that's `/mc-diagnose` and `/mc-crash`.

## Required tools

These come from the `agent-link` MCP server. If they aren't available, tell the user the MCP server isn't connected and stop.

- `get_server_stats`
- `list_online_players`
- `tick_profile`
- `get_recent_events` (last 30, no topic filter)
- `get_recent_logs` with `levels: ["WARN", "ERROR"]`, `limit: 30`
- `list_mods`

## Procedure

1. **Call all six tools in parallel.** They are independent. One round. If any tool errors or times out, do not retry — continue with the rest, and in the report note which tool(s) failed so the user knows the snapshot is degraded.
2. Compose a concise report — keep it short, but don't drop critical info when multiple issues co-exist. Sections:
   - **Health**: TPS, avg/p95/p99 mspt, mem used / max, loaded chunks. Flag anything that looks off (TPS < 19.5, p99 > 50 ms, mem > 90% of max).
   - **Players**: count + names. If 0, say "no one online".
   - **Recent activity**: 1-2 sentences summarizing chat / joins / leaves / deaths from `get_recent_events`. Surface `head_seq` (the latest event sequence number) so the user can poll incrementally for new events later.
   - **Recent issues**: if WARN/ERROR logs exist, summarize the top 1-2 patterns (don't dump raw lines). If clean, say "no warnings or errors in buffer".
   - **Mods**: count + loader. Don't list them all — only call out ones that look unusual or relevant if there's a recent error.
3. End with **one** suggested next step appropriate to what you found. If multiple conditions match, pick the highest-priority one in this order:
   - crash reports recently created → "want me to run `/mc-crash`?"
   - p99 high or errors present → "want me to run `/mc-diagnose`?"
   - everything clean → "looks healthy. anything specific?"

## Style

- No tool-call narration. The user sees the summary, not the JSON.
- Numbers, not adjectives: "TPS 19.97, p99 18ms" beats "TPS is good".
- For timestamps from events/logs, render in the user's local timezone if known; otherwise use UTC and label it (e.g. "14:02 UTC").
- Don't paginate or offer follow-ups beyond the one suggested next step.

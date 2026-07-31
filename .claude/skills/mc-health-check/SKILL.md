---
name: mc-health-check
description: Verify the agent-link MCP connection is working and report the server's identity. Use after switching servers, reconfiguring the MCP host, when the user asks "are you connected?", or as the first step of any longer session against an unfamiliar server.
---

# agent-link health check

Goal: in three or four tool calls, confirm the bridge is up, the auth handshake succeeded, and report the server's identity so the user knows what they're talking to.

## Required tools

From the `agent-link` MCP server: `ping`, `whoami`, `get_server_stats`, `list_mods`.

If those tools aren't visible, the MCP server isn't connected — tell the user to check their MCP host config (typically `.claude/mcp.json` or equivalent) and the `AGENT_LINK_URL` / `AGENT_LINK_TOKEN` env vars, then stop.

`whoami` is the one that matters most here: it answers "connected, and with what authority" in a
single call, so you don't later discover the limits by triggering a denial in front of the user.

## Procedure

1. **Parallel**: `ping`, `whoami`, `get_server_stats`, `list_mods`.
2. Compose a short status block:
   - **Connection**: ✓ if `ping.pong === true`. Include uptime in human form (ms → seconds/minutes/hours/days).
   - **Authority**: token tier from `whoami.caller`. For a `console` token, one line: everything runs without in-game approval. For `guest`, say so and surface `whoami.approval.warning` if present — "no OP online, so anything needing approval will be denied" is the single most useful thing to tell the user up front.
   - **Server**: TPS, mspt, online/max, loaded chunks, memory.
   - **Mods**: count, loader. Mention if `agentlink` itself is in the list (sanity check), plus whether `whoami.integrations` reports WorldEdit and spark — they gate whole tool groups.
   - **Write scope**: only if it is not the default — a non-empty `whoami.writes.build_zones`, or `file_write_allow` wider than `["config/**"]`. Silence means default.
3. If anything looks wrong (TPS missing, ping failed, mod count zero), say so and suggest the next move (re-check MCP config, restart server, etc.).

Keep the whole reply under 10 lines. This is a *check*, not a report — if the user wants more, they'll run `/mc-overview` or `/mc-diagnose`.

## Don't

- Don't run any state-changing tool here. Health check is read-only — that now excludes a much larger set than it used to: no `broadcast`, `run_console_command`, `write_config_file`, and none of the block writes, NBT writes, entity or world-property tools.
- Don't dump the full mod list — count is enough.
- Don't recite `whoami`'s tier lists. Report the tier and any warning; the lists are for you to reason with, not for the user to read.

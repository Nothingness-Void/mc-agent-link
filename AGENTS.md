# Agent setup instructions

If a user sends you this repository link, help them connect their Minecraft server through `mc-agent-link`.

## Preferred flow: local setup endpoint

Ask the user for the full `agent-link local setup endpoint` printed in the Minecraft server console after installing the mod/plugin and starting the server. It looks like:

```text
http://127.0.0.1:25581/pair/setup/<random-id>
```

This endpoint is served by the Minecraft server itself. It does not require GitHub, a browser, or a manually copied token. Do not ask the user to paste `config/agent-link.toml` or a raw token unless pairing fails.

The complete reference is in docs/pairing.md. Use this file as the short, executable runbook when
the user only provides a setup URL.

## What to do with the setup endpoint

1. Send `GET <setup_endpoint>` with `Accept: application/json`.
2. Read these fields from the returned JSON:
   - `mcp_url`
   - `pair_url`
   - `pair_code`
   - `expires_at`
3. Before `expires_at`, send:

```http
POST <pair_url>
Accept: application/json
Content-Type: application/json
Origin: http://127.0.0.1

{"pair_code":"<pair_code>"}
```

4. Use the returned `mcp` object as `mcpServers.minecraft` in the user's MCP host config. Merge it with existing servers; never overwrite unrelated entries.
5. Restart or reload the MCP host if needed.
6. Verify by calling the `whoami` tool first, then call `ping` before any other Minecraft action.

PowerShell can perform the exchange without GitHub:

    $setup = Invoke-RestMethod -Method Get -Uri '<setup-endpoint>' -Headers @{ Accept = 'application/json' }
    $body = @{ pair_code = [string]$setup.pair_code } | ConvertTo-Json -Compress
    $pair = Invoke-RestMethod -Method Post -Uri $setup.pair_url -Headers @{ Accept = 'application/json' } -ContentType 'application/json' -Body $body

Require $setup.kind to be agent-link-pairing and use $pair.mcp exactly as
mcpServers.minecraft. Never print $pair.mcp.headers.Authorization.

After a successful pairing, the bearer token is persisted by the server and remains valid across server restarts. Do not ask for a new setup endpoint on every restart. If another agent must be paired, ask the operator to run `/agentlink pair` (or `/agentlink pair-guest`) and provide the newly printed local endpoint.

If the startup log says pairing already exists and the setup endpoint is suppressed, this is
expected. Reuse the existing MCP host configuration. A new agent cannot recover a token from
`/mcp`; the operator must explicitly run `/agentlink pair` or `/agentlink pair-guest` and send
the new complete endpoint.

## Legacy GitHub setup link

Older mod/plugin versions print a GitHub-fragment link:

```text
https://github.com/Nothingness-Void/mc-agent-link/blob/main/AGENTS.md#agent-link-setup=...
```

If the user provides that legacy form, extract the value after `agent-link-setup=`, decode it as base64url JSON, and continue with the same `pair_url` POST flow. This fallback is retained for compatibility only; never require GitHub for a new installation.

## In-game approval model

After the MCP host is connected, tool execution may still require in-game approval.

- `approval.auto_allow_tools`: run immediately without a prompt
- `approval.trusted_tools`: remembered from the in-game `[always allow this tool]` button
- `build_zones`: a spatial write whose **entire** affected region lies inside an operator-declared box runs without a prompt. An edit straddling the boundary still prompts and is never clipped to fit.
- ordinary approval: online OPs see clickable approval buttons in Minecraft chat
- `approval.admin_only_tools`: only players listed in `[roles].admin_uuids` may approve
- if `roles.admin_uuids` is empty: the server falls back to all online OPs so legacy servers still work

Once connected, call `whoami` before anything else. It reports which of the above applies to your
token, whether an eligible approver is even online, and your write boundaries — which distinguishes
"not permitted" from "nobody online to permit it". Those need completely different explanations to
the user.

If the MCP host (for example Claude Code) shows its own permission prompt first, allow the `minecraft` MCP server/tool there so the request can reach the Minecraft-side approval flow.

If `/pair` returns `401`, read the JSON `reason` field:
- `expired`: ask the user to run `/agentlink pair` and send the newly printed local setup endpoint.
- `used`: first inspect the existing MCP host config. If it was not saved, ask the user to run `/agentlink pair` for a new endpoint.
- `unknown`: the code, random path, or pairing state is wrong; do not guess. Ask for a fresh endpoint or ask the operator to run `/agentlink pair`.

## MCP host config shape

```json
{
  "mcpServers": {
    "minecraft": {
      "type": "http",
      "url": "http://127.0.0.1:25581/mcp",
      "headers": {
        "Authorization": "Bearer <token from /pair>"
      }
    }
  }
}
```

Always read and merge existing config files; never overwrite unrelated MCP servers.

## Safety

The token grants Minecraft server operator-level actions. Do not run destructive commands such as
`stop`, `/op`, `/deop`, `/ban`, `/fill`, or `/kill @e` without explicit user confirmation.

Prefer a structured tool over `run_console_command` where one exists (`set_block`, `fill_blocks`,
`teleport`, `give_item`, `set_world_property`, `remove_entities`, …). The console tool is an
op-level-4 shell: approving it grants everything, its output has to be parsed out of chat text, and
nothing it does is undoable. The structured tools take typed arguments, need a narrower permission,
return parseable results, and block writes are reversible with `undo_blocks`.

Two behaviours to know before you write anything:

- Long operations must go through `start_task`, or the MCP call times out mid-work while the work
  keeps running. Exceeding a synchronous limit returns `VOLUME_TOO_LARGE` **with the exact
  `start_task` call to use** — don't split the region yourself.
- `remove_entities` without a `types`/`categories` filter defaults to a preview. Read
  `counts_by_type`, confirm, then re-issue with `dry_run:false`. It never removes players.

If a tool call returns `APPROVAL_DENIED`, explain that the in-game OP/admin denied it, no eligible approver was online, or the request timed out.

For manual fallback and troubleshooting, read `INSTALL.md`.

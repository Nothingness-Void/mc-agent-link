# Agent setup instructions

If a user sends you this repository link, help them connect their Minecraft server through `mc-agent-link`.

## Preferred flow: setup link

Ask the user for the full `agent-link setup link` printed in the Minecraft server console after installing the Forge mod and starting the server. It looks like:

```text
https://github.com/Nothingness-Void/mc-agent-link/blob/main/AGENTS.md#agent-link-setup=...
```

Do not ask the user to paste `config/agent-link.toml` or a raw token unless pairing fails.

## What to do with the setup link

1. Extract the URL fragment value after `agent-link-setup=`.
2. Decode it as base64url JSON. Add padding if your decoder requires it.
3. Read these fields:
   - `mcp_url`
   - `pair_url`
   - `pair_code`
   - `expires_at`
4. Before `expires_at`, send:

```http
POST <pair_url>
Accept: application/json
Content-Type: application/json
Origin: http://127.0.0.1

{"pair_code":"<pair_code>"}
```

5. Use the returned `mcp` object as `mcpServers.minecraft` in the user's MCP host config.
6. Restart or reload the MCP host if needed.
7. Verify by calling the `ping` tool.

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
- `expired`: ask the user to send the latest refreshed setup link from the Minecraft server console.
- `used`: tell the user the setup link was already consumed, possibly by another agent/MCP host, and ask for the latest refreshed setup link if needed.
- `unknown`: the code is wrong or not recognized; re-check the setup link and ask for a fresh one if pairing still fails.

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

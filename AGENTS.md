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

The token grants Minecraft server operator-level actions through tools like `run_console_command`. Do not run destructive commands such as `stop`, `/op`, `/deop`, `/ban`, `/fill`, or `/kill @e` without explicit user confirmation.

For manual fallback and troubleshooting, read `INSTALL.md`.

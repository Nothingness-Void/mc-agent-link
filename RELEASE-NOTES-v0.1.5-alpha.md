# mc-agent-link v0.1.5-alpha

> ⚠️ **Alpha**: in-game agent request and setup-link usability release. MCP HTTP remains the recommended transport. WebSocket wire protocol (`v=0`) remains compatible.

This release lets server operators ask the connected agent for help from inside Minecraft and makes setup links refresh automatically until pairing succeeds.

## What's new

### In-game `/agent` requests

Operators can now submit requests directly from the Minecraft server:

```text
/agent check why the server is lagging
```

The command requires permission level 2 and queues the request for the connected MCP agent. Agents can pull, acknowledge, and reply using three new tools:

- `get_agent_requests`
- `update_agent_request_status`
- `reply_agent_request`

Replies are sent back to the requesting player in-game when they are online. Long replies are split/truncated for Minecraft chat.

### Setup links refresh automatically

Setup links remain short-lived and one-use, but the mod now refreshes the pair code and prints a new setup link when the previous code expires and pairing has not succeeded yet:

```text
agent-link setup link refreshed (send this to your AI agent, one use, expires at ...): https://github.com/Nothingness-Void/mc-agent-link#agent-link-setup=...
```

Users no longer need to restart the server just because they missed the initial 10-minute pairing window.

## Tool / transport surface

- MCP HTTP endpoint: unchanged at `POST /mcp`.
- Pairing endpoint: unchanged at `POST /pair`.
- New in-game command: `/agent <request>`.
- New tools: `get_agent_requests`, `update_agent_request_status`, `reply_agent_request`.
- Node bridge remains available as a legacy fallback and exposes the same new tools.

## Upgrade path from v0.1.4-alpha

Drop in the new jar and restart the server.

Existing MCP host configs keep working. The setup link is only needed for new or reconfigured hosts.

If you use the legacy Node bridge tarball, replace it with the new tarball and restart the MCP host.

## Artifacts

| File | Goes into |
|---|---|
| `agent-link-forge-1.20.1-0.1.5-alpha.jar` | `<server>/mods/` (Forge 1.20.1, Java 17) |
| `agent-link-mcp-server-0.1.5-alpha.tgz` | Legacy stdio bridge fallback |
| `SHA256SUMS.txt` | Verify the above |

## Verify the artifacts

```sh
sha256sum agent-link-forge-1.20.1-0.1.5-alpha.jar agent-link-mcp-server-0.1.5-alpha.tgz
```

Expected hashes are in `SHA256SUMS.txt`.

## License

[Apache-2.0](https://github.com/Nothingness-Void/mc-agent-link/blob/main/LICENSE).

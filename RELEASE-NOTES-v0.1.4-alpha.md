# mc-agent-link v0.1.4-alpha

> ⚠️ **Alpha**: installation-flow and spark hotfix release. WebSocket wire protocol (`v=0`) remains compatible. MCP HTTP remains the recommended transport.

This release makes the default install path much easier and fixes `spark_health_report` returning before spark's viewer URL appears.

## What's new

### Setup link pairing

The Forge mod now prints a one-use setup link when MCP HTTP starts:

```text
agent-link setup link (send this to your AI agent, one use, expires at ...): https://github.com/Nothingness-Void/mc-agent-link#agent-link-setup=...
```

The setup link contains a short-lived pair code, not the permanent token. Agents decode the link, call `POST /pair`, receive the MCP host config block, write it into the user's MCP config, and verify with `ping`.

User-facing install flow is now:

1. Drop the jar into `<server>/mods/`.
2. Start the server.
3. Send the console setup link to the AI agent.

The old manual token path still works for troubleshooting.

### Agent-facing install instructions

A new `AGENTS.md` explains what an AI agent should do when a user sends this repository link or a setup link:

- decode `#agent-link-setup=...`,
- exchange `pair_code` at `/pair`,
- merge the returned config into `mcpServers.minecraft`,
- reload the MCP host,
- verify with `ping`.

## What's fixed

### `spark_health_report` no longer returns on the first non-URL spark line

In v0.1.3-alpha, `spark_profiler_stop` captured viewer URLs correctly, but `spark_health_report` could return immediately after the first spark status line, for example:

```text
[⚡] Generating server health report...
```

That meant `url_present: false` even when spark later uploaded a viewer URL.

The wait now completes only when the spark viewer URL regex matches or `wait_url_ms` expires. Non-URL spark output is still accumulated into `output`, but it does not end the wait early.

The wait also uses `MinecraftServer#managedBlock` so the server task queue continues draining while spark collects world statistics.

### `spark_health_report` default wait increased

`spark_health_report` now defaults to `wait_url_ms = 45000` because spark can spend roughly 30 seconds collecting world statistics before uploading.

### `spark_profiler_cancel` captures output more reliably

`cancel` now keeps the temporary spark output capture active briefly, so cancellation messages have a chance to appear in `output`.

## Tool / transport surface

- MCP HTTP endpoint: unchanged at `POST /mcp`.
- New pairing endpoint: `POST /pair`.
- WebSocket transport: unchanged.
- Node bridge: still available as a legacy fallback.
- `spark_health_report` schema unchanged except the documented default wait is now 45000 ms.

## Upgrade path from v0.1.3-alpha

Drop in the new jar and restart the server.

Existing MCP host configs keep working. The setup link is only needed for new or reconfigured hosts.

If you use the legacy Node bridge tarball, replace it with the new tarball and restart the MCP host.

## Artifacts

| File | Goes into |
|---|---|
| `agent-link-forge-1.20.1-0.1.4-alpha.jar` | `<server>/mods/` (Forge 1.20.1, Java 17) |
| `agent-link-mcp-server-0.1.4-alpha.tgz` | Legacy stdio bridge fallback |
| `SHA256SUMS.txt` | Verify the above |

## Verify the artifacts

```sh
sha256sum agent-link-forge-1.20.1-0.1.4-alpha.jar agent-link-mcp-server-0.1.4-alpha.tgz
```

Expected hashes are in `SHA256SUMS.txt`.

## License

[Apache-2.0](https://github.com/Nothingness-Void/mc-agent-link/blob/main/LICENSE).

# mc-agent-link v0.1.6-alpha

> Alpha release: base/lib mod split and setup-link pairing improvements.

This release turns `mc-agent-link` into a cleaner base/lib mod. The in-game `/agent` command now lives in a separate optional addon, while the base mod keeps the shared request queue and MCP tools.

## Highlights

### Base/lib mod direction

- Removed the built-in `/agent` command from the base mod.
- Kept `AgentRequestBuffer` and MCP tools as the base API:
  - `agent_heartbeat`
  - `get_agent_requests`
  - `update_agent_request_status`
  - `reply_agent_request`
- Added addon architecture documentation in `docs/addons.md`.

### New optional `/agent` addon

The in-game command is now provided by a separate addon repo:

https://github.com/Nothingness-Void/mc-agent-link-agent

Install both jars to use `/agent` in game:

```text
agent-link-forge-1.20.1-0.1.6-alpha.jar
agent-link-agent-forge-1.20.1-0.1.0-alpha.jar
```

### Pairing improvements

- Setup links now point directly to `AGENTS.md`:

```text
https://github.com/Nothingness-Void/mc-agent-link/blob/main/AGENTS.md#agent-link-setup=...
```

- Setup-link payload includes `instructions_url`.
- `/pair` docs now explicitly include `Origin: http://127.0.0.1`.
- `/pair` 401 responses now return JSON with a reason:

```json
{"error":"Invalid or expired pair code","reason":"expired"}
```

Reasons are `unknown`, `expired`, or `used`.

### Manual pairing refresh

Operators or the server console can generate a new setup link immediately:

```text
/agentlink pair
```

## Compatibility notes

- Forge: `1.20.1`
- Java: `17`
- MCP HTTP endpoint: `POST /mcp`
- Pairing endpoint: `POST /pair`
- Legacy Node stdio bridge remains available.

## Artifacts

| File | Goes into |
|---|---|
| `agent-link-forge-1.20.1-0.1.6-alpha.jar` | `<server>/mods/` |
| `agent-link-mcp-server-0.1.6-alpha.tgz` | Legacy stdio bridge fallback |
| `SHA256SUMS.txt` | Verify the above |

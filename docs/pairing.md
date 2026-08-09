# Agent Link Pairing

This is the authoritative pairing guide for Forge 1.20.1, NeoForge 1.21.1, and Spigot/Paper 1.20+.
All three implementations use the same MCP HTTP pairing protocol. Only the configuration key names
are different.

## What pairing does

Pairing does not require a GitHub page. The Minecraft server creates a short-lived, one-use pair
code and exposes it through a local HTTP setup endpoint. The agent:

1. GETs the setup endpoint.
2. POSTs the returned pair code to the server.
3. Receives an MCP HTTP configuration block containing a new bearer token.
4. Merges that block into the MCP host configuration.
5. Reloads the host and verifies whoami, then ping.

The bearer token is stored as a hash in the server's persistent token registry. The plaintext token
is returned only in the successful /pair response and is not recoverable from the registry.

## First pairing

### 1. Find the setup endpoint

On a fresh, unpaired server, the console prints one line like:

    agent-link local setup endpoint (one use, expires at 2026-08-09T07:38:17Z): http://127.0.0.1:25581/pair/setup/<random-id>

Copy the complete HTTP URL. Do not remove the random path, shorten it, open the GitHub repository,
or ask the user for agent-link.toml.

The default 127.0.0.1 address means the agent and Minecraft server are on the same machine. For a
remote agent, configure a reachable public host before starting the server; see Remote pairing.

### 2. GET the endpoint

The endpoint is a JSON API. It does not require GitHub or a browser.

PowerShell:

    $setup = Invoke-RestMethod -Method Get -Uri '<setup-endpoint-url>' -Headers @{ Accept = 'application/json' }
    if ($setup.kind -ne 'agent-link-pairing') {
        throw "The setup endpoint did not return an active pairing descriptor: $($setup.reason)"
    }

curl:

    curl --fail-with-body -H 'Accept: application/json' '<setup-endpoint-url>'

An active response looks like this:

    {
      "kind": "agent-link-pairing",
      "version": 2,
      "mcp_url": "http://127.0.0.1:25581/mcp",
      "pair_url": "http://127.0.0.1:25581/pair",
      "setup_url": "http://127.0.0.1:25581/pair/setup/<random-id>",
      "pair_code": "1234-5678",
      "expires_at": 1779640000000,
      "expires_at_iso": "2026-05-25T00:00:00Z",
      "allow_remote": false,
      "token_tier": "console",
      "pair_request": {
        "method": "POST",
        "url": "http://127.0.0.1:25581/pair",
        "body": { "pair_code": "1234-5678" }
      }
    }

Use values returned by the server. Do not reconstruct URLs from the example or replace mcp_url
with the setup URL.

### 3. POST the pair code

PowerShell:

    $pairBody = @{ pair_code = [string]$setup.pair_code } | ConvertTo-Json -Compress
    $pair = Invoke-RestMethod -Method Post -Uri $setup.pair_url -Headers @{ Accept = 'application/json' } -ContentType 'application/json' -Body $pairBody
    $minecraftServer = $pair.mcp
    if ($null -eq $minecraftServer -or $minecraftServer.type -ne 'http') {
        throw 'The pairing response did not contain an MCP HTTP server block.'
    }

Equivalent HTTP request:

    POST <pair_url>
    Accept: application/json
    Content-Type: application/json

    {"pair_code":"1234-5678"}

The response contains the exact object to install:

    {
      "mcp": {
        "type": "http",
        "url": "http://127.0.0.1:25581/mcp",
        "headers": {
          "Authorization": "Bearer <new-token>"
        }
      },
      "server": {
        "minecraft": true,
        "token_tier": "console"
      }
    }

Treat the Authorization value as a secret. Do not print it in the final response, paste it into
chat, commit it, or write it to the Minecraft server log.

### 4. Merge the MCP host configuration

Read the existing host configuration first. Create mcpServers if it does not exist, then set only
mcpServers.minecraft to the returned mcp object. Preserve every unrelated server and top-level
setting. Write the file atomically if the host supports it.

Typical locations:

| Host | Configuration location |
|---|---|
| Claude Code project | <project>/.mcp.json |
| Claude Code user | %USERPROFILE%/.claude.json on Windows; ~/.claude.json on macOS/Linux |
| Cursor project | <project>/.cursor/mcp.json |
| Cursor user | ~/.cursor/mcp.json |

Do not put the setup endpoint in the MCP host configuration. The setup endpoint is only for
pairing; the MCP endpoint is the returned mcp.url.

### 5. Reload and verify

Reload or restart the MCP host if it does not watch its configuration. Then:

1. Run initialize through the host.
2. Run whoami before any world or server action. It reports token tier, approval policy, online
   approvers, file-write boundaries, and build zones.
3. Run ping and confirm pong is true.
4. If the host cannot see the server, inspect MCP connection status before changing Minecraft
   permissions.

## Pairing state and restarts

There are two independent pieces of state:

- The setup endpoint and pair code are temporary, one-use, and valid for 10 minutes.
- The bearer token created by a successful pair is persistent.

| State | Console behavior | Agent action |
|---|---|---|
| No issued token | Prints a local setup endpoint; refreshes it after 10 minutes until pairing succeeds | GET the newest endpoint and pair |
| Pairing succeeds | Consumes the endpoint and persists the token | Save the returned mcp block |
| Server restarts after pairing | Suppresses the endpoint and does not refresh in the background | Reuse the existing MCP host config |
| A second agent is needed | Existing pairing is not reopened automatically | Operator runs /agentlink pair or /agentlink pair-guest |
| The host config was lost | The server cannot recover the plaintext token | Operator runs /agentlink pair and the agent saves the new response |
| A token was revoked | The old host config receives 401 | Operator runs /agentlink pair and replaces only Minecraft's block |

No setup endpoint after restart is the expected result of successful pairing, not a failure. Do not
run /agentlink pair on every restart.

If the log says "pairing already exists; setup endpoint suppressed", a new agent cannot recover a
token from /mcp. The operator must explicitly run /agentlink pair or /agentlink pair-guest and
provide the newly printed complete endpoint.

## Remote pairing

The setup endpoint, /pair, and /mcp must all be reachable from the agent machine. A URL containing
127.0.0.1 or localhost points to the agent's own machine, not the Minecraft server.

### Forge and NeoForge

Edit <server>/config/agent-link.toml before starting the server:

    allow_remote = true
    mcp_public_host = "mc.example.com"
    mcp_allowed_origins = ["null"]

mcp_public_host is the hostname or IP embedded in the setup endpoint and returned MCP URL. Do not
include http:// or a trailing slash. Open only the MCP port, 25581 by default, to the trusted agent
network. allow_remote also affects the legacy WebSocket listener on 25580.

### Spigot/Paper

Edit plugins/AgentLink/config.yml:

    mcp:
      allow-remote: true
      public-host: mc.example.com
      allowed-origins:
        - "null"

Open only the configured MCP port to the trusted agent network. Keep mcp.allowed-origins narrow
when browser clients are allowed. Native MCP clients normally omit Origin and are represented by
the literal allowlist entry "null".

After changing remote settings, restart the server and use the newly printed endpoint. Never edit
the returned mcp.url by hand after pairing; fix the server public-host setting and pair again.

## Failure recovery

### GET setup endpoint

| Result | Meaning | Action |
|---|---|---|
| 200, kind=agent-link-pairing | Active descriptor | POST its pair_code immediately |
| 404, reason=unknown | Wrong, truncated, or stale random path | Ask for the newest endpoint; do not guess the code |
| 410, reason=expired | The 10-minute window ended | Operator runs /agentlink pair or /agentlink pair-guest |
| 410, reason=used | Another host consumed it | Check the existing host config; otherwise pair again |
| 410, reason=pairing_inactive | Server already has a persistent token and automatic pairing is closed | Reuse the existing config or explicitly run /agentlink pair |
| 403 | Origin is not allowlisted | Use a native request without Origin, or configure the exact trusted origin |
| Connection refused | HTTP listener is disabled, stopped, bound elsewhere, or blocked by firewall | Check server log, mcp_enabled, ports, bind setting, and firewall |

### POST /pair

The endpoint accepts only JSON POST requests. An error is HTTP 401 with:

    {"error":"Invalid or expired pair code","reason":"unknown|expired|used"}

- unknown: the code does not belong to the active endpoint, the endpoint is no longer active, or
  the server was restarted. Obtain a fresh endpoint with /agentlink pair.
- expired: the endpoint exceeded 10 minutes. Obtain a fresh endpoint.
- used: pairing already succeeded. First inspect the MCP host config; if it was not saved, obtain
  a new endpoint.

Do not manually copy config/agent-link/issued_tokens.json: it stores hashes, not usable tokens. The
legacy master token in agent-link.toml is an emergency compatibility path and should not be requested
from the user when the normal pairing command is available.

## Legacy GitHub-fragment links

Older mod/plugin builds may print a URL ending in:

    https://github.com/Nothingness-Void/mc-agent-link/blob/main/AGENTS.md#agent-link-setup=<base64url-json>

This is only a transport for a JSON descriptor. An agent can decode the fragment locally without
opening GitHub, read mcp_url, pair_url, pair_code, and expires_at, and then use the same POST /pair
flow. New builds print the local endpoint instead and do not require GitHub.

## Security checklist

- Treat the setup URL as a one-use credential until it expires.
- Treat the bearer token as a persistent server credential.
- Do not put either value in chat history, source control, screenshots, or public issue reports.
- Merge only mcpServers.minecraft; do not overwrite other MCP servers.
- Use whoami before any write or console action.
- Do not run destructive Minecraft commands during pairing or troubleshooting.

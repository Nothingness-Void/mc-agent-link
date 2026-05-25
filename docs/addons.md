# Addon architecture

`mc-agent-link` is the base/lib mod. Its job is to expose stable Minecraft server capabilities to agents and addon mods, while optional features live in separate addons.

## Base mod responsibilities

The base Forge mod should stay focused on:

- MCP HTTP transport and setup-link pairing.
- Core server tools and safety boundaries.
- In-game `/agent` request queue.
- Stable Java APIs that addon mods can call in the same JVM.
- Documentation for agent and addon authors.

The base mod should not require Claude Code, a specific LLM provider, WSL, tmux, or any background companion process.

## Addon examples

Optional addons can provide higher-level features:

- `mc-agent-link-agent`: in-game `/agent` command, backed by the base mod request queue.
- `mc-agent-link-claude`: Windows-focused Claude Code bridge that auto-handles `/agent` requests.
- `mc-agent-link-avatar`: in-game agent entity/avatar and world interactions.
- `mc-agent-link-custom-commands`: user-defined command-to-prompt bindings.

These addons should be separate repositories or separately released artifacts that declare a Forge dependency on `agentlink`.

## Current base API surface

The current in-JVM API for `/agent` request addons is:

```java
AgentRequestBuffer buf = AgentRequestBuffer.get();
List<AgentRequestBuffer.Entry> entries = buf.since(lastSeq, 10, false);
AgentRequestBuffer.Entry working = buf.updateStatus(id, AgentRequestBuffer.Status.WORKING, "...");
AgentRequestBuffer.Entry done = buf.reply(id, reply, true);
AgentRequestBuffer.sendStatusToPlayer(mcServer, working);
AgentRequestBuffer.sendReplyToPlayer(mcServer, done);
```

`AgentRequestBuffer.Entry` currently includes:

- `seq`
- `id`
- `createdAt`
- `updatedAt`
- `source`
- `playerName`
- `playerUuid`
- `message`
- `status`
- `statusMessage`
- `reply`

## In-game `/agent` addon direction

The `/agent` command should live in `mc-agent-link-agent`, not in the base mod. The addon depends on `agentlink` and calls `AgentRequestBuffer` directly:

- `/agent <request>` creates a pending request.
- `/agent status` reads recent requests and `lastAgentSeenAt`.
- `/agent cancel <id>` cancels a pending request.
- User-facing command messages should be localized by the addon.

The base mod may continue to expose MCP tools such as `get_agent_requests`, `update_agent_request_status`, `reply_agent_request`, and `agent_heartbeat` as the transport/API side of this feature.

## Claude addon direction

A Claude addon can be implemented as a separate Forge mod:

- `modId = "agentlinkclaude"`
- mandatory dependency: `agentlink`
- pure Windows first
- call local `claude.cmd` / `claude.exe` with `ProcessBuilder`
- use `claude -p --resume <session_id> <message>`
- use one shared session id for all OP requests
- poll `AgentRequestBuffer` directly in JVM; do not use MCP HTTP for queue access
- keep processing serial to avoid session conflicts

The Claude addon should be explicit opt-in. Installing the base mod alone must not start any local LLM process.

## Addon safety requirements

Addons that can trigger server actions should:

- Preserve base mod OP restrictions.
- Process requests serially unless they implement claiming/locking.
- Avoid destructive commands unless explicitly confirmed.
- Log request ids, player names, status changes, and process failures.
- Stop worker threads and child processes during server shutdown.
- Surface clear failure messages back to the requesting player.

## Future stable API work

If addons become common, promote the request queue into a small stable API package, for example:

```text
world.agentlink.api.AgentRequests
world.agentlink.api.AgentRequest
world.agentlink.api.AgentRequestStatus
```

Possible future additions:

- Atomic `claim(id, workerName)` to prevent multiple addons from processing the same request.
- Listener/event callback when a new request is queued.
- Public capability/service lookup for addon mods.
- Explicit semantic versioning for the Java addon API.

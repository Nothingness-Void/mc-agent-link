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

## Registering your own MCP tools

`world.agentlink.api.AgentLinkApi` is the supported surface. Everything under
`world.agentlink.dispatch`, `world.agentlink.transport` and `world.agentlink.config` is internal and
may change between releases.

```java
AgentLinkApi.registerTool("myaddon", new BaseAddonTool(
        "echo",
        "Echo the arguments back.",
        schema()) {
    @Override
    protected JsonObject invoke(JsonObject args) {
        return args;
    }
});
```

The registered name becomes `myaddon__echo`. It appears in MCP `tools/list`, flows through the same
approval pipeline as built-in tools, and respects the same role tiers. Register from your `@Mod`
constructor — calls made before the server starts are queued until the dispatcher exists.

### Threading

Your `invoke` runs on the **main server thread** by default, which is what makes world access safe.
If your tool blocks — an HTTP call, a database query, a large file read — that block is tick time, and
players feel it directly.

Pass `offThread = true` to the four-argument `BaseAddonTool` constructor to run on a worker pool
instead:

```java
new BaseAddonTool("fetch_stats", "…", schema(), /* offThread */ true) { … }
```

Off-thread tools must not touch `ServerLevel`, entities, or block state directly. When you need world
access from an off-thread tool, hop back:

```java
String blockId = ServerThread.call(server, () -> BlockWriter.idOf(level.getBlockState(pos)));
```

That is the same mechanism the base mod's sliced write tools use to spread a large edit across ticks.

### Sensitive tools

Anything your addon exposes that mutates state or reads secrets should be added to
`approval.admin_only_tools` in `agent-link.toml` — by its full prefixed name, e.g.
`myaddon__delete_everything`. Tools absent from every tier list fall through to tier 3, where any
online OP can approve them.

If your tool arguments can carry secrets, add `<tool>.<argkey>` entries to `audit.redact_args` so the
audit log records a length instead of the value.

### Build zones

If your addon writes to the world, `AgentLinkApi.isInBuildZone(dim, x, y, z)` reports whether a
position sits inside a region the operator marked as the agent's sandbox. Reusing it means the
operator configures one geometric boundary rather than two. It reports containment only — approval
still runs, and the base mod's automatic geometric exemption applies only to tools it recognizes as
spatial.

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

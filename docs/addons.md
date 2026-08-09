# Addon architecture

`mc-agent-link` is the base/lib mod. Its job is to expose stable Minecraft server capabilities to agents and addon mods, while optional features live in separate addons.

## Base mod responsibilities

The base Forge mod should stay focused on:

- MCP HTTP transport and local setup-endpoint pairing.
- Core server tools and safety boundaries.
- In-game `/agent` request queue.
- Stable Java APIs that addon mods can call in the same JVM.
- Documentation for agent and addon authors.

The base mod should not require Claude Code, a specific LLM provider, WSL, tmux, or any background companion process.

## Addon examples

Optional addons can provide higher-level features:

- `mc-agent-link-agent`: in-game `/agent` command, backed by the base mod request queue.
- `mc-agent-link-claude`: Windows-focused Claude Code bridge that auto-handles `/agent` requests.
- `mc-agent-link-pixel`: server-local image conversion and sliced, undoable Minecraft pixel-art builds.
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
String blockId = AgentLinkApi.server().call(server, () ->
        AgentLinkApi.blocks().parse("minecraft:stone").state().getBlock()
                .builtInRegistryHolder().key().location().toString());
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

The stable in-JVM API is exposed through `world.agentlink.api.AgentLinkApi`. Addons should use the
service facades instead of reaching into implementation packages:

```java
AgentRequestApi requests = AgentLinkApi.requests();
AgentEventApi events = AgentLinkApi.events();
AgentTaskApi tasks = AgentLinkApi.tasks();
AgentServerApi server = AgentLinkApi.server();
AgentBuildZoneApi zones = AgentLinkApi.buildZones();
AgentRoleApi roles = AgentLinkApi.roles();
AgentNbtApi nbt = AgentLinkApi.nbt();
AgentBlockApi blocks = AgentLinkApi.blocks();
AgentItemApi items = AgentLinkApi.items();
AgentPlayerApi players = AgentLinkApi.players();
AgentInventoryApi inventory = AgentLinkApi.inventory();
AgentEffectApi effects = AgentLinkApi.effects();
AgentEntityApi entities = AgentLinkApi.entities();
AgentEntitySpawnApi entitySpawn = AgentLinkApi.entitySpawn();
AgentWorldApi worlds = AgentLinkApi.worlds();
AgentChunkApi chunks = AgentLinkApi.chunks();
AgentMessageApi messages = AgentLinkApi.messages();
```

Addon-owned long work can use the already-approved task submission path, while world writes can share
one undo entry across slices:

```java
AgentTaskApi.Submission queued = AgentLinkApi.tasks().submit(tool, args, session);
AgentBlockApi.Batch batch = AgentLinkApi.blocks().beginBatch(level, "my addon edit", true);
```

Tools submitted this way should implement `world.agentlink.task.TaskContext.Sliceable` and hop onto the
server thread for every world slice. The dispatcher preserves that marker when it prefixes addon
tools, so `start_task` does not silently turn an addon edit into one blocking server-thread unit.

The request facade replaces the old direct `AgentRequestBuffer` integration:

```java
List<AgentRequestApi.Request> entries = requests.since(lastSeq, 10, false);
AgentRequestApi.Claim claim = requests.claimOwned(id, "myaddon", "processing");
AgentRequestApi.Request working = claim == null ? null : claim.request();
AgentRequestApi.Request done = claim == null ? null : requests.replyOwned(claim, reply, true);
requests.sendStatusToPlayer(mcServer, working);
requests.sendReplyToPlayer(mcServer, done);
```

`claimOwned` 返回的 lease 绑定消费者和 generation。完成或更新必须使用同一个 lease，避免
旧 worker 在取消、重启或其他消费者接管后迟到回写；取消会使 lease 失效。

The full examples and compatibility rules live in [docs/api.md](api.md). The Chinese reference is
[docs/api.zh-CN.md](api.zh-CN.md).

## In-game `/agent` addon direction

The `/agent` command should live in `mc-agent-link-agent`, not in the base mod. The addon depends on
`agentlink` and consumes requests through `AgentLinkApi.requests()`:

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
- poll `AgentLinkApi.requests()` directly in JVM; do not use MCP HTTP for queue access
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

## Deliberate future API work

The typed server surface is intentionally broad now. The next compatibility-sensitive additions are:

- Listener callbacks when a request is queued or reaches a terminal state.
- An explicit Java API version separate from the mod version.

# Addon Java API

`mc-agent-link` exposes a small stable Java surface for Forge addon mods. Addons should import
only `world.agentlink.api.*`; packages such as `world.agentlink.dispatch`, `transport`, `task`,
`events`, and `agent` are implementation details.

The current API generation is shipped with base `0.5.0-alpha`. The main entry point is
`AgentLinkApi`:

```java
AgentLinkApi.requests();
AgentLinkApi.events();
AgentLinkApi.tasks();
AgentLinkApi.diagnostics();
AgentLinkApi.server();
AgentLinkApi.buildZones();
AgentLinkApi.roles();
AgentLinkApi.messages();
AgentLinkApi.nbt();
AgentLinkApi.items();
AgentLinkApi.players();
AgentLinkApi.inventory();
AgentLinkApi.effects();
AgentLinkApi.entities();
AgentLinkApi.entitySpawn();
AgentLinkApi.worlds();
AgentLinkApi.chunks();
AgentLinkApi.blocks();
AgentLinkApi.scoreboards();
AgentLinkApi.serverControl();
AgentLinkApi.containers();
AgentLinkApi.progression();
```

## Typed server controls

The control facades are deliberately split by domain so addon mods do not need to copy MCP tool
argument parsing or reach into `world.agentlink.dispatch`. They operate on native Minecraft types
and must be called on the server thread, either from a server event or through
`AgentLinkApi.server().run/call`.

```java
ServerPlayer player = AgentLinkApi.players().online(server, "void");
ItemStack stack = AgentLinkApi.items().parse("minecraft:diamond_sword{Unbreakable:1b}", 1);
AgentLinkApi.players().whitelistAdd(server, player.getGameProfile());
AgentLinkApi.serverControl().save(server, true);
```

- `items()` parses the full vanilla item grammar with type-exact NBT.
- `nbt()` provides lossless SNBT/JSON conversion and vanilla NBT-path operations.
- `players()` resolves online/cached profiles and manages kick, ban, OP, and whitelist lists.
- `inventory()` provides slot-level writes, drops, swaps, and client synchronization.
- `effects()` resolves, applies, lists, and clears living-entity status effects.
- `entities()` resolves loaded entities and controls riding, health, motion, equipment, attributes,
  common flags, fire, teleportation, and lifecycle.
- `entitySpawn()` creates loaded entities with optional NBT, names, persistence, and no-AI setup.
- `worlds()` resolves dimensions and controls time, weather, difficulty, gamerules, shared spawn,
  and world-border settings.
- `chunks()` manages force-loaded chunk state for addon working areas.
- `blocks()` parses native block specifications and provides bounded undoable writes for addon code.
- `scoreboards()` manages objectives, scores, display slots, teams, and membership.
- `messages()` sends native chat components to one player or all online players.
- `serverControl()` exposes save, resource reload, runtime distances, allow-cheats, and an explicit
  confirmed stop. Restart is intentionally outside the JVM and belongs to a supervisor.
- `containers()` provides typed slot writes for block containers.
- `progression()` manages recipe unlocks and advancement criteria against the live registries.

## Diagnostics

`AgentLinkApi.diagnostics()` shares the same bounded collector used by the built-in
`server_diagnose` MCP tool. Live world probes are scheduled onto the server thread when needed;
addons should still avoid calling it from a hot per-tick event:

```java
JsonObject snapshot = AgentLinkApi.diagnostics().snapshot(server);
JsonArray findings = snapshot.getAsJsonObject("diagnosis").getAsJsonArray("findings");
```

The result contains raw `server_stats`, `tick_profile`, timing-only `tick_incidents`, `world`, an optional incident ledger,
optional logs/threads/mods/crash data, component timings, and conservative candidate findings.
Findings are evidence-guided hints,
not a guarantee of root cause. `Options.timeoutMs()` is a total budget (default 12 seconds, clamped
to 1--30 seconds); a component that has not started when the budget expires is skipped and reported
with `status: "timeout"`, while the top-level `complete` flag becomes false. Use `Options` to bound
or disable components.

These facades cover the stable, typed vanilla control surface. `run_console_command` remains the
intentional escape hatch for commands added by third-party mods or vanilla features that have not
earned a typed facade; common block, NBT, player, entity, world, and server operations do not need it.

These APIs are lower-level building blocks, not an approval bypass: an addon MCP tool registered via
`AgentLinkApi.registerTool` still goes through the normal approval and audit pipeline. Direct calls
made by an addon are the addon's own trusted code and should remain on the server thread.

## Request queue

Use the request facade instead of reaching into `AgentRequestBuffer`:

```java
AgentRequestApi requests = AgentLinkApi.requests();
AgentRequestApi.Request request = requests.submit(
        "myaddon", playerName, playerUuid, message);

long cursor = lastSeq;
for (AgentRequestApi.Request item : requests.since(cursor, 20, false)) {
    if (item.status() != AgentRequestApi.Status.PENDING) continue;
    AgentRequestApi.Claim claim = requests.claimOwned(item.id(), "myaddon", "processing");
    if (claim == null) continue;
    AgentRequestApi.Request claimed = claim.request();
    // Process the claimed item, then complete it with the same ownership lease.
    AgentRequestApi.Request done = requests.replyOwned(claim, reply, true);
}
```

The queue is still a shared ring buffer. `since(...)` is a cursor read, while `claimOwned(...)` is
the atomic transition from `PENDING` to `WORKING`. The returned `owner + generation` lease is
required for `replyOwned(...)` and `updateStatusOwned(...)`; a stale or foreign worker cannot
overwrite the request. A claim that returns `null` was already claimed, cancelled, or evicted;
the addon must skip it. The older `claim/reply` methods remain for compatibility, but new
consumers should use the lease methods.

The ring has a fixed capacity (128). When the next slot contains a non-terminal request, `submit`
returns `null` instead of evicting `PENDING` or `WORKING` work; callers should report backpressure
and retry later. Use `failOwned(claim, reply, statusMessage)` when processing fails so the request
ends as `FAILED` while retaining the diagnostic reply. For unowned mutations,
`updateStatusDetailed` and `replyDetailed` return `MutationOutcome` values such as `LEASED`,
`ALREADY_TERMINAL`, and `INVALID_TRANSITION` instead of making a rejected write look successful.

`sendStatusToPlayer(server, request)` and `sendReplyToPlayer(server, request)` reuse the base
mod's localized, chat-sized notification behavior.

## Events

Addon events use the same history and subscription path as built-in server events:

```java
JsonObject data = new JsonObject();
data.addProperty("kind", "myaddon_finished");
AgentEventApi.Event event = AgentLinkApi.events().publish("myaddon", data);

List<AgentEventApi.Event> recent = AgentLinkApi.events().since(
        lastSeq, 100, Set.of("myaddon"));
```

Publishing appends to the bounded history and broadcasts to authenticated sessions subscribed to
the topic. Event payloads are copied at the API boundary.

## Tasks

`AgentLinkApi.tasks()` exposes task state, cooperative cancellation, and an addon submission path for
long work that has already passed the current tool's approval pipeline:

```java
AgentTaskApi.TaskInfo task = AgentLinkApi.tasks().get(taskId);
if (task != null && !task.status().terminal()) {
    AgentLinkApi.tasks().cancel(task.id());
}
```

An addon's fast `invoke` may submit its own `TaskContext.Sliceable` implementation:

```java
AgentTaskApi.Submission submission = AgentLinkApi.tasks().submit(this, args, session);
```

The addon tool still goes through the dispatcher's approval pipeline; this API is not a permission
bypass. World access in each slice must return to the server thread.

For large block edits, addons can share one undo entry across slices:

```java
AgentBlockApi.Batch batch = AgentLinkApi.blocks().beginBatch(level, "my edit", true);
batch.set(pos, AgentLinkApi.blocks().parse("minecraft:stone"));
batch.flushUpdates();
AgentBlockApi.EditResult result = batch.commit();
String operationId = result.operationId();
// On cancellation, remove the partial edit without pushing another undo entry:
// batch.abort();
// Exact rollback is only allowed for the current native undo-stack top:
AgentBlockApi.OperationUndoResult undone = AgentLinkApi.blocks().undoOperation(operationId);
```

`Batch.operationId()` is available as soon as the batch starts, so an async addon can publish it in
task partial state. `commit()` records one undo operation; `abort()` restores writes without adding
another undo entry. Named undo only accepts the stack top, preventing an older prior snapshot from
overwriting a newer edit.

## Server thread

World access must stay on the Minecraft server thread. Addons can use the shared bridge rather than
depending on the internal `ServerThread` class:

```java
String result = AgentLinkApi.server().call(server, () -> {
    return server.getMotd().getString();
});
```

`call` runs inline when already on the server thread and otherwise waits with the default 30-second
ceiling. Use the overload with an explicit timeout for a bounded operation. Failures are reported as
`AgentApiException` with a structured `code()`.

## Roles and build zones

```java
boolean admin = AgentLinkApi.roles().isAdmin(playerUuid);
AgentRoleApi.Role role = AgentLinkApi.role(playerUuid, player.hasPermissions(2));
boolean canUseAgent = AgentLinkApi.canInteract(playerUuid, player.hasPermissions(2));
boolean inside = AgentLinkApi.buildZones().contains(
        "minecraft:overworld",
        new AgentBuildZoneApi.Box(0, 64, 0, 32, 100, 32));
```

These are read-only views of the operator's base configuration. They do not replace the normal
tool approval pipeline.

`ADMIN` comes from `[roles].admin_uuids` and may be used without OP. `OP` comes from vanilla
permission level 2 or higher. Everyone else is `PLAYER` and must be rejected by in-game Agent
commands and GUI handlers.

## Tool registration

Tool registration remains available through `AgentLinkApi.registerTool`. The base mod adds the
addon namespace, advertises the tool through MCP `tools/list`, and routes it through the normal
approval and audit layers.

## Compatibility rules

- Keep addon imports inside `world.agentlink.api`.
- Do not cast API records back to internal buffer/task/event classes.
- Treat returned JSON objects as snapshots and do not mutate them expecting server state to change.
- Stop addon workers and child processes from the addon's server-stopping hook.
- Request queue consumers must tolerate the same request being visible to more than one poll cycle.

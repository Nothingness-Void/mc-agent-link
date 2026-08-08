# 附属 mod Java API

`mc-agent-link` 为 Forge 附属 mod 提供一层稳定的 Java API。附属 mod 应只导入
`world.agentlink.api.*`；`world.agentlink.dispatch`、`transport`、`task`、`events`、`agent`
等包都属于实现细节，后续可以重构。

当前 API 版本随 base `0.5.0-alpha` 发布，统一入口是 `AgentLinkApi`：

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

## 类型化服务器控制

这些 facade 按领域拆开，附属 mod 不需要复制 MCP 参数解析，也不需要依赖
`world.agentlink.dispatch` 内部包。它们操作 Minecraft 原生类型，必须在 server thread 调用；
可以直接从服务端事件调用，也可以通过 `AgentLinkApi.server().run/call` 切回主线程。

```java
ServerPlayer player = AgentLinkApi.players().online(server, "void");
ItemStack stack = AgentLinkApi.items().parse("minecraft:diamond_sword{Unbreakable:1b}", 1);
AgentLinkApi.players().whitelistAdd(server, player.getGameProfile());
AgentLinkApi.serverControl().save(server, true);
```

- `items()`：解析完整 vanilla 物品语法，并保留精确 NBT 类型。
- `nbt()`：提供无损 SNBT/JSON 转换，以及 vanilla NBT path 操作。
- `players()`：解析在线/缓存档案，管理踢出、封禁、OP 和白名单。
- `inventory()`：提供玩家背包槽位写入、丢弃、交换和客户端同步。
- `effects()`：解析、施加、列出和清除生物状态效果。
- `entities()`：解析已加载实体，控制骑乘、生命值、运动、装备、属性、常用标记、着火、传送和生命周期。
- `entitySpawn()`：使用可选 NBT、名称、持久化和无 AI 参数生成已加载实体。
- `worlds()`：解析维度，控制时间、天气、难度、gamerule、共享出生点和世界边界。
- `chunks()`：管理强加载区块，方便附属 mod 显式加载并释放工作区域。
- `blocks()`：解析原生方块语法，并为附属 mod 提供有界、可撤销的写入。
- `scoreboards()`：管理目标、分数、展示槽、队伍和成员。
- `messages()`：向单个玩家或全部在线玩家发送原生聊天组件。
- `serverControl()`：保存、资源重载、运行时视距、全员允许作弊和显式确认停服。重启留给
  JVM 外部的 supervisor。
- `containers()`：对箱子等方块容器执行类型化槽位写入。
- `progression()`：基于 live registry 管理配方解锁和进度标准。

## 诊断 API

`AgentLinkApi.diagnostics()` 与内置 MCP 工具 `server_diagnose` 共用同一个有边界的诊断收集器。
需要读取 live world 的部分会在必要时切回 server thread；addon 仍然不应在每个 tick 的热路径中调用它：

```java
JsonObject snapshot = AgentLinkApi.diagnostics().snapshot(server);
JsonArray findings = snapshot.getAsJsonObject("diagnosis").getAsJsonArray("findings");
```

结果包含原始的 `server_stats`、`tick_profile`、仅用于时间关联的 `tick_incidents`、`world`、可用时的 incident ledger，以及可选的
日志/线程/mod/崩溃数据、各组件耗时和保守的候选根因。`findings` 是基于证据的提示，不保证就是最终根因。`Options.timeoutMs()`
是整个快照的总预算，默认 12 秒，范围 1--30 秒；预算耗尽前尚未开始的组件会跳过并标记
`status: "timeout"`，顶层 `complete` 会变为 false。可以使用 `Options` 限制大小或关闭组件。

这些 facade 覆盖稳定的类型化 vanilla 控制面。对于第三方 mod 自定义命令，或者尚未值得单独
抽象的 vanilla 功能，仍保留 `run_console_command` 作为明确的兜底；常见的方块、NBT、玩家、
实体、世界和服务器控制不需要再退回字符串命令。

这些 API 是底层构件，不是绕过审批的入口：通过 `AgentLinkApi.registerTool` 注册的 addon MCP
工具仍然经过统一审批和审计。addon 自己直接调用这些 API 属于 addon 自身的可信代码，也必须
自行保证在 server thread 执行。

## 请求队列

不要再直接访问 `AgentRequestBuffer`，使用请求 API：

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
    // 处理已经认领的请求，再用同一个 lease 完成它。
    AgentRequestApi.Request done = requests.replyOwned(claim, reply, true);
}
```

这个队列仍然是共享环形缓冲区。`since(...)` 只是游标读取，`claimOwned(...)` 才是把请求从
`PENDING` 原子切换到 `WORKING`。返回的 `owner + generation` lease 是
`replyOwned(...)` 和 `updateStatusOwned(...)` 的必要凭据，旧 worker 或其他消费者不能覆盖
请求。如果 claim 返回 `null`，说明请求已经被其他消费者认领、取消或被淘汰，addon 应跳过它。
旧的 `claim/reply` 方法保留用于兼容，新消费者应使用带 lease 的方法。

环形队列容量固定为 128。当下一个槽位仍是非终态请求时，`submit` 会返回 `null`，不会淘汰
`PENDING` 或 `WORKING` 请求；调用方应向用户报告背压并稍后重试。处理失败时使用
`failOwned(claim, reply, statusMessage)`，请求会进入 `FAILED`，同时保留诊断回复。无租约的
MCP 风格变更可使用 `updateStatusDetailed` 和 `replyDetailed`，通过 `MutationOutcome` 区分
`LEASED`、`ALREADY_TERMINAL`、`INVALID_TRANSITION` 等结果，避免把被拒绝的写入误报成成功。

`sendStatusToPlayer(server, request)` 和 `sendReplyToPlayer(server, request)` 会复用 base
统一的本地化消息和聊天分段逻辑。

## 事件

附属 mod 发布的事件会进入和 base 事件相同的历史缓冲区及订阅通道：

```java
JsonObject data = new JsonObject();
data.addProperty("kind", "myaddon_finished");
AgentEventApi.Event event = AgentLinkApi.events().publish("myaddon", data);

List<AgentEventApi.Event> recent = AgentLinkApi.events().since(
        lastSeq, 100, Set.of("myaddon"));
```

发布事件会写入有界历史，并广播给订阅该 topic 的已认证会话。API 边界会复制事件 payload，
调用方不要依赖修改原始 JSON 来改变服务端状态。

## 异步任务

`AgentLinkApi.tasks()` 提供任务状态、协作式取消，以及 addon 在已经通过当前工具审批后提交长任务的入口：

```java
AgentTaskApi.TaskInfo task = AgentLinkApi.tasks().get(taskId);
if (task != null && !task.status().terminal()) {
    AgentLinkApi.tasks().cancel(task.id());
}
```

addon 的快速 `invoke` 可以提交自己实现的 `TaskContext.Sliceable` 工具：

```java
AgentTaskApi.Submission submission = AgentLinkApi.tasks().submit(this, args, session);
```

工具本身仍然先经过 dispatcher 的审批流水线；这个 API 不负责替 addon 绕过审批。长任务的每个
世界写入切片必须通过 `AgentLinkApi.server().run/call` 回到 server thread。

方块 addon 可以让整次长编辑共用一个 undo 记录，并在切片之间发送邻居更新：

```java
AgentBlockApi.Batch batch = AgentLinkApi.blocks().beginBatch(level, "my edit", true);
batch.set(pos, AgentLinkApi.blocks().parse("minecraft:stone"));
batch.flushUpdates();
AgentBlockApi.EditResult result = batch.commit();
String operationId = result.operationId();
// 任务取消时不留下半成品：
// batch.abort();
// 需要精确撤销时只能撤销当前 native undo 栈顶：
AgentBlockApi.OperationUndoResult undone = AgentLinkApi.blocks().undoOperation(operationId);
```

`Batch` 的 `operationId()` 在开始批次时就可用，适合放入异步任务的 `partial` 状态；`commit()` 会将
 变更作为一个 undo 操作提交，`abort()` 会恢复已写入的方块但不新增 undo 记录。按操作号撤销只接受
  undo 栈顶，避免跳过中间编辑后恢复过期的 prior state。

## 主线程

世界状态只能在 Minecraft server thread 访问。addon 可以使用共享 bridge，不需要依赖内部的
`ServerThread`：

```java
String result = AgentLinkApi.server().call(server, () -> {
    return server.getMotd().getString();
});
```

如果调用已经在主线程上，会直接执行；否则会在默认 30 秒上限内等待。需要其他上限时使用带
timeout 的重载。失败会抛出带结构化 `code()` 的 `AgentApiException`。

## 角色和 build zone

```java
boolean admin = AgentLinkApi.roles().isAdmin(playerUuid);
AgentRoleApi.Role role = AgentLinkApi.role(playerUuid, player.hasPermissions(2));
boolean canUseAgent = AgentLinkApi.canInteract(playerUuid, player.hasPermissions(2));
boolean inside = AgentLinkApi.buildZones().contains(
        "minecraft:overworld",
        new AgentBuildZoneApi.Box(0, 64, 0, 32, 100, 32));
```

这些 API 只是 operator 配置的只读视图，不能替代普通工具审批流水线。

`ADMIN` 来自 `[roles].admin_uuids`，不要求同时是 OP；`OP` 来自原生权限等级 2+。其他玩家
都是 `PLAYER`，游戏内 Agent 命令和 GUI handler 必须拒绝他们。

## 工具注册

`AgentLinkApi.registerTool` 仍然可用。base 会自动添加 addon namespace，在 MCP `tools/list` 中
公布工具，并让调用进入统一的审批和审计流程。

## 兼容规则

- addon 的 import 尽量只落在 `world.agentlink.api`。
- 不要把 API record 强转回内部 buffer、task、event 类型。
- 返回的 JSON 和 record 都是快照，修改它们不会改变服务端状态。
- addon 必须在 server stopping 时停止自己的 worker 和子进程。
- 请求消费者必须允许同一请求在多个轮询周期中重复可见。

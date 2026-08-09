# AgentLink Spigot 插件

这是 `mc-agent-link` 的现代 Spigot/Paper 基础链接实现，面向 Minecraft 1.20+，使用 Java 17 字节码。它只负责让 Claude Code、Codex、Cursor 或其他 MCP agent 连接服务器，不包含 `mc-agent-link-agent` 的游戏内 agent 控制功能。

默认 JAR 使用 1.20.1 的最低公共 Bukkit API 编译，不依赖 NMS，因此同一个 JAR 可用于现代 Spigot/Paper 服务端。版本敏感功能会通过 Bukkit API 能力检查暴露给 agent；新增 API 不会被默认产物直接引用。

## 明确不包含

- 不注册 `/agent`，不提供 GUI
- 不提供游戏内 agent 请求队列、steer 或 Claude Code bridge
- 不需要安装 `mc-agent-link-agent`

## 包含功能

- MCP Streamable HTTP：`http://127.0.0.1:25581/mcp`
- 兼容旧客户端的 v0 WebSocket：`127.0.0.1:25580`
- 一次性本地 setup endpoint 配对和 hash-only token registry
- console / guest token 权限层
- guest 写操作的 OP/admin 审批
- `whoami`、服务器状态、玩家/世界读取、事件拉取、JVM 线程诊断
- 受 `write-allow` / `write-deny` 限制的文件读写
- 可撤销的方块写入：`set_block`、`fill_blocks`、`set_blocks`、`undo_blocks`
- 基础玩家、实体、世界操作工具

## 构建

需要 JDK 17 构建插件。Minecraft 服务端自身的 Java 要求仍以对应 Minecraft 版本为准：1.20.1 通常使用 Java 17，1.20.5+/1.21 通常使用 Java 21；插件 Java 17 字节码可运行在两者之上。

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.3.1'
.\gradlew.bat clean build
```

默认通用产物：`build/libs/agent-link-spigot-modern-0.5.0-alpha.jar`。

要验证并生成多个现代 API 版本的目标包：

```powershell
.\build-modern.ps1 -JavaHome 'C:\Program Files\Java\jdk-17.0.3.1'
```

脚本会编译并复制 `1.20.1`、`1.20.6`、`1.21.1`、`1.21.11` 四个目标到 `build/modern-releases/`。其中 `agent-link-spigot-modern-*.jar` 是推荐的跨版本包；其余包用于按服务端 API 锁定版本或排查兼容性。

## 安装

把通用 jar 放入服务端的 `plugins/`，启动 Spigot/Paper 1.20+。首次未配对时插件会在控制台输出本地 setup endpoint，例如 `http://127.0.0.1:25581/pair/setup/<random-id>`。把这条 URL 交给 MCP agent，agent 会直接读取 JSON 并完成配对，不依赖 GitHub。配对成功后 token 会持久化，agent 使用返回的 `mcp` 对象作为 `mcpServers.minecraft`。

插件配置位于 `plugins/AgentLink/config.yml`。默认只监听本机；跨机器连接前应配置防火墙，并将 `mcp.allowed-origins` 收紧到可信来源。

## 管理命令

插件只注册 `/agentlink`：

```text
/agentlink pair
/agentlink pair-guest
/agentlink status
/agentlink tokens
/agentlink revoke <hash-prefix>
/agentlink approvals
/agentlink approve <id>
/agentlink deny <id>
/agentlink reload
```

`pair` 生成的 console-tier token 跳过 guest 审批；`pair-guest` 生成的 guest token 对写操作要求在线 OP 或 `agentlink.admin` 玩家审批。`reload` 重新读取配置，但端口和 token 相关变更需要重启服务端。

## 验证

先按控制台打印的本地 setup endpoint 完成配对，再从 MCP host 调用 `initialize`、`tools/list`、
`whoami`、`ping` 和 `get_server_capabilities`。重启后不需要重新配对；需要新增 agent 时执行
`/agentlink pair` 或 `/agentlink pair-guest`。

如果只用浏览器或 PowerShell 对 `/mcp` 发 GET 请求，返回 `405 Method Not Allowed` 是预期行为；
MCP listener 只接受带 `Authorization` 的 JSON-RPC POST。工具列表不会出现 `agent_*`、`steer`
或游戏内请求队列工具。

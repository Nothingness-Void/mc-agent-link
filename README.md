# mc-agent-link

让 Claude Code、Codex、Cursor 或其他 MCP agent 连接正在运行的 Minecraft 服务器。
它提供结构化的服务器读取、诊断、玩家/实体/世界控制、方块写入、异步任务和事件接口，减少对
`run_console_command` 的依赖。

> 状态：`0.5.0-alpha`。当前维护 Forge 1.20.1、NeoForge 1.21.1，以及 Spigot/Paper 1.20+ 基础链接插件。

[English README](README.en.md) · [安装指南](INSTALL.md) · [配对说明](docs/pairing.zh-CN.md) · [工具目录](docs/tools.md) · [API 参考](docs/api.zh-CN.md) · [附属架构](docs/addons.md) · [诊断工具](tools/README.md)

## 版本支持

| Minecraft | 平台 | Java / 运行时 | 目录 | 游戏内附属 |
|---|---|---:|---|---|
| 1.20.1 | Forge | 17 | `minecraft/forge-mod` | [mc-agent-link-agent](https://github.com/Nothingness-Void/mc-agent-link-agent) |
| 1.21.1 | NeoForge | 21 | `minecraft/neoforge-mod` | [mc-agent-link-agent](https://github.com/Nothingness-Void/mc-agent-link-agent) |
| 1.20+ | Spigot/Paper | 插件 Java 17；服务端按 MC 版本 | `minecraft/spigot-plugin` | 不需要；只提供基础 agent-link |

Spigot/Paper 的现代兼容构建目标为 1.20.1、1.20.6、1.21.1 和 1.21.11；推荐使用 `agent-link-spigot-modern-*.jar`。
插件以 Java 17 字节码构建，但服务端自身仍需遵守 Minecraft 版本的 Java 要求：1.20.1 通常为 Java 17，1.20.5+/1.21 通常为 Java 21。

Forge 和 NeoForge 共享 MCP、配对、权限、请求队列、异步任务、诊断和 `AgentLinkApi`，但加载器实现分开，不能混装 jar。
基础 mod 提供 `/agentlink` 管理命令、MCP 工具和附属 API；`/agent`、GUI、steer 以及 Claude 本地进程桥接属于外部附属 mod。
Spigot/Paper 插件是独立的基础连接实现，包含一组 Bukkit 兼容的读取、诊断、事件、文件和基础世界操作工具，但不包含 `/agent`、GUI、游戏内请求队列或 `steer`。

游戏内附属 mod 不在本仓库中维护；它依赖匹配版本的 Forge/NeoForge 基础 mod API。需要 `/agent`、GUI、游戏内消息队列或 steer 时，必须额外安装附属 mod。

## 快速开始

### 傻瓜式安装

1. 下载与服务器匹配的 jar，放到正确目录：
   - Forge 1.20.1：`agent-link-forge-1.20.1-*.jar` 放入 `mods/`
   - NeoForge 1.21.1：`agent-link-neoforge-1.21.1-*-all.jar` 放入 `mods/`
   - Spigot/Paper 1.20+：`agent-link-spigot-modern-*.jar` 放入 `plugins/`
2. 启动服务器，在控制台找到并复制完整的本地 setup endpoint：

   ```text
   agent-link local setup endpoint ...: http://127.0.0.1:25581/pair/setup/<random-id>
   ```

   新版本配对不需要打开 GitHub，也不要复制 `config/agent-link.toml` 里的 token。
3. 把整条 endpoint 发给 Claude Code、Codex、Cursor 或其他 MCP agent。agent 会直接读取 endpoint，完成一次配对，
   将返回的 `mcp` 配置合并到 `mcpServers.minecraft`，然后按顺序调用 `whoami` 和 `ping` 验证连接。

setup endpoint 10 分钟内一次性有效。首次配对尚未成功时，过期后服务端会自动打印新的本地 endpoint；也可以由管理员运行
`/agentlink pair` 立即生成 CONSOLE 配对，或运行 `/agentlink pair-guest` 生成需要游戏内审批的 GUEST 配对。

配对成功后 bearer token 会持久化，服务端重启不会继续刷新或重新打印 endpoint，这是正常现象。需要添加第二个 agent，或 host 配置丢失时，
再运行对应的配对命令；如果返回 `used`，先检查已有 MCP host 配置，通常表示配对已经成功。

服务器与 agent 不在同一台机器时，Forge/NeoForge 修改 `config/agent-link.toml` 的 `allow_remote`、`mcp_public_host` 和
`mcp_allowed_origins`；Spigot/Paper 修改 `plugins/AgentLink/config.yml` 中 `mcp.allow-remote`、`mcp.public-host` 和
`mcp.allowed-origins`，然后重启服务器并使用新打印的 endpoint。不要把 `127.0.0.1` 的 MCP 地址手工改成远程 IP。

旧版本可能打印 GitHub fragment setup link。它仍可兼容使用，但只是旧版兼容路径，新安装优先使用服务器直接打印的本地 endpoint。

详细的本机、远程和失败恢复流程见 [docs/pairing.zh-CN.md](docs/pairing.zh-CN.md)。

## 核心能力

- 连接与自检：MCP Streamable HTTP、本地一次性配对、持久 bearer token、兼容旧客户端的 WebSocket，以及 `whoami` 权限边界查询。
- 服务器读取与诊断：玩家、实体、世界、方块、维度、mod、日志、崩溃报告、TPS/MSPT、tick incident、JVM thread dump 和有总预算的 `server_diagnose`。
- 结构化控制：玩家、实体、世界、物品、容器、计分板、数据包、世界边界/出生点、白名单/OP、NBT 和服务器保存/重载等操作。
- 建造与恢复：不依赖 WorldEdit 的批量方块写入、搜索、快照、撤销；安装 WorldEdit/FAWE 后可使用 `we_*` 工具和独立的 WorldEdit 撤销栈。
- 长任务与事件：`start_task`、`get_task`、`cancel_task`、`list_tasks` 将大操作从单个 RPC 中解耦；事件支持 pull，兼容 WebSocket 的客户端可订阅推送。
- 权限与审计：CONSOLE/GUEST 配对层、ADMIN/OP/PLAYER 游戏内角色、四级工具审批、`build_zones`、文件沙盒、敏感参数脱敏和 JSONL 审计日志。
- 扩展能力：`AgentLinkApi` 提供请求、事件、任务、诊断、主线程、方块、NBT、玩家、实体、世界、容器、进度和服务器控制 facade；附属 mod 可注册带 namespace 的 MCP 工具。
- 可选集成：Forge/NeoForge 可探测 spark、WorldEdit/FAWE；JVM 已退出时可使用 `tools/agent-link-watchdog.ps1` 保全日志、崩溃报告和 incident 现场。

完整入参和返回字段以 [docs/tools.md](docs/tools.md) 为准；协议错误码和文件沙盒见 [docs/protocol.md](docs/protocol.md)。

## 架构

```text
Claude Code / Codex / Cursor
        │ MCP Streamable HTTP
        ▼
Minecraft 服务端的 agent-link mod / plugin
        ├── MCP :25581
        ├── WebSocket :25580（兼容路径）
        └── 审批、权限、队列、任务和工具执行
```

所有世界状态访问都回到 Minecraft server thread；需要等待、文件 I/O 或大范围切片写入的工作使用受控 worker/task 路径。
`start_task` 是服务器操作的异步任务接口，不是通用的异步 agent 对话调度器；MCP host 仍按请求/响应工作。
当前项目是服务器连接层，不包含“Agent 作为真实玩家登录”的 Bot 运行时。`/agent` 请求队列、GUI 和 steer 由外部附属消费基础 API。

## 仓库结构

```text
mc-agent-link/
├── minecraft/forge-mod/       # Forge 1.20.1 基础 mod
├── minecraft/neoforge-mod/    # NeoForge 1.21.1 基础 mod
├── minecraft/spigot-plugin/   # Spigot/Paper 1.20+ 基础链接插件
├── packages/mcp-server/       # 旧 host 的 Node stdio/WebSocket bridge
├── docs/                      # 协议、配对、工具、API、附属架构和发行说明
├── tools/                     # JVM 退出现场 watchdog 和契约检查
├── .claude/skills/            # Claude Code 诊断技能
├── INSTALL.md                 # 面向 agent 的完整安装流程
└── AGENTS.md                  # setup link 和安全规则
```

## 安全边界

- 默认只监听本机；远程使用必须显式开启 `allow_remote`，设置 `mcp_public_host`、收紧 Origin 白名单并配置防火墙。
- 配对分为 CONSOLE 和 GUEST：`/agentlink pair` 用于管理员本人监督的后台连接，`/agentlink pair-guest` 保留游戏内审批。
- 游戏内附属使用 ADMIN、OP、PLAYER 三类角色；普通玩家不能提交或操作 `/agent` 请求。配置了 `roles.admin_uuids` 后，敏感审批只交给指定 ADMIN。
- GUEST 工具按 auto-allow、trusted、普通审批、admin-only 四级处理；空间写入可用 `build_zones` 限定完整 footprint，跨界不会被静默裁剪。
- 先调用 `whoami`，再执行写入或服务器控制操作。`run_console_command` 是高权限兜底，优先使用结构化工具；停止服务器、杀实体等高风险操作还需要显式确认。
- 文件写入默认只允许 `config/**`，会自动备份、拒绝路径逃逸，并对 guest 读取和审计日志中的 token、pair code、身份及敏感参数脱敏。
- 大操作必须使用 `start_task`，不要让客户端 RPC 超时后继续猜测任务状态；任务取消可能留下部分写入，需根据返回结果使用 undo。

## 路线图

- [x] Forge 1.20.1 基础 mod
- [x] NeoForge 1.21.1 基础 mod
- [x] Spigot/Paper 1.20+ 基础链接插件
- [x] 持久配对、异步任务、结构化控制、诊断、权限、审计、build zones 和 addon API
- [x] Spigot/Paper 现代 API 多目标构建
- [ ] Fabric 实现
- [ ] CI 和更完整的自动化测试
- [ ] 独立的真实玩家 Bot 运行时

## License

[Apache-2.0](LICENSE)

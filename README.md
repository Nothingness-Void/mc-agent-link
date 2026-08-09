# mc-agent-link

让 Claude Code、Codex、Cursor 或其他 MCP agent 连接正在运行的 Minecraft 服务器。
它提供结构化的服务器读取、诊断、玩家/实体/世界控制、方块写入、异步任务和事件接口，减少对
`run_console_command` 的依赖。

> 状态：早期版本。当前维护 Forge 1.20.1、NeoForge 1.21.1，以及 Spigot/Paper 1.20+ 基础链接插件。

[English README](README.en.md) · [安装指南](INSTALL.md) · [配对说明](docs/pairing.zh-CN.md) · [工具目录](docs/tools.md) · [API 参考](docs/api.zh-CN.md)

## 版本支持

| Minecraft | 平台 | Java | 目录 | 游戏内附属 |
|---|---|---:|---|---|
| 1.20.1 | Forge | 17 | `minecraft/forge-mod` | [mc-agent-link-agent](https://github.com/Nothingness-Void/mc-agent-link-agent) |
| 1.21.1 | NeoForge | 21 | `minecraft/neoforge-mod` | [mc-agent-link-agent](https://github.com/Nothingness-Void/mc-agent-link-agent) |
| 1.20+ | Spigot/Paper | 17+ | `minecraft/spigot-plugin` | 不需要；只提供基础链接 |

Forge 和 NeoForge 共享 MCP、配对、权限、请求队列、异步任务和 addon API，但加载器实现分开，不能混装 jar。
Spigot/Paper 插件是独立实现，只负责 agent-link，不包含 `/agent`、GUI、游戏内请求队列或 `steer`。

游戏内附属 mod 不在本仓库中维护；它依赖基础 mod 的 API。需要 `/agent`、GUI 或游戏内消息队列时，必须安装匹配的基础 mod 和附属 mod。

## 快速开始

1. 从 GitHub Release 下载与服务器平台匹配的 jar，或按 [安装指南](INSTALL.md) 构建。Forge/NeoForge 放入 `mods/`，Spigot/Paper 放入 `plugins/`。
2. 启动服务器，复制控制台打印的 `agent-link local setup endpoint`。新版本配对不需要访问 GitHub。
3. 把完整 endpoint 发给 Claude Code、Codex 或其他 MCP agent。agent 会完成配对、合并 `mcpServers.minecraft`，然后先调用 `whoami` 再调用 `ping`。

配对成功后 token 会持久化，服务端重启不会持续刷新链接。需要添加第二个 agent 时，由管理员执行 `/agentlink pair` 或 `/agentlink pair-guest`。
详细的本机、远程和失败恢复流程见 [docs/pairing.zh-CN.md](docs/pairing.zh-CN.md)。

## 核心能力

- 读取服务器状态、玩家、实体、方块、维度、日志、崩溃信息和 mod 列表。
- 结构化控制玩家、实体、世界、物品、容器、计分板和数据包。
- 原生方块写入、批量操作、快照和撤销；长操作使用 `start_task`，分 tick 执行并支持取消。
- 事件 pull、请求队列、统一审批、审计日志和权限边界查询。
- 可选集成 spark、WorldEdit 和第三方 addon API。

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

所有世界状态访问都回到 Minecraft server thread；长任务和文件操作不会把一次大请求阻塞在单个 RPC 中。
当前项目是服务器连接层，不包含“Agent 作为真实玩家登录”的 Bot 运行时。

## 仓库结构

```text
mc-agent-link/
├── minecraft/forge-mod/       # Forge 1.20.1 基础 mod
├── minecraft/neoforge-mod/    # NeoForge 1.21.1 基础 mod
├── minecraft/spigot-plugin/   # Spigot/Paper 1.20+ 基础链接插件
├── packages/mcp-server/       # 旧 host 的 Node stdio/WebSocket bridge
├── docs/                      # 协议、配对、工具、API 和发行说明
├── .claude/skills/            # Claude Code 诊断技能
├── INSTALL.md                 # 面向 agent 的完整安装流程
└── AGENTS.md                  # setup link 和安全规则
```

## 安全边界

- 默认只监听本机；远程使用必须显式开启 `allow_remote` 并配置 public host、Origin 和防火墙。
- 先调用 `whoami`，再执行写入或服务器控制操作。
- `run_console_command` 是高权限兜底；优先使用结构化工具。
- 文件写入默认只允许 `config/**`，会自动备份并拒绝路径逃逸。
- 长操作必须使用 `start_task`，不要让客户端 RPC 超时后继续猜测任务状态。

## 路线图

- [x] Forge 1.20.1 基础 mod
- [x] NeoForge 1.21.1 基础 mod
- [x] Spigot/Paper 1.20+ 基础链接插件
- [x] 持久配对、异步任务、诊断、权限和 addon API
- [ ] Fabric 实现
- [ ] CI 和更完整的自动化测试
- [ ] 独立的真实玩家 Bot 运行时

## License

[Apache-2.0](LICENSE)

# mc-agent-link

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

让 AI agent(通过 [Model Context Protocol](https://modelcontextprotocol.io))接管你的 Minecraft 服务器。跑命令、查玩家、读日志、看 crash 报告、profile 卡顿、调 mod 配置,以及**原生方块写入、NBT 读写、玩家/实体/世界控制、异步长任务** —— op 在控制台能干的事 agent 都能干,而且大部分不用再走 `run_console_command`。

> **状态**:早期。当前维护 Forge 1.20.1、NeoForge 1.21.1，以及面向 Spigot/Paper 1.20+ 的基础链接插件。

## 版本支持

| Minecraft | 加载器 | Java | 基础 Mod | 可选游戏内附属 |
|---|---|---:|---|---|
| 1.20.1 | Forge | 17 | `minecraft/forge-mod` | `mc-agent-link-agent` 根项目 |
| 1.21.1 | NeoForge | 21 | `minecraft/neoforge-mod` | `mc-agent-link-agent/neoforge-mod` |
| 1.20+ | Spigot/Paper | 17+* | `minecraft/spigot-plugin` | 不需要附属；只提供基础链接 |

Forge/NeoForge 两个基础 Mod 使用同一套 MCP、权限、请求队列、异步任务和 addon API；加载器入口、事件总线和网络 payload 层分别适配。不要把 Forge jar 和 NeoForge jar 混装。

Spigot 插件是独立实现：它复用 agent-link 的 MCP/配对协议，但不提供 `mc-agent-link-agent` 的 `/agent`、GUI、游戏内请求队列或 steer 功能。默认 JAR 使用 Bukkit 1.20.1 最低公共 API 编译，面向现代 1.20+ Spigot/Paper。完整安装和构建说明见 [`minecraft/spigot-plugin/README.md`](minecraft/spigot-plugin/README.md)。

*插件字节码以 Java 17 构建；服务端 JVM 要求仍按 Minecraft 版本决定，1.20.5+/1.21 通常需要 Java 21。

[English README](README.en.md) · [安装指南(给 agent 用)](INSTALL.md) · [协议规范](docs/protocol.md)

## 权限模型

项目分成两层权限：

- **后台级**：管理员通过 `/agentlink pair` 生成的 console-tier 连接，拥有全部工具权限并跳过游戏内审批。这个令牌只应放在管理员自己的 Codex/Claude 主机中。
- **游戏内级**：管理员在 `config/agent-link.toml` 的 `[roles].admin_uuids` 中分配 ADMIN；原生权限等级 `2+` 的玩家是 OP。ADMIN 和 OP 可以使用 `/agent` 与 GUI，普通玩家不能打开或操作 Agent。
- ADMIN 可以审批普通工具和管理员工具；OP 只能审批普通工具；普通玩家不能审批或提交 Agent 请求。

命令、GUI 网络包和服务端请求提交器都会重复执行服务端权限检查，客户端隐藏界面不是安全边界。

## 为什么有这个项目

Claude Code、Cursor、自定义 agent 都说 MCP,但 Minecraft 服务器不说。RCON 又只能"问一句答一句",拿不到事件流、profile 数据、crash 上下文。

`mc-agent-link` 在服务端模组内直接暴露 MCP HTTP 端点,并保留 WebSocket 协议给非 MCP 客户端和旧 bridge。多 agent 可以同时连进来,各干各的。

## 它能干什么

| 类别 | 工具 | 用途 |
|---|---|---|
| 自查 | `whoami` | agent 第一件事该调的:自己的 token tier、哪些工具免审批、有没有 OP 在线能审批、写文件白名单、build zones、各工具体积上限、WorldEdit/spark 装没装 |
| 操作 | `ping` `run_console_command` `broadcast` | 跑控制台命令、广播消息 |
| 玩家 | `list_online_players` `get_player_info` `get_player_inventory` | 在线列表、详细位姿/朝向/look_target/状态效果、完整背包 |
| 世界读取 | `get_world_info` `get_block` `get_blocks_region` `find_blocks` `get_biome` `raycast` `list_entities_near` `list_dimensions` | 时间/天气/seed、单点方块、区域 RLE(≤4096)、**大范围找方块只返回命中点**、群系、自由射线、附近实体 |
| 世界写入(原生,不需要 WorldEdit) | `set_block` `fill_blocks` `set_blocks` `undo_blocks` `save_block_snapshot` `list_snapshots` `restore_block_snapshot` | 单点/长方体/任意点集写入,支持 blockstate + 方块实体 NBT;独立撤销栈;快照存取闭环(带 offset 即 copy-paste) |
| NBT | `get_nbt` `set_nbt` | 方块实体/实体/玩家/背包槽的原始 NBT 读写,支持 NBT path;附魔、村民交易、刷怪笼、模组内部数据全都能碰 |
| 玩家/实体控制 | `teleport` `give_item` `set_gamemode` `apply_effect` `spawn_entity` `remove_entities` `modify_entity` `manage_players` `manage_player_inventory` `control_entity` | 传送(含跨维度/落到地表)、给物品(带 NBT)、切模式、状态效果、生成/清理/改实体、玩家管理、槽位级背包和实体关系/属性 |
| 容器/玩家数据 | `manage_container` `set_player_state` `manage_player_progression` | 箱子等容器槽位、玩家生命/经验/能力、配方和进度 |
| 世界/服务器控制 | `set_world_property` `set_world_spawn` `set_world_border` `force_load_chunks` `save_world` `server_control` | 时间/天气/难度/gamerule、出生点、世界边界、区块强加载、落盘、资源重载、视距和受控停服 |
| 数据包 | `manage_datapacks` | 列出、启用/禁用和重载已选数据包 |
| 计分板 | `get_scoreboard` `manage_scoreboard` | 目标、分数、展示槽、队伍和成员的结构化读写 |
| 异步任务 | `start_task` `get_task` `cancel_task` `list_tasks` | 长任务从单次 RPC 解耦:立刻返回 task_id,分 tick 执行 + 进度上报 + 可取消 |
| 注册表 | `list_block_ids` `list_item_ids` `list_entity_ids` `list_biome_ids` | 分页 + 子串过滤 |
| 诊断 | `server_diagnose` `get_server_stats` `tick_profile` `tick_incidents` `thread_dump` `list_mods` | 一次汇总健康快照、候选根因、TPS/MSPT、慢 tick 事故历史、世界/实体/区块、日志、线程、崩溃摘要和 mod 列表 |
| 游戏内请求 API | `agent_heartbeat` `get_agent_requests` `update_agent_request_status` `reply_agent_request` | Forge/NeoForge 基础 Mod 提供请求队列和 MCP API；游戏内 `/agent` 命令由可选附属 mod `mc-agent-link-agent` 提供；Spigot 插件明确不实现这一层 |
| 观察(pull) | `get_recent_events` `get_recent_logs` | 聊天/进出/死亡,外加 **command / container_open / entity_death / explosion / player_hurt / advancement / dimension_change**;`block_place` 等高频 topic 按需开启 |
| 文件(沙盒) | `list_dir` `read_server_file` `read_config` `write_config_file` | 服务端 root 下任意文件**只读**;`config/**` 才能写,且自动备份 |
| Spark 集成(选装) | `spark_status` `spark_stats` `spark_profiler_start/stop/cancel` `spark_health_report` | 装了 [spark](https://spark.lucko.me) mod 之后,agent 能跑火焰图、拿 viewer URL、读 GC 细节 |
| WorldEdit 集成(选装) | `we_status` `we_set` `we_replace` `we_sphere` `we_cyl` `we_undo` | 超大选区更快,球/柱生成是原生工具没有的;独立于 `undo_blocks` 的撤销栈 |
| 稳定 addon API | `world.agentlink.api.AgentLinkApi` `BaseAddonTool` | 可选附属 mod 能注册自己的 MCP 工具,自动加上 `<modid>__` 前缀,并出现在 HTTP `tools/list` 里 |

完整工具目录(含每个工具的入参/返回字段)见 [docs/tools.md](docs/tools.md)。协议字段、错误码、沙盒边界看 [docs/protocol.md](docs/protocol.md)。

附属 mod 的稳定 Java API 已模块化覆盖请求/事件/任务、诊断、主线程桥接、NBT、物品、玩家/背包、
状态效果、实体/生成、方块/撤销、区块、世界、计分板、容器、配方/进度和服务器生命周期。
入口是 `world.agentlink.api.AgentLinkApi`，完整中文参考见 [docs/api.zh-CN.md](docs/api.zh-CN.md)；
第三方 mod 未建模的命令仍可通过 `run_console_command` 兜底。

### 服务器诊断

遇到卡顿时优先调用 `server_diagnose`。它是有总时间预算的一次性只读快照，不会启动 profiler 或上传数据；返回原始证据、组件超时状态和保守的 `diagnosis.findings` 候选信号。默认预算 12 秒，可用 `timeout_ms` 调整到 1--30 秒。完整崩溃文本仍用返回路径调用 `read_server_file`。如果 JVM 已经退出，使用 [tools/README.md](tools/README.md) 中的外部 watchdog 保存退出现场；guest agent 读取配置和日志时会自动脱敏 token、pair code 和 OP 身份信息。

### 请求队列的可靠性

请求队列是 128 条容量的共享环形队列。队列满时不会覆盖正在处理或等待中的请求，新提交会得到明确的“队列已满”结果；消费方应稍后重试。addon worker 必须使用 `claimOwned`、`replyOwned` 或 `failOwned`，过期租约不能迟到回复。取消和重载会联动终止对应 Claude 进程，失败请求会保留 `FAILED` 状态和诊断回复。

### 两条边界值得单独说

**长任务必须走 `start_task`。** 超过同步体积上限的调用会返回 `VOLUME_TOO_LARGE`,并把该用的 `start_task` 调用原样写在错误信息里 —— agent 不需要自己切块。任务在 dedicated executor 上按 `tasks.blocks_per_tick` 分 tick 执行,服务器不会被一次大 fill 冻住。

**`build_zones` 让高频写入不必每次弹审批。** 在 `config/agent-link.toml` 里划一个 bbox,footprint **完整落在**区域内的空间写入直接跳过审批;跨界的照旧弹窗,绝不静默裁剪。默认空列表,即行为与 0.4.x 完全一致。

## 架构

```
 ┌──────────────────────┐    Streamable HTTP   ┌─────────────────────────────┐
 │  Agent(Claude Code, │ ───────────────────► │  Forge mod (Java)           │
 │  Cursor, 自定义...)   │   POST /mcp + JSON   │  minecraft/forge-mod        │
 └──────────────────────┘   Bearer auth        │  在 Minecraft 服务端 JVM 内  │
                                               └─────────────────────────────┘
                                                  │
                                                  │ 同时支持
                                                  ▼
                            ┌──────────────────────────────────────┐
                            │  WebSocket :25580 (非 MCP 客户端用)   │
                            │  + Node bridge (兼容老 host)         │
                            └──────────────────────────────────────┘
```

- **Forge mod** 跑在 Minecraft 服务端 JVM 里,起两个监听:
  - `:25581/mcp` —— MCP Streamable HTTP,host(Claude Code、Cursor、…)直接连
  - `:25580` —— 自家 WebSocket 协议,服务非 MCP 客户端(moderation bot、stdio bridge)
- 所有触碰世界状态的活儿都派发到主线程,线程安全。声明 `offThread()` 的工具(文件 IO、分片写入、等审批)跑在 worker 池上,需要碰世界时通过 `ServerThread.call` 跳回主线程 —— 慢工具不再拖 tick。
- **多 agent**:HTTP + WebSocket 各自接受多个并发连接。
- **游戏内审批**:MCP 工具调用可在游戏聊天里弹出 `[允许一次] [拒绝] [始终允许该工具] [复制详情]` 按钮,OP 点击即可审批,不需要手打命令。
- **可扩展**:附属 mod 可以通过 `world.agentlink.api.AgentLinkApi.registerTool(...)` 注册自定义工具;工具名会自动命名空间化成 `<modid>__<tool>`。

## 仓库结构

```
mc-agent-link/
├── .claude/skills/         # Claude Code 斜杠命令:/mc-overview /mc-diagnose /mc-crash /mc-health-check
├── docs/
│   └── protocol.md         # agent-link 线协议规范
├── minecraft/
│   ├── forge-mod/          # Forge 1.20.1 mod(Java 17, Gradle)
│   ├── neoforge-mod/       # NeoForge 1.21.1 mod(Java 21, Gradle)
│   └── spigot-plugin/      # Spigot/Paper 1.20+ plugin(Java 17; base link only)
├── packages/
│   └── mcp-server/         # Node MCP bridge(TypeScript,stdio fallback)
└── INSTALL.md              # 安装指南(给 agent 自动读取用)
```

## 快速开始

根据服务器加载器选择对应版本的基础 Mod 和可选附属 Mod:

- **Forge 1.20.1**: `minecraft/forge-mod` + `mc-agent-link-agent` 根项目
- **NeoForge 1.21.1**: `minecraft/neoforge-mod` + `mc-agent-link-agent/neoforge-mod`
- **Spigot/Paper 1.20+**: `minecraft/spigot-plugin`，使用 `agent-link-spigot-modern-*.jar`，只放入服务端 `plugins/`，不安装 `mc-agent-link-agent`

傻瓜式安装:

1. **装基础 mod**:把所选版本的 `agent-link-*.jar` 丢进服务器 `mods/`,启动服务器；需要游戏内 `/agent` 命令时，再放入同版本的附属 jar。
2. **复制本地 setup endpoint**:首次未配对时,控制台会打印一行 `agent-link local setup endpoint (...)`,形如 `http://127.0.0.1:25581/pair/setup/<random-id>`。agent 直接 GET 这条 URL,不需要访问 GitHub。
3. **发给 agent**:把整条本地 URL 发给 Claude Code / Cursor / 自定义 agent。agent 会自动 GET/POST 换取 MCP 配置,只合并写入 host 配置中的 `mcpServers.minecraft`,再按 `whoami`、`ping` 顺序验证。配对成功后 token 会持久化,重启服务器不会继续刷新链接；要配第二个 agent 时管理员再运行 `/agentlink pair` 或 `/agentlink pair-guest`。

Spigot/Paper 插件的默认端口仍是 `25580`(WebSocket) 和 `25581`(MCP HTTP)，配置文件为 `plugins/AgentLink/config.yml`。它只注册 `/agentlink` 管理命令，不注册 `/agent`；需要游戏内 agent 控制时必须使用对应的 Forge/NeoForge 基础 Mod + 附属 Mod。

旧版本 setup link 长这样(仅兼容保留):

```text
https://github.com/Nothingness-Void/mc-agent-link/blob/main/AGENTS.md#agent-link-setup=...
```

如果本地 endpoint 过期,运行 `/agentlink pair` 重新生成；如果已被使用,先检查现有 host 配置。若日志显示 pairing already exists; setup endpoint suppressed,说明已有持久 token，重启后不再打印链接是正常的；新 agent 必须让管理员重新运行 `/agentlink pair` 或 `/agentlink pair-guest`。

服务器和 agent 不在同一台机器时，Forge/NeoForge 同时设置 `allow_remote = true`、`mcp_public_host` 和可信的 `mcp_allowed_origins`；Spigot/Paper 设置 `mcp.allow-remote`、`mcp.public-host` 和 `mcp.allowed-origins`。详细流程见 [docs/pairing.zh-CN.md](docs/pairing.zh-CN.md)。

服务器在别的机器上的话:把 `agent-link.toml` 里的 `allow_remote` 改成 `true`,把 `mcp_allowed_origins` 收紧到信任的 client,重启,并确保防火墙放行 `mcp_listen_port`。

**用旧 host 不支持 HTTP transport?** 仓库里的 Node bridge(`packages/mcp-server`)走 stdio + WebSocket,详见 [INSTALL.md 附录 A](INSTALL.md#附录-a--node-bridgestdio兼容路径)。这是兼容路径,新安装优先用本地 setup endpoint + HTTP。

## 让 agent 知道怎么用

每个 MCP host 在 `initialize` 阶段都会拿到 mod 内置的 `instructions` 字符串(简介 + 工具分组 + 卡顿诊断闭环 + 安全规则),不需要用户在 prompt 里手写。HTTP 直连和 Node bridge 路径都会暴露同一份 instructions。

仓库还附带 4 个 Claude Code skill(`.claude/skills/` 下,会随 git 走):

| 命令 | 用途 |
|---|---|
| `/mc-health-check` | 切服务器、换配置后跳一下连通性 |
| `/mc-overview` | 一轮快照:健康度 + 玩家 + 事件 + 错误 + mods,末尾给一个建议 |
| `/mc-diagnose` | 卡顿诊断闭环:server_diagnose → 超时组件判断 → thread_dump/spark → mod 定位 → 改 config |
| `/mc-crash` | 读最新 crash-reports/*.txt,关联 mods + 错误日志,给修复建议 |

 非 Claude Code 的 agent 也能直接读 `.claude/skills/<name>/SKILL.md` 当 prompt 模板。

## 附属 mod / addon API

- **稳定入口**:`world.agentlink.api.AgentLinkApi`
- **注册工具**:`AgentLinkApi.registerTool(modId, tool)`
- **模块服务**:`requests()`、`events()`、`tasks()`、`server()`、`buildZones()`、`roles()`
- **高层附属示例**:`mc-agent-link-pixel`（服务端本地图片转像素画，异步、分片、可撤销）
- **便捷基类**:`world.agentlink.api.BaseAddonTool`
- **命名规则**:注册后会自动变成 `<modid>__<tool_name>`，避免和内置工具或别的 addon 冲突
- **发现方式**:HTTP MCP `tools/list` 会把这些 addon 工具和内置工具一起返回

详细的 Java API 示例见 [docs/api.zh-CN.md](docs/api.zh-CN.md)，英文版见 [docs/api.md](docs/api.md)。

这让 base mod 保持通用 MCP / 审批 / 请求队列能力,Claude bridge、avatar、自定义工具都能放在可选 addon 里单独发版。

## 安全边界

- **写**:`write_config_file` 受 `config/agent-link.toml` 里的 `write_allow` / `write_deny` 两条 glob 列表控制(默认 `write_allow = ["config/**"]`、`write_deny = []`)。要扩大或收紧权限,直接改这个文件然后重启服务器。`write_deny` 优先于 `write_allow`。详细规则、glob 语法、示例见 [docs/protocol.md](docs/protocol.md) 的 "Filesystem sandbox" 段。
- **写文件其他保证**:自动备份到 `config/.agent-link-backup/<encoded-path>.<时间戳>.bak`,原子重命名,4 MiB 上限。备份目录本身永远不可写。
- **读**:服务端 root 内任意文件,默认 256 KiB,硬上限 4 MiB,二进制回退 base64。
- **绝不允许**:删除文件、运行 OS shell、改 token、`..` 逃逸。
- **op 命令**:`run_console_command` 是 op level 4,内置 instructions 提示 agent 在 `stop` / `/op` / `/ban` 等命令前必须经过用户确认。
- **游戏内工具审批**:默认启用,分 4 档。
  - `approval.auto_allow_tools`：直接放行,不弹按钮
  - `approval.trusted_tools`：通过 `[始终允许该工具]` / `[始终允许 tool(arg=glob)]` 按钮维护,支持参数级 glob,如 `run_console_command(command=say *)`
  - 普通审批：默认发给所有在线 OP 和已分配的 ADMIN;OP 或 ADMIN 都能点按钮,手打 `/agentlink approve|deny|trust|trustpattern|trustlist|untrust` 也受同一套校验
  - `approval.admin_only_tools`：配置 `admin_uuids` 后只有这些 ADMIN 能点按钮;空 `admin_uuids` 时回退到所有在线 OP,保持老服可用
- **高风险工具**:`run_console_command`、`write_config_file`、`broadcast`、`spark_profiler_*` 不能被"始终允许整工具",但可以用参数级模式信任(例如只放行 `say *`)。
- 默认绑定 `127.0.0.1`;`allow_remote = true` 才会监听 `0.0.0.0`,自己判断要不要加防火墙。

## 协议要点

- 帧 = UTF-8 JSON,`v=0`,三类:`request` / `response`(按 `id` 关联,可乱序) / `event`(订阅后推送)。
- **推荐 pull 模式**:`get_recent_events` 拉一段最近事件(LLM 只为请求过的事件付 token);`subscribe_events` 推送给非 MCP 客户端用。
- 错误码:`UNAUTHENTICATED` `INVALID_TOKEN` `UNSUPPORTED_VERSION` `UNKNOWN_TOOL` `INVALID_ARGS` `APPROVAL_DENIED` `INTERNAL_ERROR` `TIMEOUT` `SPARK_UNAVAILABLE`。

## 路线图

- [x] NeoForge 1.21.1 实现
- [x] Spigot/Paper 1.20+ 基础链接插件
- [ ] Fabric 实现
- [ ] 自动应用补丁(目前 agent 只建议,人确认)
- [ ] CI + 单元测试

跟踪 / 贡献:[issues](https://github.com/Nothingness-Void/mc-agent-link/issues)。

## License

[Apache-2.0](LICENSE)

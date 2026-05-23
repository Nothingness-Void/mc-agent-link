# mc-agent-link

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

让 AI agent(通过 [Model Context Protocol](https://modelcontextprotocol.io))接管你的 Minecraft 服务器。跑命令、查玩家、读日志、看 crash 报告、profile 卡顿、调 mod 配置 —— **op 在控制台能干的事,agent 都能干**。

> **状态**:早期。先支持 Forge 1.20.1 服务端,NeoForge / Fabric / Paper 计划中。

[English README](README.en.md) · [安装指南(给 agent 用)](INSTALL.md) · [协议规范](docs/protocol.md)

## 为什么有这个项目

Claude Code、Cursor、自定义 agent 都说 MCP,但 Minecraft 服务器不说。RCON 又只能"问一句答一句",拿不到事件流、profile 数据、crash 上下文。

`mc-agent-link` 在服务端起一个 WebSocket 端点,在 agent 这一侧跑一个 MCP bridge,中间用一个简单的 JSON 协议串起来。多 agent 可以同时连进来,各干各的。

## 它能干什么

| 类别 | 工具 | 用途 |
|---|---|---|
| 操作 | `ping` `run_console_command` `list_online_players` `get_player_info` `broadcast` `get_server_stats` | 跑控制台命令、查在线玩家、广播消息 |
| 观察(pull) | `get_recent_events` `get_recent_logs` | 读最近的聊天/进出/死亡事件,以及完整服务器日志(含异常栈) |
| 诊断 | `tick_profile` `thread_dump` `list_mods` | tick 分布、JVM 线程 dump、已装 mod 列表 |
| 文件(沙盒) | `list_dir` `read_server_file` `write_config_file` | 服务端 root 下任意文件**只读**;`config/**` 才能写,且自动备份 |
| Spark 集成(选装) | `spark_status` `spark_stats` `spark_profiler_start/stop/cancel` `spark_health_report` | 装了 [spark](https://spark.lucko.me) mod 之后,agent 能跑火焰图、拿 viewer URL、读 GC 细节 |

完整的协议字段、错误码、沙盒边界看 [docs/protocol.md](docs/protocol.md)。

## 架构

```
 ┌──────────────────────┐    stdio      ┌──────────────────────┐    WebSocket    ┌─────────────────────────────┐
 │  Agent(Claude Code, │ ◄──────────►  │  mcp-server (Node)   │ ◄────────────►  │  Forge mod (Java)           │
 │  Cursor, 自定义...)   │     MCP       │  packages/mcp-server │   agent-link    │  minecraft/forge-mod        │
 └──────────────────────┘               └──────────────────────┘    协议         └─────────────────────────────┘
                                                                                  在 Minecraft 服务端 JVM 内运行
```

- **Forge mod** 跑在 Minecraft 服务端 JVM 里,起一个 WebSocket 监听。所有触碰世界状态的活儿都派发到主线程,线程安全。
- **MCP server** 是一个 Node.js 进程,一边讲 agent-link 协议,一边讲 MCP。一个 mod 可以同时服务多个 agent。
- **多 agent**:mod 同时接受 N 个 WebSocket 连接,bridge 也支持多个 MCP host。

## 仓库结构

```
mc-agent-link/
├── .claude/skills/         # Claude Code 斜杠命令:/mc-overview /mc-diagnose /mc-crash /mc-health-check
├── docs/
│   └── protocol.md         # agent-link 线协议规范
├── minecraft/
│   └── forge-mod/          # Forge 1.20.1 mod(Java 17, Gradle)
├── packages/
│   └── mcp-server/         # Node MCP bridge(TypeScript)
└── INSTALL.md              # 安装指南(给 agent 自动读取用)
```

## 快速开始

3 步:

1. **装 mod**:把 `agent-link-forge-1.20.1-0.1.0.jar` 丢进服务器 `mods/`,启动一次。
2. **拿 token**:服务器起来后看 `<server>/config/agent-link.toml` 的 `token = "..."`。控制台首次启动时也会打一行 `agent-link generated token: xxx`。
3. **配 MCP host**(以 Claude Code 为例,`~/.claude.json` 或项目 `.mcp.json`):

   ```json
   {
     "mcpServers": {
       "minecraft": {
         "command": "node",
         "args": ["/abs/path/to/mc-agent-link/packages/mcp-server/dist/index.js"],
         "env": {
           "AGENT_LINK_URL": "ws://127.0.0.1:25580",
           "AGENT_LINK_TOKEN": "<上一步的 token>"
         }
       }
     }
   }
   ```

服务器在别的机器上的话,把 `127.0.0.1` 换成对应 IP,并把 `agent-link.toml` 里的 `allow_remote` 改成 `true` 后重启。

**有 agent 帮忙安装?** 把 [INSTALL.md](INSTALL.md) 给它读,按步骤自动完成。

## 让 agent 知道怎么用

每个 MCP host 在 `initialize` 阶段都会拿到 mcp-server 内置的 `instructions` 字符串(简介 + 工具分组 + 卡顿诊断闭环 + 安全规则),不需要用户在 prompt 里手写。

仓库还附带 4 个 Claude Code skill(`.claude/skills/` 下,会随 git 走):

| 命令 | 用途 |
|---|---|
| `/mc-health-check` | 切服务器、换配置后跳一下连通性 |
| `/mc-overview` | 一轮快照:健康度 + 玩家 + 事件 + 错误 + mods,末尾给一个建议 |
| `/mc-diagnose` | 卡顿诊断闭环:tick_profile → thread_dump → 可选 spark → mod 定位 → 改 config |
| `/mc-crash` | 读最新 crash-reports/*.txt,关联 mods + 错误日志,给修复建议 |

非 Claude Code 的 agent 也能直接读 `.claude/skills/<name>/SKILL.md` 当 prompt 模板。

## 安全边界

- **写**:**只允许** `config/**`,自动备份到 `config/.agent-link-backup/<name>.<时间戳>.bak`,原子重命名。
- **读**:服务端 root 内任意文件,默认 256 KiB 上限,硬上限 4 MiB,二进制回退 base64。
- **绝不允许**:写 `mods/*.jar`、删除文件、运行 shell、改 token、写到 `..`。
- **op 命令**:`run_console_command` 是 op level 4,内置 instructions 提示 agent 在 `stop` / `/op` / `/ban` 等命令前必须经过用户确认。
- 默认绑定 `127.0.0.1`;`allow_remote = true` 才会监听 `0.0.0.0`,自己判断要不要加防火墙。

## 协议要点

- 帧 = UTF-8 JSON,`v=0`,三类:`request` / `response`(按 `id` 关联,可乱序) / `event`(订阅后推送)。
- **推荐 pull 模式**:`get_recent_events` 拉一段最近事件(LLM 只为请求过的事件付 token);`subscribe_events` 推送给非 MCP 客户端用。
- 错误码:`UNAUTHENTICATED` `INVALID_TOKEN` `UNSUPPORTED_VERSION` `UNKNOWN_TOOL` `INVALID_ARGS` `INTERNAL_ERROR` `TIMEOUT` `SPARK_UNAVAILABLE`。

## 路线图

- [ ] NeoForge 实现
- [ ] Fabric 实现
- [ ] Paper 实现
- [ ] 自动应用补丁(目前 agent 只建议,人确认)
- [ ] CI + 单元测试

跟踪 / 贡献:[issues](https://github.com/Nothingness-Void/mc-agent-link/issues)。

## License

[Apache-2.0](LICENSE)

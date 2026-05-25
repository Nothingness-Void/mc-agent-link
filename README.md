# mc-agent-link

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

让 AI agent(通过 [Model Context Protocol](https://modelcontextprotocol.io))接管你的 Minecraft 服务器。跑命令、查玩家、读日志、看 crash 报告、profile 卡顿、调 mod 配置 —— **op 在控制台能干的事,agent 都能干**。

> **状态**:早期。先支持 Forge 1.20.1 服务端,NeoForge / Fabric / Paper 计划中。

[English README](README.en.md) · [安装指南(给 agent 用)](INSTALL.md) · [协议规范](docs/protocol.md)

## 为什么有这个项目

Claude Code、Cursor、自定义 agent 都说 MCP,但 Minecraft 服务器不说。RCON 又只能"问一句答一句",拿不到事件流、profile 数据、crash 上下文。

`mc-agent-link` 在服务端模组内直接暴露 MCP HTTP 端点,并保留 WebSocket 协议给非 MCP 客户端和旧 bridge。多 agent 可以同时连进来,各干各的。

## 它能干什么

| 类别 | 工具 | 用途 |
|---|---|---|
| 操作 | `ping` `run_console_command` `list_online_players` `get_player_info` `broadcast` `get_server_stats` | 跑控制台命令、查在线玩家、广播消息 |
| 游戏内请求 API | `agent_heartbeat` `get_agent_requests` `update_agent_request_status` `reply_agent_request` | 主 mod 提供请求队列和 MCP API;游戏内 `/agent` 命令由可选附属 mod `mc-agent-link-agent` 提供 |
| 观察(pull) | `get_recent_events` `get_recent_logs` | 读最近的聊天/进出/死亡事件,以及完整服务器日志(含异常栈) |
| 诊断 | `tick_profile` `thread_dump` `list_mods` | tick 分布、JVM 线程 dump、已装 mod 列表 |
| 文件(沙盒) | `list_dir` `read_server_file` `write_config_file` | 服务端 root 下任意文件**只读**;`config/**` 才能写,且自动备份 |
| Spark 集成(选装) | `spark_status` `spark_stats` `spark_profiler_start/stop/cancel` `spark_health_report` | 装了 [spark](https://spark.lucko.me) mod 之后,agent 能跑火焰图、拿 viewer URL、读 GC 细节 |

完整的协议字段、错误码、沙盒边界看 [docs/protocol.md](docs/protocol.md)。

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
- 所有触碰世界状态的活儿都派发到主线程,线程安全。
- **多 agent**:HTTP + WebSocket 各自接受多个并发连接。

## 仓库结构

```
mc-agent-link/
├── .claude/skills/         # Claude Code 斜杠命令:/mc-overview /mc-diagnose /mc-crash /mc-health-check
├── docs/
│   └── protocol.md         # agent-link 线协议规范
├── minecraft/
│   └── forge-mod/          # Forge 1.20.1 mod(Java 17, Gradle)
├── packages/
│   └── mcp-server/         # Node MCP bridge(TypeScript,stdio fallback)
└── INSTALL.md              # 安装指南(给 agent 自动读取用)
```

## 快速开始

傻瓜式安装:

1. **装 mod**:把 `agent-link-forge-1.20.1-*.jar` 丢进服务器 `mods/`,启动服务器。
2. **复制 setup link**:控制台会打印一行 `agent-link setup link (...)`。这条链接 10 分钟内一次性有效;如果没配对成功,mod 会自动刷新并打印新链接。OP 或控制台也可以运行 `/agentlink pair` 立刻生成新链接。
3. **发给 agent**:把整条 setup link 发给 Claude Code / Cursor / 自定义 agent。agent 会用 `/pair` 换取 MCP 配置、写入 host 配置,再调用 `ping` 验证。

setup link 长这样:

```text
https://github.com/Nothingness-Void/mc-agent-link/blob/main/AGENTS.md#agent-link-setup=...
```

如果配对过期,看控制台最新的 refreshed setup link,或运行 `/agentlink pair` 手动刷新;如果已被使用,说明配对已经成功。

服务器在别的机器上的话:把 `agent-link.toml` 里的 `allow_remote` 改成 `true`,把 `mcp_allowed_origins` 收紧到信任的 client,重启,并确保防火墙放行 `mcp_listen_port`。

**用旧 host 不支持 HTTP transport?** 仓库里的 Node bridge(`packages/mcp-server`)走 stdio + WebSocket,详见 [INSTALL.md 附录 A](INSTALL.md#附录-a--node-bridgestdio兼容路径)。这是兼容路径,新安装优先用 setup link + HTTP。

## 让 agent 知道怎么用

每个 MCP host 在 `initialize` 阶段都会拿到 mod 内置的 `instructions` 字符串(简介 + 工具分组 + 卡顿诊断闭环 + 安全规则),不需要用户在 prompt 里手写。HTTP 直连和 Node bridge 路径都会暴露同一份 instructions。

仓库还附带 4 个 Claude Code skill(`.claude/skills/` 下,会随 git 走):

| 命令 | 用途 |
|---|---|
| `/mc-health-check` | 切服务器、换配置后跳一下连通性 |
| `/mc-overview` | 一轮快照:健康度 + 玩家 + 事件 + 错误 + mods,末尾给一个建议 |
| `/mc-diagnose` | 卡顿诊断闭环:tick_profile → thread_dump → 可选 spark → mod 定位 → 改 config |
| `/mc-crash` | 读最新 crash-reports/*.txt,关联 mods + 错误日志,给修复建议 |

非 Claude Code 的 agent 也能直接读 `.claude/skills/<name>/SKILL.md` 当 prompt 模板。

## 安全边界

- **写**:`write_config_file` 受 `config/agent-link.toml` 里的 `write_allow` / `write_deny` 两条 glob 列表控制(默认 `write_allow = ["config/**"]`、`write_deny = []`)。要扩大或收紧权限,直接改这个文件然后重启服务器。`write_deny` 优先于 `write_allow`。详细规则、glob 语法、示例见 [docs/protocol.md](docs/protocol.md) 的 "Filesystem sandbox" 段。
- **写文件其他保证**:自动备份到 `config/.agent-link-backup/<encoded-path>.<时间戳>.bak`,原子重命名,4 MiB 上限。备份目录本身永远不可写。
- **读**:服务端 root 内任意文件,默认 256 KiB,硬上限 4 MiB,二进制回退 base64。
- **绝不允许**:删除文件、运行 OS shell、改 token、`..` 逃逸。
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

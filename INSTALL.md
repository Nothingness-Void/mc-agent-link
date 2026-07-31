# 安装指南(给 agent 读)

> **目标读者**:任何能读 markdown 的 AI agent。把这份文档丢给 Claude Code / Cursor / 自定义 agent,它应该能照步骤把 mc-agent-link 装到用户的 Minecraft 服务器上,无需用户手动操作除了"放 jar 进 mods/ 启动一次"之外的事。

> **推荐路径是 setup link + HTTP 直连** —— mod 自己暴露 MCP HTTP endpoint,并在开服时打印一次性 setup link。agent 用链接完成配对、写 MCP host 配置。不需要 Node bridge。Bridge 路径(stdio)保留作为兼容/老配置,见文末 **附录 A**。

## 安装前提(确认这些再继续)

让 agent 检查或问用户:

1. 用户的 Minecraft 服务器是 **Forge 1.20.1**。其他版本/loader 当前不支持。
2. 用户的 agent host 支持 **MCP 客户端配置**,且支持 HTTP transport(Claude Code 1.0+、Cursor 0.42+、Zed Preview、Continue 都行)。
3. agent 能访问用户的服务器 `mods/` 目录(本地路径或 SSH/SFTP)。
4. agent 能读写用户的 MCP host 配置文件(典型路径见下文)。

任一不满足:停下来告诉用户,不要硬装。

## 两个产物

| 产物 | 位置 | 谁放 |
|---|---|---|
| Forge mod jar | `<server>/mods/agent-link-forge-1.20.1-*.jar` | 用户(或 agent 通过 SCP) |
| MCP host 配置 | 见 "Step 4" | agent |

不需要 Node、不需要 npm、不需要绝对路径。

## Step 1 — 拿到 mod jar

**推荐**:从 [GitHub Releases](https://github.com/Nothingness-Void/mc-agent-link/releases) 下载最新 `agent-link-forge-1.20.1-*.jar`,直接扔进 `<server>/mods/`。

**自己构建**(需要 JDK 17,严格 17,不是 8 也不是 21):

```bash
git clone https://github.com/Nothingness-Void/mc-agent-link.git
cd mc-agent-link/minecraft/forge-mod
./gradlew shadowJar          # Linux/macOS
.\gradlew.bat shadowJar      # Windows
```

如果 `JAVA_HOME` 不对:

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk         # Linux
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17.0.3.1" # Windows
```

产物:`minecraft/forge-mod/build/libs/agent-link-forge-1.20.1-*.jar`。拷到用户的 `<server>/mods/`。

## Step 2 — 启动服务器并拿 setup link

agent 这一步只能让用户做(除非 agent 有控制服务器进程的能力):

> 「请启动你的 Minecraft 服务器,等控制台打出 `agent-link setup link ...` 后,把整条 setup link 发给我。」

setup link 形如:

```text
https://github.com/Nothingness-Void/mc-agent-link/blob/main/AGENTS.md#agent-link-setup=eyJ2IjoxLCJtY3BfdXJsIjoi...
```

agent 收到后:

1. 从 URL fragment 里取出 `agent-link-setup=` 后面的 base64url payload。
2. base64url 解码成 JSON。
3. 读取 `pair_url`、`pair_code`、`mcp_url`、`expires_at`。
4. 在过期前向 `pair_url` 发送:

```http
POST /pair
Accept: application/json
Content-Type: application/json
Origin: http://127.0.0.1

{"pair_code":"1234-5678"}
```

成功返回:

```json
{
  "mcp": {
    "type": "http",
    "url": "http://127.0.0.1:25581/mcp",
    "headers": {
      "Authorization": "Bearer <token>"
    }
  }
}
```

这个 `mcp` 对象就是要写进 MCP host 配置里的 `mcpServers.minecraft`。

setup link **10 分钟内一次性有效**。如果 `/pair` 返回 `401`,响应体会包含 `reason`:

```json
{"error":"Invalid or expired pair code","reason":"expired"}
```

`reason` 可能是 `unknown`、`expired` 或 `used`。未配对成功前,mod 会每次过期后自动打印新链接。

兼容/故障排查时,仍可读 `<server>/config/agent-link.toml` 手动取 token:

```toml
listen_port = 25580           # WebSocket 端口(给非 MCP 客户端用)
allow_remote = false          # false = 仅 127.0.0.1
token = "abcd1234..."

mcp_enabled = true            # MCP HTTP transport
mcp_listen_port = 25581       # MCP endpoint 端口
mcp_allowed_origins = ["null", "http://localhost", "http://127.0.0.1"]

write_allow = ["config/**"]   # 写权限白名单
write_deny = []

[approval]
enabled = true
timeout_seconds = 60
table_version = 1             # 审批表迁移代数,mod 自己维护,别手改
auto_allow_tools = [
  "ping", "agent_heartbeat", "get_agent_requests", "update_agent_request_status", "reply_agent_request",
  "list_online_players", "get_player_info", "list_mods", "get_server_stats",
  "get_recent_events", "get_recent_logs", "subscribe_events", "unsubscribe_events",
  "tick_profile", "thread_dump", "spark_status", "spark_stats", "spark_health_report",
  # 0.5.0 只读新增
  "whoami", "find_blocks", "get_nbt", "list_snapshots",
  # 异步任务簿记(start_task 会对被包装的工具单独走一遍审批)
  "start_task", "get_task", "cancel_task", "list_tasks"
]
trusted_tools = []
admin_only_tools = [
  "run_console_command", "write_config_file", "broadcast",
  "spark_profiler_start", "spark_profiler_stop", "spark_profiler_cancel",
  "read_server_file", "list_dir", "get_container",
  "we_set", "we_replace", "we_sphere", "we_cyl", "we_undo",
  # 0.5.0 写入类
  "set_block", "set_blocks", "fill_blocks", "undo_blocks", "restore_block_snapshot", "set_nbt",
  "teleport", "give_item", "set_gamemode", "apply_effect",
  "spawn_entity", "remove_entities", "modify_entity",
  "set_world_property", "force_load_chunks", "save_world"
]

# 建造白名单(0.5.0)。空列表 = 每次空间写入都弹审批,和 0.4.x 一致。
# footprint 完整落在某个区域内的空间写入免审批;跨界的照旧弹窗,绝不静默裁剪。
build_zones = []

[tasks]
max_concurrent = 2            # 同时运行的异步任务数(最大 8)
blocks_per_tick = 8000        # 分片写入的每 tick 预算,越低越不影响 TPS

[events]
verbose_topics = []           # 可选:"block_place" "block_break" "item_pickup" "item_drop"

[roles]
admin_uuids = []
guest_uuids = []
```

> **升级说明**:`auto_allow_tools` / `admin_only_tools` 写在 toml 里,升级时以文件为准。所以 mod 会按 `approval.table_version` 把新版本引入的工具**增量合并**进这两张表 —— 否则新的只读工具会变成"要 OP 点一下",新的写入工具会变成"任何 OP 都能批"而不是"只有 admin 能批"。你手动删掉的条目不会被加回来。

> **安全提示**:这个 token 等同于服务端 op 权限。如果用户在公开聊天里发,告诉他重新生成(把 toml 里 token 字段清空,重启服务器,会重新生成)。

### 可选:Claude Code 权限交给游戏内审批

mc-agent-link 会在 Minecraft 聊天里弹出 MCP 工具审批按钮。为了让请求能到达服务器,Claude Code 自己的 MCP 权限弹窗需要对 `minecraft` 服务器工具放行;最终是否执行由游戏内按钮决定。

当前审批语义:

- `approval.auto_allow_tools`：直接放行,不弹按钮
- `approval.trusted_tools`：点过 `[始终允许该工具]` 后免重复审批
- `build_zones`：空间写入的 footprint 完整落在某个区域内时免审批(见下)
- 普通工具：默认所有在线 OP 都能看到按钮,也都能执行 `/agentlink approve|deny|trust <id>`
- `approval.admin_only_tools`：只有 `[roles].admin_uuids` 里的玩家能批准
- 如果 `roles.admin_uuids = []`：回退到所有在线 OP,保持老服务器/未配角色服务器可用

高风险工具仍不能被永久信任,只能逐次允许。

### 可选:用 build_zones 划出 agent 的施工区

0.5.0 之前每个空间写入都在 `admin_only_tools` 里,一次建造就是几百次点击 —— 点到这个量级,审批就不再是判断,而是机械动作。反过来把 `fill_blocks` 整个信任掉,agent 就能推平主城。

所以边界按**几何**划,而不是按调用次数:

```toml
build_zones = [
  {label = "agent plot", dim = "minecraft:overworld", min = [300, -64, 300], max = [340, 320, 340]}
]
```

规则:

- 只有当这次调用影响的**整个**区域落在某一个区域内,才免审批。跨界的照旧弹窗 —— **不会**被裁剪成"只改区域内那部分",因为做一件和用户要求不同的事比多问一次更糟。
- 覆盖 `set_block` `set_blocks` `fill_blocks` `restore_block_snapshot` `we_set` `we_replace` `we_sphere` `we_cyl` `spawn_entity`。
- **不**覆盖 `run_console_command`(命令字符串我们没解析,推断不出它会碰哪里,硬猜就是假保证)、`write_config_file`、`set_nbt`,以及任何非空间工具。
- 默认空列表。不配就是 0.4.x 的行为。
- 命中时审计日志记 `outcome: build_zone`。

告诉用户:先给一小块地,确认 agent 干活的方式没问题再放宽。

### 可选:配置 admin 名单

如果用户想把高风险审批严格限制给腐竹/管理员,填写 `[roles].admin_uuids`:

```toml
[roles]
admin_uuids = [
  "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
  "11111111-2222-3333-4444-555555555555"
]
```

填写后:

- `approval.admin_only_tools` 只会发按钮给这些 admin
- 普通审批仍然发给所有在线 OP
- 非 admin OP 即使手打 `/agentlink approve <id>` 也会被拒绝

### 可选:调整写权限

默认 agent 只能写 `config/**`。如果用户想让 agent 改更多文件(比如 `data/whitelist/`、`world/datapacks/`),让用户改 `agent-link.toml` 里的 `write_allow`,然后重启服务器:

```toml
write_allow = ["config/**", "data/whitelist/*.json"]
write_deny  = ["config/security/**"]   # 即使在 allow 范围内,这里也会被拒绝
```

`write_deny` 优先于 `write_allow`,常用来在大范围放权后挖洞排除敏感路径。空数组 `write_allow = []` 表示完全只读。Glob 语法:`**` 匹配任意层级,`*` 匹配单层任意字符,`?` 匹配单字符。

## Step 3 — 写 MCP host 配置

根据用户用什么 agent host,写到对应文件。**先读现有文件,合并,不要覆盖。**

### Claude Code(项目级,推荐)

文件:`<项目>/.mcp.json`

```json
{
  "mcpServers": {
    "minecraft": {
      "type": "http",
      "url": "http://127.0.0.1:25581/mcp",
      "headers": {
        "Authorization": "Bearer <pair 返回的 token>"
      }
    }
  }
}
```

实际写入时使用 `/pair` 返回的 `mcp` 对象,不要重新手打 token。

如果用户的服务器在另一台机器上:把 `127.0.0.1` 换成那台机器的 IP,**并且**让用户在 `<server>/config/agent-link.toml` 里:

1. 把 `allow_remote` 改成 `true`(这同时影响 WebSocket 和 MCP HTTP 的 bind 地址)
2. 把 `mcp_allowed_origins` 收紧到你信任的 client(防 DNS rebinding)
3. 重启服务器

提醒用户加防火墙规则。

### Claude Code(用户级)

文件:`~/.claude.json`(macOS/Linux)或 `%USERPROFILE%\.claude.json`(Windows)。结构同上。

### Cursor

文件:`~/.cursor/mcp.json`(全局)或 `<项目>/.cursor/mcp.json`(项目)。字段同 Claude Code。

### 其他 host

任何支持 MCP Streamable HTTP transport 的 host 都能用,需要的字段是:

- endpoint:`http://<host>:<mcp_listen_port>/mcp`
- header:`Authorization: Bearer <token>`
- header:`Accept: application/json`(client 一般默认带)

## Step 4 — 验证

agent 让用户在 host 里跑一句:**「ping the minecraft server」**。

预期行为:agent 调用 `ping` 工具,返回 `{"pong": true, "uptime_ms": ...}`。

不行的话排查顺序:

1. **`401 Unauthorized`** → token 复制错了,或者 host 配置 header 没生效。重做 Step 2 / Step 3。
2. **`403 Forbidden, Origin not allowed`** → host 发的 Origin 头不在 allowlist。让用户把对应 origin 加进 `mcp_allowed_origins` 重启。
3. **`ECONNREFUSED <host>:25581`** → mod 没起来,或者 `mcp_enabled = false`,或者跨机时没改 `allow_remote = true`。
4. **mod 起了但 :25581 不通** → 看服务器日志有没有 `MCP HTTP failed to start`(端口被占等)。
5. **跨机时 :25580 通但 :25581 不通** → 防火墙单独拦了 25581。

## 装 spark(可选,强烈推荐)

[spark](https://spark.lucko.me/download) 是独立 mod。装上之后 mc-agent-link 会自动启用 6 个 `spark_*` 工具(火焰图、heap、health 报告)。**不装也能用**,只是诊断深度浅些。

agent 让用户从 [spark.lucko.me/download](https://spark.lucko.me/download) 下载 Forge 1.20.1 版,扔进 `mods/`,重启服务器。无需额外配置。

## 完成检查清单

agent 给用户一个汇总:

- [ ] mod jar 已在 `<server>/mods/`
- [ ] 服务器已启动过一次,`config/agent-link.toml` 已生成
- [ ] MCP host 配置已写,url 和 Bearer token 正确
- [ ] `ping` 工具调用成功
- [ ] (可选)spark mod 已装,`spark_status` 返回 `installed: true`

完事告诉用户:试一下 `/mc-overview` 或 `/mc-health-check`(如果是 Claude Code)。其他 host 直接问 agent「服务器现在怎么样」即可。

## 卸载

1. 从 `<server>/mods/` 删 `agent-link-forge-*.jar`,重启服务器。
2. 从 MCP host 配置里删 `minecraft` server 块。
3. 想保留 token 以后再装就留着 `config/agent-link.toml`,否则也可以删。

## 故障排查

### mod 启动后 Minecraft 直接 crash

读 `<server>/crash-reports/` 里最新那份。常见原因:Forge 版本不匹配(必须 1.20.1)、Java 版本不对(必须 17)、端口被别的进程占了(改 `agent-link.toml` 里的 `listen_port` 或 `mcp_listen_port`)。

### 编译 mod 失败:`Could not find me.lucko:spark-api`

Lucko 的 maven 仓库网络问题。重试 `./gradlew shadowJar`,或者临时把 `compileOnly 'me.lucko:spark-api:0.1-SNAPSHOT'` 这一行从 `build.gradle` 注释掉(spark 集成是运行时反射加载的,编译期注释掉只会让 IDE 红,不影响运行)。

### MCP host 看不到工具

用户多半是改了配置但没重启 host。Claude Code:`/mcp` 看连接状态;不行就退出重开。Cursor:Settings → MCP 那一栏点重连。

### 工具能调但都返回 `INTERNAL_ERROR`

mod 自身崩了。让用户调 `read_server_file { path: "logs/latest.log" }`(或者直接看那份日志)找 `agent-link tool ... crashed` 的栈。把栈贴给开发者就行。

---

## 附录 A — Node bridge(stdio,兼容路径)

旧 host 不支持 HTTP transport 时使用。需要 Node.js ≥ 20。

构建:

```bash
cd packages/mcp-server
npm install
npm run build
```

host 配置:

```json
{
  "mcpServers": {
    "minecraft": {
      "command": "node",
      "args": ["<dist/index.js 绝对路径>"],
      "env": {
        "AGENT_LINK_URL": "ws://127.0.0.1:25580",
        "AGENT_LINK_TOKEN": "<token>"
      }
    }
  }
}
```

bridge 通过 WebSocket 连 :25580,功能与 HTTP 直连等价。两条路径可以同时启用,各自独立。

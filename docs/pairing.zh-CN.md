# Agent Link 配对说明

这是 Forge 1.20.1、NeoForge 1.21.1、Spigot/Paper 1.20+ 的权威配对文档。三套实现使用相同的
HTTP/MCP 配对协议，只有配置项名称不同。

## 配对做什么

配对不会把永久服务器 token 放到 GitHub 页面。服务器生成一个短期、一次性的 pair code，并通过
本地 HTTP setup endpoint 提供给 agent。agent 完成以下流程:

1. GET setup endpoint。
2. 把返回的 pair code POST 回服务器。
3. 接收包含新 bearer token 的 MCP HTTP 配置块。
4. 把配置块合并进 MCP host 配置。
5. 重载 host，先调用 whoami，再调用 ping。

成功配对后，服务器只把 bearer token 的 hash 保存到持久化 registry。明文 token 只在成功的
/pair 响应中返回，不能从 registry 恢复。

## 首次配对

### 1. 找到 setup endpoint

全新的、尚未配对的服务器启动后，控制台会打印类似这一行:

    agent-link local setup endpoint (one use, expires at 2026-08-09T07:38:17Z): http://127.0.0.1:25581/pair/setup/<random-id>

复制完整的 HTTP URL。不要删除随机路径，不要缩短 URL，不要打开 GitHub 仓库，也不要向用户索要
agent-link.toml。

默认的 127.0.0.1 表示 agent 和 Minecraft 服务器在同一台电脑。远程 agent 必须在启动服务端前
配置可访问的 public host，见“远程配对”。

### 2. GET endpoint

这是服务端直接提供的 JSON API，不需要 GitHub，也不需要浏览器。

PowerShell:

    $setup = Invoke-RestMethod -Method Get -Uri '<setup-endpoint-url>' -Headers @{ Accept = 'application/json' }
    if ($setup.kind -ne 'agent-link-pairing') {
        throw "setup endpoint 不是有效的配对描述: $($setup.reason)"
    }

curl:

    curl --fail-with-body -H 'Accept: application/json' '<setup-endpoint-url>'

有效响应包含:

    {
      "kind": "agent-link-pairing",
      "version": 2,
      "mcp_url": "http://127.0.0.1:25581/mcp",
      "pair_url": "http://127.0.0.1:25581/pair",
      "setup_url": "http://127.0.0.1:25581/pair/setup/<random-id>",
      "pair_code": "1234-5678",
      "expires_at": 1779640000000,
      "expires_at_iso": "2026-05-25T00:00:00Z",
      "allow_remote": false,
      "token_tier": "console",
      "pair_request": {
        "method": "POST",
        "url": "http://127.0.0.1:25581/pair",
        "body": { "pair_code": "1234-5678" }
      }
    }

必须使用服务端返回的值。不要根据示例自行拼 URL，也不要把 mcp_url 错当成 setup URL。

### 3. POST pair code

PowerShell:

    $pairBody = @{ pair_code = [string]$setup.pair_code } | ConvertTo-Json -Compress
    $pair = Invoke-RestMethod -Method Post -Uri $setup.pair_url -Headers @{ Accept = 'application/json' } -ContentType 'application/json' -Body $pairBody
    $minecraftServer = $pair.mcp
    if ($null -eq $minecraftServer -or $minecraftServer.type -ne 'http') {
        throw '配对响应没有 MCP HTTP server 配置块'
    }

对应的 HTTP 请求:

    POST <pair_url>
    Accept: application/json
    Content-Type: application/json

    {"pair_code":"1234-5678"}

成功响应中的 mcp 对象就是要安装到 host 的配置:

    {
      "mcp": {
        "type": "http",
        "url": "http://127.0.0.1:25581/mcp",
        "headers": {
          "Authorization": "Bearer <new-token>"
        }
      }
    }

Authorization 值是密钥。不要在最终回复、聊天、提交记录或 Minecraft 日志中打印它。

### 4. 合并 MCP host 配置

先读取 host 原有配置。没有 mcpServers 就创建，然后只设置
mcpServers.minecraft = 配对响应中的 mcp。保留所有其他 MCP server 和顶层字段，最好原子写入。

常见位置:

| Host | 配置位置 |
|---|---|
| Claude Code 项目级 | <project>/.mcp.json |
| Claude Code 用户级 | Windows %USERPROFILE%/.claude.json；macOS/Linux ~/.claude.json |
| Cursor 项目级 | <project>/.cursor/mcp.json |
| Cursor 用户级 | ~/.cursor/mcp.json |

不要把 setup endpoint 写进 MCP host。setup endpoint 只负责一次配对，MCP endpoint 必须使用返回的
mcp.url。

### 5. 重载并验证

如果 host 不会自动监听配置变化，就重载或重启 host。然后按顺序执行:

1. 通过 host 执行 initialize。
2. 任何世界或服务器操作之前先执行 whoami，确认 token 等级、审批规则、在线审批人、文件写入边界和建造区域。
3. 执行 ping，确认返回 pong: true。
4. 如果 host 看不到服务器，先检查 MCP 连接状态，不要先放宽 Minecraft 权限。

## 配对状态与重启

配对有两种互相独立的状态:

- setup endpoint 和 pair code 是临时的一次性凭据，有效期 10 分钟。
- 成功配对生成的 bearer token 是持久凭据。

| 状态 | 控制台行为 | agent 动作 |
|---|---|---|
| 没有已颁发 token | 打印本地 endpoint；10 分钟后仍未配对会自动刷新 | GET 最新 endpoint 并配对 |
| 配对成功 | 消费 endpoint，保存 token | 保存返回的 mcp 配置 |
| 配对后重启 | 抑制 endpoint，不在后台刷新 | 继续使用已有 host 配置 |
| 需要第二个 agent | 不会自动重新开放配对 | 管理员执行 /agentlink pair 或 /agentlink pair-guest |
| host 配置丢失 | 服务器不能恢复明文 token | 管理员重新执行 /agentlink pair |
| token 被撤销 | 原 host 配置返回 401 | 重新 /agentlink pair，只替换 Minecraft 配置块 |

配对后重启没有新的 endpoint 是正常现象，不是故障。不要每次重启都执行 /agentlink pair。

如果日志显示“pairing already exists; setup endpoint suppressed”，新 agent 不能从 /mcp 恢复凭据。
管理员必须显式执行 /agentlink pair 或 /agentlink pair-guest，并把新打印的完整 endpoint 交给 agent。

## 远程配对

setup endpoint、/pair、/mcp 必须都能从 agent 机器访问。URL 中的 127.0.0.1 或 localhost 指向 agent
自己的电脑，不是 Minecraft 服务器。

### Forge 和 NeoForge

启动前编辑 <server>/config/agent-link.toml:

    allow_remote = true
    mcp_public_host = "mc.example.com"
    mcp_allowed_origins = ["null"]

mcp_public_host 是写入 setup endpoint 和返回 MCP URL 的主机名/IP。不要包含 http:// 或末尾 /。
只向可信网络开放 MCP 端口，默认是 25581；allow_remote 也会影响旧 WebSocket 的 25580。

### Spigot/Paper

编辑 plugins/AgentLink/config.yml:

    mcp:
      allow-remote: true
      public-host: mc.example.com
      allowed-origins:
        - "null"

只向可信网络开放配置的 MCP 端口。若允许浏览器客户端，应收紧 mcp.allowed-origins；原生 MCP 客户端
通常不发送 Origin，对应 allowlist 中的字面值是 "null"。

修改远程设置后重启服务端，并使用新打印的 endpoint。不要在配对后手工改 mcp.url；应修正服务器
public host 配置后重新配对。

## 失败恢复

### GET setup endpoint

| 结果 | 含义 | 动作 |
|---|---|---|
| 200 且 kind=agent-link-pairing | 有效描述 | 立即 POST pair_code |
| 404 且 reason=unknown | URL 错误、截断或过期随机路径 | 要求最新 endpoint，不要猜 code |
| 410 且 reason=expired | 超过 10 分钟 | 管理员执行 /agentlink pair 或 /agentlink pair-guest |
| 410 且 reason=used | 已被其他 host 消费 | 检查现有 host 配置，否则重新配对 |
| 410 且 reason=pairing_inactive | 服务器已有持久 token，自动配对已关闭 | 使用已有配置，或管理员显式执行 /agentlink pair |
| 403 | Origin 不在白名单 | 使用不带 Origin 的原生请求，或加入准确的可信 origin |
| 连接拒绝 | HTTP 未启动、端口错误、绑定失败或防火墙阻断 | 查日志、mcp_enabled、端口、绑定配置和防火墙 |

### POST /pair

接口只接受 JSON POST。错误响应是 HTTP 401:

    {"error":"Invalid or expired pair code","reason":"unknown|expired|used"}

- unknown：code 不属于当前 endpoint、endpoint 已关闭，或服务器已经重启。用 /agentlink pair 获取新 endpoint。
- expired：超过 10 分钟。获取新 endpoint。
- used：配对已经成功。先检查 host 配置；如果配置没有保存，再获取新 endpoint。

不要手工复制 config/agent-link/issued_tokens.json，里面只有 hash，不能作为 bearer token 使用。

## 旧版 GitHub fragment 链接

旧版 mod/plugin 可能打印:

    https://github.com/Nothingness-Void/mc-agent-link/blob/main/AGENTS.md#agent-link-setup=<base64url-json>

这只是 JSON 描述的传输方式。agent 可以在本地解码 fragment，不打开 GitHub，读取 mcp_url、pair_url、
pair_code、expires_at，然后使用同样的 POST /pair 流程。新版本改为本地 endpoint，不依赖 GitHub。

## 安全清单

- setup URL 在失效前视为一次性密钥。
- bearer token 是持久服务器凭据。
- 不要把任一凭据写入聊天、源码、截图或公开 issue。
- 只合并 mcpServers.minecraft，不要覆盖其他 MCP server。
- 先 whoami，再执行任何写入或控制台操作。
- 配对和排障期间不要执行破坏性 Minecraft 命令。

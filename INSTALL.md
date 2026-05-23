# 安装指南(给 agent 读)

> **目标读者**:任何能读 markdown 的 AI agent。把这份文档丢给 Claude Code / Cursor / 自定义 agent,它应该能照步骤把 mc-agent-link 装到用户的 Minecraft 服务器上,无需用户手动操作除了"放 jar 进 mods/ 启动一次"之外的事。

## 安装前提(确认这些再继续)

让 agent 检查或问用户:

1. 用户的 Minecraft 服务器是 **Forge 1.20.1**。其他版本/loader 当前不支持。
2. 用户机器上有 **Node.js ≥ 20**(`node --version` 验证)。
3. 用户的 agent host 支持 **MCP 客户端配置**(Claude Code / Cursor / Zed / Continue 等都行)。
4. agent 能访问用户的服务器 `mods/` 目录(本地路径或 SSH/SFTP)。
5. agent 能读写用户的 MCP host 配置文件(典型路径见下文)。

任一不满足:停下来告诉用户,不要硬装。

## 三个产物

安装一共三件东西:

| 产物 | 位置 | 谁放 |
|---|---|---|
| Forge mod jar | `<server>/mods/agent-link-forge-1.20.1-0.1.0.jar` | 用户(或 agent 通过 SCP) |
| MCP bridge | `mc-agent-link/packages/mcp-server/dist/` 编译产物 | agent |
| MCP host 配置 | 见 "Step 4" | agent |

## Step 1 — 拿到仓库

如果用户已经 clone 了:

```bash
cd <用户提供的路径>/mc-agent-link
git pull
```

如果没有:

```bash
git clone https://github.com/Nothingness-Void/mc-agent-link.git
cd mc-agent-link
```

## Step 2 — 构建 mod jar

```bash
cd minecraft/forge-mod
# Linux/macOS:
./gradlew shadowJar
# Windows PowerShell:
.\gradlew.bat shadowJar
```

需要 JDK 17(不是 8、不是 21,严格 17)。如果 `JAVA_HOME` 不对,设置后再跑:

```bash
# 例:
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
# Windows PowerShell:
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17.0.3.1"
```

构建产物:`minecraft/forge-mod/build/libs/agent-link-forge-1.20.1-0.1.0.jar`。

把这个 jar 拷到用户的 `<server>/mods/`。

## Step 3 — 构建 MCP bridge

```bash
cd packages/mcp-server
npm install
npm run build
```

产物:`packages/mcp-server/dist/index.js`。**记住这个绝对路径**,Step 4 要用。

## Step 4 — 让用户启动一次服务器并拿 token

agent 这一步只能让用户做(除非 agent 有控制服务器进程的能力):

> 「请启动你的 Minecraft 服务器一次,等控制台打出 `agent-link listening on ...` 后再停止。这一步是为了让 mod 生成 token。」

服务器停掉后,agent 读 `<server>/config/agent-link.toml`,提取 `token = "..."` 字段。

```toml
listen_port = 25580
allow_remote = false
token = "abcd1234..."   # 拿这个值

# 写权限白名单/黑名单。glob 语法,默认仅允许 config/。
write_allow = ["config/**"]
write_deny = []
```

如果用户的服务器在远端机器,agent 让用户把 token 发过来,不要让用户暴露其他字段。

> **安全提示**:这个 token 等同于服务端 op 权限。如果用户在公开聊天里发,告诉他重新生成(把 toml 里 token 字段清空,重启服务器,会重新生成)。

### 可选:调整写权限

默认 agent 只能写 `config/**`。如果用户想让 agent 改更多文件(比如 `data/whitelist/`、`world/datapacks/`),让用户改 `agent-link.toml` 里的 `write_allow`,然后重启服务器:

```toml
write_allow = ["config/**", "data/whitelist/*.json"]
write_deny  = ["config/security/**"]   # 即使在 allow 范围内,这里也会被拒绝
```

`write_deny` 优先于 `write_allow`,常用来在大范围放权后挖洞排除敏感路径。空数组 `write_allow = []` 表示完全只读。Glob 语法:`**` 匹配任意层级,`*` 匹配单层任意字符,`?` 匹配单字符。

## Step 5 — 写 MCP host 配置

根据用户用什么 agent host,写到对应文件。**先读现有文件,合并,不要覆盖。**

### Claude Code(项目级,推荐)

文件:`<项目>/.mcp.json`

```json
{
  "mcpServers": {
    "minecraft": {
      "command": "node",
      "args": ["<Step 3 拿到的 dist/index.js 绝对路径>"],
      "env": {
        "AGENT_LINK_URL": "ws://127.0.0.1:25580",
        "AGENT_LINK_TOKEN": "<Step 4 拿到的 token>"
      }
    }
  }
}
```

如果用户的服务器在另一台机器上:把 `127.0.0.1` 换成那台机器的 IP,**并且**让用户在 `<server>/config/agent-link.toml` 里把 `allow_remote` 改成 `true`,重启服务器。提醒用户加防火墙规则。

### Claude Code(用户级)

文件:`~/.claude.json`(macOS/Linux)或 `%USERPROFILE%\.claude.json`(Windows)。结构同上。

### Cursor

文件:`~/.cursor/mcp.json`(全局)或 `<项目>/.cursor/mcp.json`(项目)。结构同 Claude Code。

### Zed

`~/.config/zed/settings.json`,在 `context_servers` 块下添加,字段名与 Claude Code 相同。

### 其他 host

参考用户 host 的文档,字段都是标准的 MCP stdio server 配置:`command` + `args` + `env`。

## Step 6 — 验证

agent 让用户在 host 里跑一句:**「ping the minecraft server」**。

预期行为:agent 调用 `ping` 工具,返回 `{"pong": true, "uptime_ms": ...}`。

不行的话排查顺序:

1. **`AGENT_LINK_TOKEN is required`** → host 配置里 env 没生效;查路径和重启 host。
2. **`ECONNREFUSED 127.0.0.1:25580`** → mod 没起来或 Minecraft 服务器没启动。让用户启动服务器并看日志里有没有 `agent-link listening on 127.0.0.1:25580`。
3. **`INVALID_TOKEN`** → token 复制错了,或者用户清空了重新生成。重做 Step 4。
4. **socket closed (4401)** → 同上,token 错了。
5. **跨机时 `ECONNREFUSED` 但本机能连** → `allow_remote` 没改成 true,或者防火墙拦了 25580。

## 装 spark(可选,强烈推荐)

[spark](https://spark.lucko.me/download) 是独立 mod。装上之后 mc-agent-link 会自动启用 6 个 `spark_*` 工具(火焰图、heap、health 报告)。**不装也能用**,只是诊断深度浅些。

agent 让用户从 [spark.lucko.me/download](https://spark.lucko.me/download) 下载 Forge 1.20.1 版,扔进 `mods/`,重启服务器。无需额外配置。

## 完成检查清单

agent 给用户一个汇总:

- [ ] mod jar 已在 `<server>/mods/`
- [ ] 服务器已启动过一次,`config/agent-link.toml` 已生成
- [ ] MCP host 配置已写,`AGENT_LINK_URL` 和 `AGENT_LINK_TOKEN` 正确
- [ ] `ping` 工具调用成功
- [ ] (可选)spark mod 已装,`spark_status` 返回 `installed: true`

完事告诉用户:试一下 `/mc-overview` 或 `/mc-health-check`(如果是 Claude Code)。其他 host 直接问 agent「服务器现在怎么样」即可。

## 卸载

1. 从 `<server>/mods/` 删 `agent-link-forge-*.jar`,重启服务器。
2. 从 MCP host 配置里删 `minecraft` server 块。
3. 想保留 token 以后再装就留着 `config/agent-link.toml`,否则也可以删。

## 故障排查

### mod 启动后 Minecraft 直接 crash

读 `<server>/crash-reports/` 里最新那份。常见原因:Forge 版本不匹配(必须 1.20.1)、Java 版本不对(必须 17)、端口 25580 被别的进程占了(改 `agent-link.toml` 里的 `listen_port`)。

### 编译 mod 失败:`Could not find me.lucko:spark-api`

Lucko 的 maven 仓库网络问题。重试 `./gradlew shadowJar`,或者临时把 `compileOnly 'me.lucko:spark-api:0.1-SNAPSHOT'` 这一行从 `build.gradle` 注释掉(spark 集成是运行时反射加载的,编译期注释掉只会让 IDE 红,不影响运行;**注释掉之后** `SparkBridge.java` 里用到 spark API 类型的方法签名也要打开看看,通常 `Class.forName` 调用不会受影响)。

### `npm install` 卡住

国内网络,换镜像:

```bash
npm config set registry https://registry.npmmirror.com
npm install
```

装完可以改回:`npm config set registry https://registry.npmjs.org`。

### MCP host 看不到工具

用户多半是改了配置但没重启 host。Claude Code:`/mcp` 看连接状态;不行就退出重开。Cursor:Settings → MCP 那一栏点重连。

### 工具能调但都返回 `INTERNAL_ERROR`

mod 自身崩了。让用户调 `read_server_file { path: "logs/latest.log" }`(或者直接看那份日志)找 `agent-link tool ... crashed` 的栈。把栈贴给开发者就行。

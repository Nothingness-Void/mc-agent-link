# 服务器退出现场保全

`agent-link-watchdog.ps1` 用于处理 JVM 已经退出后的情况。模组运行在服务器 JVM 内，JVM 崩溃后无法继续响应 MCP，所以 watchdog 在进程退出后保存：

- `logs/latest.log`
- `logs/debug.log`（如果存在）
- 最新的 `crash-reports/*.txt`
- `hs_err_pid*.log`、`replay_pid*.log`、`java_error_in_*.log`（如果存在）
- `incident-ledger.json`（如果模组已经写入）
- `incident.json`（进程启动信息、PID 复用判断、时间和 artifact 记录）

它只监视和复制文件，不会自动重启服务器，也不会执行 Minecraft 命令。

## 用法

先启动服务器并找到 Java 进程 PID，然后在另一个 PowerShell 窗口运行：

```powershell
.\tools\agent-link-watchdog.ps1 `
  -ProcessId 12345 `
  -ServerDirectory 'C:\Minecraft\server'
```

默认输出到服务端的 `diagnostics/incident-时间戳/`。服务器退出后，把该目录交给 agent 分析。`incident.json` 会记录大于复制上限的 heap dump 元数据，但不会复制数 GB 的 `.hprof`/`.dmp` 文件；watchdog 不会重启服务器或执行命令。

如果只想调整检测间隔：

```powershell
.\tools\agent-link-watchdog.ps1 -ProcessId 12345 -ServerDirectory . -PollMilliseconds 250
```

发布前可以运行离线契约检查，确认 Java 工具表、Node bridge、诊断超时字段和 watchdog 语法没有漂移：

```powershell
.\tools\validate-contract.ps1 -CheckBuiltArtifacts
```

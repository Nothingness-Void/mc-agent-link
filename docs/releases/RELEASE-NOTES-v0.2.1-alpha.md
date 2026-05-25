# mc-agent-link v0.2.1-alpha

## Highlights

### 游戏内 MCP 审批分层（Direction A）

MCP 工具调用现在按 4 档处理：

| Tier | 行为 | 默认成员 |
|---|---|---|
| 1 · auto-allow | 直接放行，不弹按钮 | `ping`, `agent_heartbeat`, `get_agent_requests`, `update_agent_request_status`, `reply_agent_request`, `list_online_players`, `get_player_info`, `list_mods`, `get_server_stats`, `get_recent_events`, `get_recent_logs`, `subscribe_events`, `unsubscribe_events`, `tick_profile`, `thread_dump`, `spark_status`, `spark_stats`, `spark_health_report` |
| 2 · trusted | 通过 `[始终允许该工具]` 记住，下次直接放行 | 服主自己在游戏内加的 |
| 3 · 普通审批 | 发给所有在线 OP；手打 `/agentlink approve|deny|trust` 也走同一套权限校验 | 不在 1/2/4 档的工具 |
| 4 · admin-only | 发给 `[roles].admin_uuids` 里的玩家；这些玩家以外的人不能手动批准 | `run_console_command`, `write_config_file`, `broadcast`, `spark_profiler_start/stop/cancel`, `read_server_file`, `list_dir` |

### `admin_uuids` 为空时的回退行为

如果 `[roles].admin_uuids = []`，就视为“没启用角色分层”：

- 普通审批 → 所有在线 OP 可批
- `admin_only_tools` → 也回退到所有在线 OP 可批

这样老服务器、单人测试服、没配 UUID 名单的服务器仍然能正常看到审批按钮，不会出现“工具执行了但游戏里没有任何可批准入口”的死局。

### 稳定 addon API

base mod 现在提供稳定 Java API 给可选附属 mod：

- `world.agentlink.api.AgentLinkApi`
- `world.agentlink.api.BaseAddonTool`

附属工具通过 `AgentLinkApi.registerTool(modId, tool)` 注册后会自动加上 `<modid>__` 前缀，并出现在 HTTP MCP `tools/list` 里。

## 配置变化

`config/agent-link.toml` 当前支持：

```toml
[approval]
enabled = true
timeout_seconds = 60
auto_allow_tools = [ ... ]
trusted_tools = []
admin_only_tools = [
  "run_console_command", "write_config_file", "broadcast",
  "spark_profiler_start", "spark_profiler_stop", "spark_profiler_cancel",
  "read_server_file", "list_dir"
]

[roles]
admin_uuids = []
guest_uuids = []
```

## Compatibility

- 旧服务器的 `agent-link.toml` 会保留旧值；如果你想拿到新的默认审批列表，最简单的方法是删除旧的 `approval.*` 条目后重启，让模组重写。
- HTTP MCP `tools/list` 现在会动态包含 addon 工具；旧的 Node stdio bridge 仍是兼容路径。

## Notes

- 高风险工具仍不能被“始终允许”，只能逐次批准。
- `APPROVAL_DENIED` 现在也覆盖“没有符合条件的 approver 在线”的场景，而不只是简单超时/拒绝。
- `mc-agent-link-agent` 0.3.1-alpha 跟随升级，`base_agentlink_version_range = [0.2.1-alpha,)`。

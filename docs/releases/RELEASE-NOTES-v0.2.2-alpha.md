# mc-agent-link v0.2.2-alpha

## Highlights

### 参数级信任（来自 Claude Code 的 `Bash(npm test:*)` 范式）

`/agent` 调 `run_console_command` 现在不再每次都弹按钮。审批提示新增第 3 个按钮：

```
[允许一次] [拒绝] [始终允许该工具] [始终允许 run_console_command(command=say *)] [复制详情]
```

第 4 个按钮把当前调用的命令模式写进 `approval.trusted_tools`，例如 `run_console_command(command=say *)`。下次任何 `say` 命令直接放行；`/op` `/stop` 仍正常审批。

匹配规则：
- 单 token 的命令（如 `list`）→ 精准匹配 `command=list`
- 多 token 命令（如 `say hello`）→ 前缀通配 `command=say *`
- glob 语法：`*` = 任意字符，`?` = 一个字符

支持参数级信任的工具：`run_console_command(command=...)`、`broadcast(message=...)`、`write_config_file(path=...)`。

### 新命令

```
/agentlink trustlist            # 列出所有信任规则
/agentlink trustpattern <id>    # 等价于点 [始终允许 ...] 按钮
/agentlink untrust <规则>       # 移除一条信任规则，例: /agentlink untrust run_console_command(command=say *)
```

之前的 `/agentlink trust <id>`、`approve`、`deny`、`approvals` 全部保留，行为不变。

### 配置格式扩展（向后兼容）

`approval.trusted_tools` 现在接受两种字符串：

```toml
[approval]
trusted_tools = [
  "tick_profile",                              # 旧格式：整工具放行
  "run_console_command(command=say *)",        # 新格式：参数级
  "run_console_command(command=tellraw *)",
  "broadcast(message=*)",                      # 等价于全工具放行
]
```

旧格式继续读，加进新规则不会破坏旧字段。

### 安全约束保留

- `NEVER_TRUST_TOOLS` 列表（`run_console_command` / `broadcast` / `write_config_file` / `spark_profiler_*`）的**整工具**信任仍然不允许，旧的 `[始终允许该工具]` 按钮在这些工具上仍隐藏。但**参数级**信任可以——这就是设计意图：让服主可以放行"安全的子集"而不是"整个危险工具"。
- 即使 toml 被手改塞进 `run_console_command` 这种整工具白名单，匹配阶段也会跳过，安全护栏不会被静态字符串绕开。
- `admin_only_tools` / `[roles].admin_uuids` / 普通审批的 OP 限定都不变。

## 兼容性

- 旧 `agent-link.toml` 不会被覆盖。新的 `[approval].trusted_tools` 注释如果想看，删那一行重启即可重写。
- 0.2.1-alpha 已经写过的整工具信任继续生效，无须迁移。
- 配套 addon `mc-agent-link-agent` 仍是 0.3.2-alpha，不需要换。新功能在主 mod，附属 mod 透明。

## Notes

- 参数级匹配大小写敏感（命令值经常含玩家名 / 路径）。如果你想忽略大小写，把 glob 写宽即可（`say *` 已经覆盖 `Say *` 因为 `say` 后面接的内容才是 wildcard 部分）。
- `untrust` 必须给完整的规则字符串，例如 `/agentlink untrust run_console_command(command=say *)`。`/agentlink trustlist` 列出的就是可以原样复制的规则。

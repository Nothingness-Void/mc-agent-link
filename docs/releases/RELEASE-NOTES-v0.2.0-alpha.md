# mc-agent-link v0.2.0-alpha

## Highlights

### Lib-ification (headline change)

The base mod now ships a stable public API at `world.agentlink.api`. Addon mods (e.g. `mc-agent-link-agent`) can build against it without reaching into internal classes.

- `AgentLinkApi.registerTool(modId, tool)` — register an MCP tool from an addon. Auto-prefixed with `<modId>__` to avoid clashes with built-in tools and other addons.
- `AgentLinkApi.isAdmin(uuid)` / `isGuest(uuid)` / `adminUuids()` / `guestUuids()` — single source of truth for "腐竹/管理员" identity, used by all addons.
- `AgentLinkApi.config()` — read-only view of token, MCP endpoint, and approval settings.
- `BaseAddonTool` — convenience base class that bundles name + description + JSON Schema.

The `dispatch.RequestDispatcher`, `transport.mcp.*`, and `config.AgentLinkConfig` packages are now considered internal. Use `AgentLinkApi` instead.

### Roles config

`config/agent-link.toml` now has a `[roles]` block:

```toml
[roles]
admin_uuids = []   # 腐竹/管理员 UUID 列表
guest_uuids = []   # 显式 guest, 留空时所有非 admin 都视为 guest
```

When `admin_uuids` is non-empty, in-game tool approval prompts go ONLY to admins (not all online OPs). Empty list = legacy behavior (all OPs see prompts).

### MCP `tools/list` advertises addon tools

When an addon registers a tool through `AgentLinkApi.registerTool`, MCP `tools/list` automatically appends its name + description + `inputSchema`. The MCP host (Claude Code, Cursor) sees them alongside built-in tools.

### Tool approval (already in this version, kept)

- Non-auto-allowed MCP tool calls ask in-game admins in chat.
- Clickable actions: `[允许一次]` `[拒绝]` `[始终允许该工具]` `[复制详情]`.
- `/agentlink approve <id>` / `deny <id>` / `trust <id>` / `approvals`.
- Persistent config under `[approval]` in `config/agent-link.toml`.

## Safety

- Approval is enabled by default.
- High-impact tools (`run_console_command`, `write_config_file`, `broadcast`, spark profiler) cannot be permanently trusted and require per-call approval.
- Denied, timed-out, or unavailable approvals return `APPROVAL_DENIED`.

## Migration notes

- **For server admins**: existing configs continue to work. To move to role-based approval, fill `[roles].admin_uuids` with trusted player UUIDs and restart the server. Empty list = legacy behavior (all OPs).
- **For addon authors**: switch to `AgentLinkApi` (set `mods.toml` `versionRange = "[0.2.0-alpha,)"`). The internal classes still compile but may be rearranged in 0.3+.

## Claude Code usage

If Claude Code shows its own MCP permission prompt first, allow the `minecraft` MCP server/tool there so the request reaches the game. The final execution decision is handled by the in-game admin approval buttons.

## Companion

- `mc-agent-link-agent` 0.3.0-alpha is updated to use `AgentLinkApi.isAdmin()` and to drop its own `claude.admin_uuids` field. Upgrade both mods together.

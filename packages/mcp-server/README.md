# agent-link MCP server

A Model Context Protocol stdio server that bridges any MCP-aware agent (Claude Code, Cursor, Zed, custom) to a Minecraft server running the [`agent-link`](../../minecraft/forge-mod) Forge mod.

## Quick start

```bash
cd packages/mcp-server
npm install
npm run build
```

Configure the host (e.g. `.claude/mcp.json` or Cursor's settings):

```json
{
  "mcpServers": {
    "minecraft": {
      "command": "node",
      "args": ["/absolute/path/to/mc-agent-link/packages/mcp-server/dist/index.js"],
      "env": {
        "AGENT_LINK_URL": "ws://127.0.0.1:25580",
        "AGENT_LINK_TOKEN": "<paste from server config/agent-link.toml>"
      }
    }
  }
}
```

The token is generated on the server's first start and lives in `<server>/config/agent-link.toml`.

## Tools exposed (v0)

- `ping`
- `run_console_command`
- `list_online_players`
- `get_player_info`
- `broadcast`
- `get_server_stats`

Events (`chat`, `player_join`, `player_leave`, `player_death`) are delivered to the bridge but not yet surfaced as MCP resources / notifications. Coming next.

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

## How agents discover what to do

The bridge ships an MCP `instructions` string in its `initialize` response. Any
compliant host (Claude Code, Cursor, Zed, custom clients) feeds those
instructions to the model automatically — agents see a description of the
server, the tool groups, and the recommended diagnosis loop without any extra
prompt engineering on the user's side.

If you're building a host that ignores `instructions`, you'll want to surface
it yourself; see `INSTRUCTIONS` in `src/index.ts`.

## Tools exposed (v0)

Operations:

- `ping`
- `run_console_command`
- `list_online_players`
- `get_player_info`
- `broadcast`
- `get_server_stats`

Observation (pull):

- `get_recent_events` — chat / join / leave / death from a 1024-entry ring buffer
- `get_recent_logs` — server console log lines (everything, including stack traces) from a 2048-entry buffer

Diagnosis & remote management:

- `list_mods` — installed mods with id, version, display name
- `read_server_file` — read any file under server root (crash reports, logs, configs, spark output…)
- `list_dir` — list a directory under server root
- `write_config_file` — **only writable area is `config/`**; existing files are auto-backed-up to `config/.agent-link-backup/`
- `tick_profile` — full tick distribution (avg / p50 / p95 / p99 / max mspt)
- `thread_dump` — JVM thread dump with state and stack frames

### Why pull, not push

The MCP bridge intentionally does not expose `subscribe_events` / `unsubscribe_events`. Push subscriptions exist in the agent-link protocol for non-MCP clients (a moderation bot, a dashboard), but for an LLM agent, pulling on demand keeps token spend bounded — the agent only pays for events it asked for.

### Diagnosis loop

A typical cycle for "the server feels laggy":

1. `tick_profile` — is the avg fine but p99 bad?
2. `thread_dump` — what was `Server thread` doing when sampled?
3. `run_console_command` `/spark profiler start` → wait → `/spark profiler stop` → grab the URL or read `spark/` via `read_server_file`
4. `list_mods` to map the hot package back to a mod
5. `read_server_file` on `config/<that-mod>.toml`
6. `write_config_file` to tune the offending value (auto-backed-up); ask the operator to `/reload` or restart


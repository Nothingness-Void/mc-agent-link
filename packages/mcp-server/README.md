# agent-link MCP bridge (legacy compatibility)

A Model Context Protocol stdio bridge that connects an MCP-aware agent (Claude Code, Cursor, Zed, or a custom host) to a Minecraft server running the [`agent-link`](../../minecraft/forge-mod) Forge mod.

> New installations should use the server's MCP Streamable HTTP endpoint and local setup endpoint. This package is retained for hosts that cannot use HTTP transport. It exposes the legacy v0 WebSocket tool subset, not the complete 0.5.0 structured-tool surface.

See the root [pairing guide](../../docs/pairing.md) for the current setup flow.

## Quick start

```bash
cd packages/mcp-server
npm install
npm run build
```

Pair the server first using its local `http://.../pair/setup/...` endpoint. Use the bearer token returned by the successful `/pair` response below; do not copy a token from `config/agent-link.toml` or put the one-use setup URL in this bridge configuration.

Then configure the host (e.g. `.claude/mcp.json` or Cursor's settings):

```json
{
  "mcpServers": {
    "minecraft": {
      "command": "node",
      "args": ["/absolute/path/to/mc-agent-link/packages/mcp-server/dist/index.js"],
      "env": {
        "AGENT_LINK_URL": "ws://127.0.0.1:25580",
        "AGENT_LINK_TOKEN": "<bearer token returned by /pair>"
      }
    }
  }
}
```

`AGENT_LINK_TOKEN` is a persistent server credential. Keep it in the host's secret configuration and never commit or paste it into chat. The setup endpoint and pair code are short-lived and one-use; the bridge itself connects to the legacy WebSocket port after pairing.

## How agents discover what to do

The bridge ships an MCP `instructions` string in its `initialize` response. Any
compliant host (Claude Code, Cursor, Zed, custom clients) feeds those
instructions to the model automatically — agents see a description of the
server, the tool groups, and the recommended diagnosis loop without any extra
prompt engineering on the user's side.

If you're building a host that ignores `instructions`, you'll want to surface
it yourself; see `INSTRUCTIONS` in `src/index.ts`.

## Tools exposed (v0 compatibility subset)

The bridge intentionally exposes the older diagnostic and operator subset below. For native block writes, snapshots/undo, NBT, structured player/entity/world controls, async tasks, and the full current tool catalog, use MCP Streamable HTTP directly.

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
- `write_config_file` — writes are gated by `write_allow` / `write_deny` globs in `config/agent-link.toml` (default: `config/**` only). Existing files are auto-backed-up to `config/.agent-link-backup/`
- `tick_profile` — full tick distribution (avg / p50 / p95 / p99 / max mspt)
- `thread_dump` — JVM thread dump with state and stack frames

Spark integration (only useful when the [spark](https://spark.lucko.me) mod is installed):

- `spark_status` — probe spark; always succeeds. Call this first.
- `spark_stats` — multi-window TPS / MSPT / CPU / GC from the spark Java API
- `spark_profiler_start` / `spark_profiler_stop` / `spark_profiler_cancel` — sample CPU; `_stop` returns the viewer URL
- `spark_health_report` — `/spark health --upload`, returns a shareable URL

### Why pull, not push

The MCP bridge intentionally does not expose `subscribe_events` / `unsubscribe_events`. Push subscriptions exist in the agent-link protocol for non-MCP clients (a moderation bot, a dashboard), but for an LLM agent, pulling on demand keeps token spend bounded — the agent only pays for events it asked for.

### Diagnosis loop

A typical cycle for "the server feels laggy":

1. `tick_profile` — is the avg fine but p99 bad?
2. `thread_dump` — what was `Server thread` doing when sampled?
3. `spark_status` — if installed, `spark_profiler_start { timeout: 30 }` → `spark_profiler_stop` → grab the URL. Without spark, the JVM thread dump is the deepest signal you have.
4. `list_mods` to map the hot package back to a mod
5. `read_server_file` on `config/<that-mod>.toml`
6. `write_config_file` to tune the offending value (auto-backed-up); ask the operator to `/reload` or restart


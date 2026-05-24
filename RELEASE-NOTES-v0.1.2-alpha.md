# mc-agent-link v0.1.2-alpha

> ⚠️ **Alpha**: WebSocket wire protocol (`v=0`) and tool surface unchanged. Adds a second transport (MCP HTTP) and ships two more spark fixes.

## Highlights

### MCP HTTP transport — Node bridge no longer required

The mod now exposes the same tool surface as a native [MCP Streamable HTTP](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports#streamable-http) server on a second port (default `25581`). Hosts that support HTTP transport (Claude Code, Cursor, …) can connect with a single-line config:

```json
{
  "mcpServers": {
    "minecraft": {
      "type": "http",
      "url": "http://127.0.0.1:25581/mcp",
      "headers": { "Authorization": "Bearer <token>" }
    }
  }
}
```

No Node.js, no `npm install`, no absolute paths.

The Node bridge in `packages/mcp-server` is unchanged and remains the right path for hosts that don't speak HTTP transport yet — see [INSTALL.md Appendix A](INSTALL.md#附录-a--node-bridgestdio兼容路径).

**Implementation**:

- Built on JDK's `com.sun.net.httpserver.HttpServer` — zero new dependencies.
- 2025-06-18 protocol with 2025-03-26 fallback. Server-initiated SSE is intentionally not implemented (`GET /mcp` → `405`); pull-mode events via `get_recent_events` cover the same use case.
- Authentication: `Authorization: Bearer <token>` (constant-time compare) + `Origin` header allowlist (default `["null", "http://localhost", "http://127.0.0.1"]`) + `Accept` validation + 128 KiB body cap.
- Both transports share one dispatcher and one tool registry, so behaviour, sandbox limits, and tool semantics are identical regardless of how the agent connects.

**New config keys** (`config/agent-link.toml`):

```toml
mcp_enabled = true
mcp_listen_port = 25581
mcp_allowed_origins = ["null", "http://localhost", "http://127.0.0.1"]
```

If `allow_remote = true`, narrow `mcp_allowed_origins` to your trusted clients to prevent DNS-rebinding attacks.

Commit: [`4e2d51d`](https://github.com/Nothingness-Void/mc-agent-link/commit/4e2d51d).

### spark fixes (one was wrong, one was missing)

`v0.1.1-alpha` claimed to fix `spark_stats` against `spark-1.10.53-forge`. It didn't — the fix only worked against the `spark-api` jar this mod was *compiled* against. In the wild, every statistic still came back as `NoSuchMethodException`, and `spark_profiler_*` returned an empty `output` with `url_present: false`.

**spark_stats — wrong erasure assumption.** `DoubleStatistic` / `GenericStatistic` are declared with `<W extends Enum<W> & StatisticWindow>`. Older `spark-api` builds erased `W` to `StatisticWindow`; the build embedded in `spark-1.10.53-forge` erases to `Enum` (the leftmost bound). `getMethod("poll", StatisticWindow.class)` fails on the latter. Fix: scan the interface's methods for the unique `poll(<single-arg, not Object>)` and ignore the synthetic bridge `poll(Object)` — version-skew-proof.

**spark_profiler_* — output capture deadlocked the server thread.** spark's async upload calls back via `CommandSource.sendSystemMessage`, which on Forge bounces onto the server thread via `mc.execute()`. Tools already run on the server thread, so a plain `Object.wait()` blocked the very thread that needed to drain the callback — the URL message never arrived. Fix: drive the wait through `MinecraftServer#managedBlock`, which polls the server task queue while parking. URL extraction now succeeds and `url_present` flips to `true`.

Commit: [`bc2aa69`](https://github.com/Nothingness-Void/mc-agent-link/commit/bc2aa69).

## Wire protocol

Same `v=0`. Same WebSocket frames (`hello` / `welcome` / `request` / `response` / `event`). Same tool names, args, results. Same error codes.

## Tool surface

Same 22 wire-level tools. Over MCP HTTP, 20 tools are exposed (`subscribe_events` / `unsubscribe_events` are omitted because HTTP has no per-session state — use `get_recent_events` instead).

## Configuration

Existing `agent-link.toml` files keep working unchanged. The three new MCP keys are written with their defaults the next time the mod loads, so a restart is enough — no manual editing required.

## Skills / instructions

The four Claude Code skills (`/mc-overview`, `/mc-diagnose`, `/mc-crash`, `/mc-health-check`) are unchanged. The MCP `instructions` string is byte-for-byte the same on both transports.

## Artifacts

| File | Size | Goes into |
|---|---|---|
| `agent-link-forge-1.20.1-0.1.2-alpha.jar` | ~228 KB | `<server>/mods/` (Forge 1.20.1, Java 17) |
| `agent-link-mcp-server-0.1.2-alpha.tgz` | ~13 KB | `npm install -g <tgz>` or unpack and point your MCP host at `dist/index.js` (only needed for hosts that don't support HTTP transport) |
| `SHA256SUMS.txt` | — | Verify the above |

## Upgrade path from v0.1.0-alpha or v0.1.1-alpha

Drop in the new jar, restart the server. Existing `agent-link.toml` works as-is; the three new `mcp_*` keys are added with their defaults on the next load.

If you'd rather connect via HTTP, swap your MCP host config to the one-line shape above and remove the `command` / `args` / `env` block. Otherwise the Node bridge stays valid — just bump the tarball.

## Verify the artifacts

```sh
sha256sum agent-link-forge-1.20.1-0.1.2-alpha.jar agent-link-mcp-server-0.1.2-alpha.tgz
```

Expected hashes are in `SHA256SUMS.txt`.

## License

[Apache-2.0](https://github.com/Nothingness-Void/mc-agent-link/blob/main/LICENSE).

# agent-link wire protocol (v0)

> Status: draft. Subject to change until v1. Loader-agnostic — Forge, NeoForge, Fabric, and Paper implementations all speak this same protocol.

## Transport

WebSocket over TCP. Default port `25580` (configurable per server). Frames are UTF-8 JSON, one message per frame.

A single Minecraft server instance runs one WebSocket listener. Multiple bridges/agents may connect concurrently.

## Authentication

The mod ships with a token in `config/agent-link.toml`:

```toml
listen_port = 25580
token = "..."          # generated on first run if empty
allow_remote = false   # bind 127.0.0.1 unless true
```

Clients must complete the auth handshake before any other request is honored. The server will close the socket with code `4401` on auth failure.

```json
// → client
{"v": 0, "type": "hello", "token": "..."}
// ← server (success)
{"v": 0, "type": "welcome", "server": {"mc_version": "1.20.1", "loader": "forge", "loader_version": "47.4.6", "agent_link_version": "0.1.0"}}
```

## Frame shapes

Every frame has a `type`. Three top-level types:

### Request (client → server)

```json
{"v": 0, "type": "request", "id": "uuid-or-int", "tool": "run_console_command", "args": {"command": "list"}}
```

### Response (server → client)

```json
{"v": 0, "type": "response", "id": "uuid-or-int", "ok": true, "result": {...}}
{"v": 0, "type": "response", "id": "uuid-or-int", "ok": false, "error": {"code": "INVALID_ARGS", "message": "..."}}
```

`id` echoes the request. Responses may arrive out of order.

### Event (server → client, after subscription)

```json
{"v": 0, "type": "event", "topic": "chat", "data": {"player": "Steve", "message": "hi"}, "ts": 1700000000000}
```

Events are pushed only to clients that subscribed via `subscribe_events`.

## Threading guarantees

The server processes incoming requests on its WebSocket I/O thread, then dispatches the work to the main server tick thread before touching world state. Responses are sent back asynchronously. Order of responses is **not guaranteed** to match order of requests — always correlate by `id`.

## Versioning

`v` is the protocol major version. Breaking changes bump `v`. Servers MUST reject frames with a `v` they do not support and respond with `{"type":"response", "ok":false, "error":{"code":"UNSUPPORTED_VERSION", ...}}`.

The `welcome` frame's `agent_link_version` is the implementation's semver and is informational; clients should branch on `v` for protocol decisions and on `loader` / `mc_version` for capability decisions.

## Tools (v0 minimum set)

These MUST be implemented by any conforming server. Per-loader extensions live under separately namespaced tool names (e.g. `forge.mods.list`).

| tool | args | result |
|---|---|---|
| `ping` | `{}` | `{"pong": true, "uptime_ms": 12345}` |
| `run_console_command` | `{"command": "list"}` | `{"return_value": 1, "output": "There are 0 of a max..."}` |
| `list_online_players` | `{}` | `{"players":[{"name":"Steve","uuid":"...","ping":42,"dim":"minecraft:overworld"}], "count":1}` |
| `get_player_info` | `{"name": "Steve"}` | `{"name","uuid","pos":[x,y,z],"dim","yaw","pitch","health","max_health","food","xp_level","gamemode","ping"}` |
| `broadcast` | `{"message": "hi all", "color": "yellow"}` | `{"sent": true, "recipients": 3}` |
| `get_server_stats` | `{}` | `{"tps":19.97,"mspt":12.4,"mem_used_mb":4096,"mem_max_mb":16384,"loaded_chunks":1234,"online":3,"max_players":20}` |
| `get_recent_events` | `{"since_seq":0,"limit":50,"topics":["chat",...]}` | `{"events":[{"seq":N,"ts":...,"topic":"chat","data":{...}}], "returned":N, "head_seq":N, "oldest_seq":1, "buffer_capacity":1024, "truncated":false}` |
| `get_recent_logs` | `{"since_seq":0,"limit":100,"levels":["WARN","ERROR"],"contains":"foo"}` | `{"logs":[{"seq":N,"ts":...,"level":"INFO","logger":"...","message":"..."}], "returned":N, "head_seq":N, "oldest_seq":1, "buffer_capacity":2048, "truncated":false}` |
| `subscribe_events` | `{"topics":["chat","player_join","player_leave","player_death"]}` | `{"subscribed":["chat","player_join",...]}` |
| `unsubscribe_events` | `{"topics":["chat"]}` | `{"unsubscribed":["chat"]}` |
| `list_mods` | `{}` | `{"mods":[{"mod_id","display_name","version","description"}], "count":N, "loader":"forge"}` |
| `read_server_file` | `{"path":"crash-reports/foo.txt","offset":0,"max_bytes":262144}` | `{"path","size","offset","bytes_read","truncated","encoding":"utf-8"|"base64","content"}` |
| `list_dir` | `{"path":"config","max_entries":500}` | `{"path","entries":[{"name","is_dir","size","mtime_ms"}], "returned":N, "total":N, "truncated":false}` |
| `write_config_file` | `{"path":"config/foo.toml","content":"...","overwrite":false,"encoding":"utf-8"}` | `{"path","bytes_written","created":true,"backup":"config/.agent-link-backup/..."}` |
| `tick_profile` | `{}` | `{"samples":100,"avg_mspt":12.4,"max_mspt":48.9,"p50_mspt":11.0,"p95_mspt":22.0,"p99_mspt":40.0,"tps":19.97,"window_ticks":100}` |
| `thread_dump` | `{"max_frames":30,"only_server":false}` | `{"threads":[{"id","name","state","lock?","lock_owner?","stack":[...],"stack_truncated"}], "count":N}` |
| `spark_status` | `{}` | `{"installed":true,"command_available":true,"api_available":true,"profiler_info":"..."}` — never errors |
| `spark_stats` | `{}` | `{"api_available":true,"tps":{"seconds_5":19.9,...},"mspt":{"seconds_10":{"mean","max","min","median","p95"}},"cpu_process":{...},"cpu_system":{...},"gc":{"G1 Young Generation":{...}}}` |
| `spark_profiler_start` | `{"timeout":30,"interval_ms":4,"only_ticks_over_ms":50,"thread_all":false,"alloc":false}` | `{"started":true,"command":"/spark profiler start ...","output":"..."}` |
| `spark_profiler_stop` | `{"comment":"...","save_to_file":false,"wait_url_ms":15000}` | `{"command","output","url?","url_present"}` |
| `spark_profiler_cancel` | `{}` | `{"output","cancelled":true}` |
| `spark_health_report` | `{"memory":false,"network":false,"wait_url_ms":45000}` | `{"command","output","url?","url_present"}` |

### Pull vs push

The default and recommended consumption mode is **pull**: every event is appended to a server-side ring buffer (1024 entries) and agents call `get_recent_events` when they want to know what's been happening. This way the LLM only spends tokens on events the agent explicitly requested.

For incremental polling pass the previous response's `head_seq` as `since_seq` next time. Events older than `oldest_seq` have been evicted from the buffer.

`subscribe_events` (push) is retained for advanced clients that want a real-time stream — e.g. a moderation bot reacting to chat. Push and pull share the same buffer, so subscribing does not disable the pull path.

## Event topics (v0)

| topic | data shape |
|---|---|
| `chat` | `{"player","uuid","message"}` |
| `player_join` | `{"player","uuid","address"}` |
| `player_leave` | `{"player","uuid"}` |
| `player_death` | `{"player","uuid","cause","killer"}` |

## Filesystem sandbox

`read_server_file` and `list_dir` accept any path under the server root (defined as `MinecraftServer#getServerDirectory`). Path traversal (`..`) and absolute paths are rejected.

`write_config_file` is the only write tool. It is gated by two glob lists in `config/agent-link.toml`:

```toml
write_allow = ["config/**"]   # default — only config/ is writable
write_deny = []               # default — nothing extra denied
```

Evaluation order:

1. Path must resolve inside server root.
2. If any `write_deny` glob matches → reject (`INVALID_ARGS`, "matches write_deny pattern X").
3. If no `write_allow` glob matches → reject (`INVALID_ARGS`, "does not match any write_allow pattern").
4. Otherwise → allowed.

Glob syntax follows the JDK's `FileSystem.getPathMatcher` — `**` matches any number of segments, `*` matches any chars within a segment, `?` matches one char. Patterns are evaluated against the server-root-relative path with forward slashes (e.g. `config/foo.toml`). Edit the file, restart the server, the new rules take effect.

Examples:

```toml
# Allow forge configs and a specific data file, but never the security one.
write_allow = ["config/**", "data/whitelist/*.json"]
write_deny  = ["config/security/**"]

# Read-only mode — no writes ever succeed.
write_allow = []

# Wide open (DON'T do this on a real server) — every path under server root.
write_allow = ["**"]
```

Other guarantees:

- Reads are capped at 4 MiB per call (default 256 KiB). Binary files are returned base64-encoded.
- Writes are capped at 4 MiB and are atomic (write to `*.agent-link.tmp`, rename onto target).
- Existing files are auto-backed-up to `config/.agent-link-backup/<encoded-path>.<timestamp>.bak` before being overwritten. The encoded path replaces `/` with `_` so backups from outside `config/` don't collide.
- Successful writes are mirrored to the server log (`agent-link: wrote …`) for human audit.
- The `config/.agent-link-backup/` directory itself is never writable — restore manually if needed.
- Deletes, moves, and renames are intentionally not exposed; use `run_console_command` if the operating system level is required.

## Spark integration

When the [spark](https://spark.lucko.me) profiler mod is installed, six additional tools light up. They are loader-agnostic on the wire, but Forge is the only loader implemented today.

- `spark_status` always succeeds; agents should call it before any other `spark_*` tool. If `installed: false`, fall back on `tick_profile` and `thread_dump`.
- `spark_stats` uses the spark Java API (`me.lucko:spark-api`, loaded reflectively so the mod still runs without spark). Returns multi-window TPS / MSPT / CPU / GC breakdowns.
- `spark_profiler_start` / `_stop` / `_cancel` wrap `/spark profiler ...`. The wire protocol stays request/response — `_stop` blocks for up to `wait_url_ms` (default 15s, max 60s) waiting for spark's asynchronous upload to surface a viewer URL. If the URL doesn't arrive in time, `url_present: false` is returned and the agent can either retry or read `spark/` via `read_server_file`.
- `spark_health_report` runs `/spark health --upload` and returns the URL. Default `wait_url_ms` is 45s because spark may spend ~30s collecting world statistics before uploading.

While waiting for spark URLs, the mod keeps the Minecraft server task queue draining; non-URL spark output is captured into `output` but never ends the wait early.

When spark is not installed, every tool except `spark_status` returns `SPARK_UNAVAILABLE`.

## MCP Streamable HTTP transport

In addition to the WebSocket protocol above, the mod exposes the same tool surface as a native MCP Streamable HTTP server. MCP hosts (Claude Code, Cursor, …) connect directly — no Node bridge required.

**MCP endpoint**: `POST http://<host>:<mcp_listen_port>/mcp` (default port `25581`).

**Setup link**: on startup the mod logs a one-use setup link valid for 10 minutes:

```text
https://github.com/Nothingness-Void/mc-agent-link#agent-link-setup=<base64url-json>
```

The decoded payload is:

```json
{
  "v": 1,
  "repo": "https://github.com/Nothingness-Void/mc-agent-link",
  "mcp_url": "http://127.0.0.1:25581/mcp",
  "pair_url": "http://127.0.0.1:25581/pair",
  "pair_code": "1234-5678",
  "expires_at": 1779640000000,
  "expires_at_iso": "2026-05-25T00:00:00Z",
  "allow_remote": false
}
```

Agents exchange it with:

```http
POST <pair_url>
Accept: application/json
Content-Type: application/json

{"pair_code":"1234-5678"}
```

Success returns the MCP host config block:

```json
{
  "mcp": {
    "type": "http",
    "url": "http://127.0.0.1:25581/mcp",
    "headers": {
      "Authorization": "Bearer <token>"
    }
  }
}
```

The pair code is consumed after one successful exchange. `401` means invalid, expired, or already used.

**MCP headers**:

- `Authorization: Bearer <token>` — same token as the WebSocket transport (`config/agent-link.toml`)
- `Accept: application/json` — required by spec; SSE is not produced
- `Content-Type: application/json`
- `Origin` — optional. Native hosts usually omit it; browsers send the origin. Validated against `mcp_allowed_origins` in the config (default `["null", "http://localhost", "http://127.0.0.1"]`).

**Spec compliance**: implements the 2025-06-18 protocol revision with the 2025-03-26 fallback. We do not initiate server-to-client messages, so the GET-based SSE stream documented in the spec is intentionally returned as `405 Method Not Allowed`.

**JSON-RPC 2.0 methods**:

| method | params | result |
|---|---|---|
| `initialize` | `{protocolVersion, capabilities, clientInfo}` | `{protocolVersion, capabilities: {tools: {}}, serverInfo: {name: "agent-link", version}, instructions}` |
| `tools/list` | `{}` | `{tools: [{name, description, inputSchema}, ...]}` (20 tools) |
| `tools/call` | `{name, arguments}` | `{content: [{type: "text", text: "<JSON tool result>"}], isError: false}` |
| `notifications/initialized` | (notification) | (acknowledged with `202 Accepted`) |

Tool execution failures are returned as MCP tool errors (`isError: true`) rather than JSON-RPC errors, so the host can surface them to the model.

**Error surfaces**:

| HTTP status | meaning |
|---|---|
| `401` | missing/invalid `Authorization` header on `/mcp`, or invalid/expired/used pair code on `/pair` |
| `403` | `Origin` header not in `mcp_allowed_origins` |
| `405` | non-`POST` method (or `GET` since we don't stream) |
| `406` | `Accept` header doesn't include `application/json` |
| `413` | request body exceeds 128 KiB |
| `400` | malformed JSON-RPC request (mapped to JSON-RPC `-32700` / `-32600`) |
| `200` | successful response (or any JSON-RPC error inside the body) |
| `202` | acknowledged notification |

The HTTP transport shares the dispatcher and tool registry with the WebSocket transport, so behavior, sandbox limits, and tool semantics are identical. The two transports can run simultaneously — disable HTTP via `mcp_enabled = false` if undesired.

The two stateful tools `subscribe_events` / `unsubscribe_events` are not useful over HTTP (no persistent session); use `get_recent_events` instead.

## Error codes

| code | meaning |
|---|---|
| `UNAUTHENTICATED` | sent before `hello` |
| `INVALID_TOKEN` | wrong token |
| `UNSUPPORTED_VERSION` | `v` mismatch |
| `UNKNOWN_TOOL` | tool not implemented on this loader |
| `INVALID_ARGS` | argument validation failed |
| `INTERNAL_ERROR` | unexpected exception (server logs the trace) |
| `TIMEOUT` | tool exceeded its server-side budget |
| `SPARK_UNAVAILABLE` | a `spark_*` tool was called but the spark mod is not installed |

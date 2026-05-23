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
| `run_console_command` | `{"command": "list"}` | `{"output": "There are 0 of a max..."}` |
| `list_online_players` | `{}` | `{"players": [{"name":"Steve","uuid":"...","ping":42}]}` |
| `get_player_info` | `{"name": "Steve"}` | `{"name", "uuid", "pos":[x,y,z], "dim", "health", "food", "gamemode", "ping"}` |
| `broadcast` | `{"message": "hi all", "color": "yellow"}` | `{"sent": true}` |
| `get_server_stats` | `{}` | `{"tps_1m":19.97,"mspt":12.4,"mem_used_mb":4096,"mem_max_mb":16384,"loaded_chunks":1234,"online":3}` |
| `get_recent_events` | `{"since_seq":0,"limit":50,"topics":["chat",...]}` | `{"events":[{"seq":N,"ts":...,"topic":"chat","data":{...}}], "head_seq":N, "oldest_seq":1, "buffer_capacity":1024, "truncated":false}` |
| `subscribe_events` | `{"topics":["chat","player_join","player_leave","player_death"]}` | `{"subscribed":["chat","player_join",...]}` |
| `unsubscribe_events` | `{"topics":["chat"]}` | `{"unsubscribed":["chat"]}` |

### Pull vs push

The default and recommended consumption mode is **pull**: every event is appended to a server-side ring buffer (1024 entries) and agents call `get_recent_events` when they want to know what's been happening. This way the LLM only spends tokens on events the agent explicitly requested.

For incremental polling pass the previous response's `head_seq` as `since_seq` next time. Events older than `oldest_seq` have been evicted from the buffer.

`subscribe_events` (push) is retained for advanced clients that want a real-time stream — e.g. a moderation bot reacting to chat. Push and pull share the same buffer, so subscribing does not disable the pull path.

## Event topics (v0)

| topic | data shape |
|---|---|
| `chat` | `{"player","uuid","message"}` |
| `player_join` | `{"player","uuid","ip"}` |
| `player_leave` | `{"player","uuid"}` |
| `player_death` | `{"player","uuid","cause","killer"}` |
| `server_log` | `{"level","logger","message"}` (optional, opt-in via `subscribe_events`) |

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

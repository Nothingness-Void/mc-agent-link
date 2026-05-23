# mc-agent-link v0.1.0-alpha

> ⚠️ **Alpha**: this is a self-install dry run. APIs, the wire protocol (`v=0`), and tool names may shift. The token in your `agent-link.toml` is op-equivalent — keep it private.

The first agent-installable release of mc-agent-link. Hand [INSTALL.md](https://github.com/Nothingness-Void/mc-agent-link/blob/main/INSTALL.md) to your AI agent and it should be able to set everything up end-to-end.

## What's in the box

Two artifacts:

| File | Size | Goes into |
|---|---|---|
| `agent-link-forge-1.20.1-0.1.0-alpha.jar` | ~370 KB | `<server>/mods/` (Forge 1.20.1, Java 17) |
| `agent-link-mcp-server-0.1.0-alpha.tgz` | ~13 KB | `npm install -g <tgz>` or unpack and point your MCP host at `dist/index.js` |

Source is at this tag — `git clone --branch v0.1.0-alpha` works the same.

## What an agent can do once installed

| Group | Tools |
|---|---|
| Operations | `ping`, `run_console_command`, `list_online_players`, `get_player_info`, `broadcast`, `get_server_stats` |
| Observation (pull) | `get_recent_events` (chat / join / leave / death), `get_recent_logs` (full server console + stack traces) |
| Diagnosis | `tick_profile`, `thread_dump`, `list_mods` |
| Filesystem (sandboxed) | `read_server_file`, `list_dir`, `write_config_file` |
| Spark integration (only if [spark](https://spark.lucko.me) is also installed) | `spark_status`, `spark_stats`, `spark_profiler_start`/`_stop`/`_cancel`, `spark_health_report` |

20 tools total. See [docs/protocol.md](https://github.com/Nothingness-Void/mc-agent-link/blob/main/docs/protocol.md) for the full wire protocol.

## How agents discover the surface

The MCP bridge ships an `instructions` string in its `initialize` response — Claude Code, Cursor, Zed, and any compliant host feed it to the model automatically. No prompt engineering required on your side.

Four Claude Code skills are committed in-repo (`.claude/skills/`):

- `/mc-health-check` — connectivity check
- `/mc-overview` — one-round status snapshot
- `/mc-diagnose` — lag diagnosis loop with optional spark
- `/mc-crash` — crash report investigation

For non-Claude agents, read the same `SKILL.md` files as prompt templates.

## Configurable safety

Operators control what agents can do via `<server>/config/agent-link.toml`:

```toml
listen_port = 25580
allow_remote = false               # bind to 127.0.0.1 only by default
token = "..."                      # auto-generated on first start; treat as op credential
write_allow = ["config/**"]        # glob allowlist for write_config_file
write_deny  = []                   # glob denylist (evaluated first)
```

`write_deny` overrides `write_allow`. Glob syntax: `**` (any segments), `*` (any chars within a segment), `?` (one char). Restart the server to apply changes.

## Known limitations / not in this release

- Forge 1.20.1 only — NeoForge / Fabric / Paper planned.
- No automatic restart, no automatic mod install. Agents *suggest*; humans confirm.
- `subscribe_events` / `unsubscribe_events` exist on the wire protocol but aren't surfaced through MCP (pull is enough for LLM consumption).
- No CI yet; no unit tests.
- The TOML `version` reported in the welcome frame is `0.1.0-alpha`. Use it to gate behavior in agents that connect to multiple versions.

## Goals of the alpha

Concretely, three things to test:

1. **Can an agent install this from `INSTALL.md` alone?** End-to-end: clone repo → build → put jar in mods/ → grab token → write MCP host config → smoke-test with `ping`.
2. **Are the in-protocol `instructions` enough for an agent to use the tools correctly?** Without anyone pre-loading the skills.
3. **Do the safety rails actually rail?** `write_config_file` outside `write_allow`, `run_console_command stop`, etc.

If you hit something, please open an issue — including which agent host you used, what step failed, and the exact error string. Bug reports beat star count for a project at this stage.

## Verify the artifacts

```sh
# After download:
sha256sum agent-link-forge-1.20.1-0.1.0-alpha.jar agent-link-mcp-server-0.1.0-alpha.tgz
```

(SHA256 sums included as separate files in this release.)

## License

[Apache-2.0](https://github.com/Nothingness-Void/mc-agent-link/blob/main/LICENSE).

# mc-agent-link v0.2.5-alpha

## Highlights

Per-call **audit log** for every MCP tool invocation, plus structured `Decision` so
"who approved this and why" is finally answerable after the fact.

This was already on the v0.3.0 TESTBOOK as a known gap ("no cooldown / rate limit /
audit log"); cooldown and rate limit are deferred, audit lands now because the
risk surface widened a lot in 0.2.4 (`get_container`, `read_config`, `save_block_snapshot`).

Audit being in place also lowers the cost of unfreezing `restore_block_snapshot` and
`get_player_death_log` next round — you can review what an agent actually did.

## What's new

### `logs/agentlink-audit.log`

One JSON object per line, written by a daemon writer thread. Each line includes:

| Field | Meaning |
|---|---|
| `ts` | ISO-8601 UTC timestamp |
| `tool` | Tool name |
| `outcome` | `auto_allow` / `approval_disabled` / `trusted_rule` / `approved` / `denied` / `denied_no_approvers` / `timed_out` / `server_stopping` |
| `actor` / `actor_uuid` | Set when an OP clicked `[Allow once]` / `[Always allow]` / `[Deny]` |
| `trust_rule` | The trust rule string when the call was auto-allowed by a previous trust |
| `reason` | Human-readable reason (translated) |
| `result_ok` | true on tool success |
| `error_code` / `error_message` | When the tool errored or approval was denied |
| `args_json` | The args that were passed in, with sensitive values redacted |
| `result_redacted_keys` / `result_summary` | Redacted result keys (e.g. `read_config.content`) |

Audit writes are non-blocking from the dispatcher's point of view — invocations enqueue
into a daemon writer thread.

### Redaction

Sensitive fields are replaced with `{"redacted": true, "length": N}` before writing.

Default `audit.redact_args`:
- `run_console_command.command` — full /op or /seed payloads
- `write_config_file.content` and `write_config_file.base64`
- `read_config.content` — file contents (note: result-side, not arg-side)

You can extend this from `agent-link.toml` for any addon tool whose arg or returned
content is sensitive. Format: `tool_name.arg_key`.

### `/agentlink audit tail [N]` and `/agentlink audit path`

Two new subcommands of `/agentlink` (admin-gated when `admin_uuids` is configured;
falls back to OP when no admins are set, same as the rest of the approval system):

- `/agentlink audit path` — print the absolute path to the audit log
- `/agentlink audit tail` — print the last 20 lines (default)
- `/agentlink audit tail <N>` — print the last N lines (max 200)

Lines are printed verbatim as the JSON they were written as, so they can be copied
straight into `jq` for filtering.

### Structured `Decision`

Internal: `AgentToolApproval.Decision` now carries `Outcome` (enum), `actor`,
`actorUuid`, and `trustRule` instead of just a bool + free-text reason. Backward
compatible — the old `Decision.approved(reason)` / `Decision.denied(reason)`
factories still exist; new code paths and the audit log use the structured fields.

## Configuration

Three new keys in `agent-link.toml`:

```toml
[audit]
enabled = true
redact_args = [
    "run_console_command.command",
    "write_config_file.content",
    "write_config_file.base64",
    "read_config.content",
]
max_arg_chars = 2000
```

`max_arg_chars` truncates the args JSON at N chars so a single bad call can't
balloon the log file. Truncated lines get an additional `args_truncated_at` field.

## Compatibility

- Old `agent-link.toml` keeps working — `audit.*` keys are written on first load
  with sensible defaults.
- Disabling audit: set `audit.enabled = false`. The `/agentlink audit tail` command
  reports "audit disabled" when called against a disabled service.
- `mc-agent-link-agent 0.3.5-alpha` bumps `base_agentlink_version_range` to
  `[0.2.5-alpha,)`. The addon itself is unchanged.

## Known gaps still open

- **Long reply truncation** (P0-4 in TESTBOOK) — list-type tools still hit a 1500-char
  ceiling somewhere (likely the agent-side chat layer; not confirmed). Two-stage
  fetch is the planned fix.
- **Catalog cold start** — `get_recipes_for` / `get_block_drops` still walk the full
  managers on first call. No complaint yet, no fix.
- `restore_block_snapshot` / `get_player_death_log` / `start_task` — still deferred,
  separate releases when scoped.

## Reference

`docs/tools.md` updated with the audit section.

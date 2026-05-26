# mc-agent-link v0.4.0-alpha

## Highlights

Three things land together:

1. **Token tiers** — `/agentlink pair` now mints a **CONSOLE** token that bypasses in-game approval entirely; the new `/agentlink pair-guest` keeps the legacy GUEST behavior. Closes the "I'm in the terminal supervising, why am I clicking buttons in-game?" mismatch.
2. **`we_set` NPE fix** — the 0.2.6 jar crashed when filling a region without a mask. Routed through `EditSession.setBlocks(Region, Pattern)` for the no-mask path.
3. **Version-number unification** — base + addon both jump to **0.4.0-alpha**. Going forward they ship in lockstep; addon was 0.3.x ahead, this catches them up via a one-time minor jump rather than future drift.

## Token tiers

When pairing a Minecraft server with an MCP host, you now choose what kind of trust the bearer token carries:

| Tier | Issued by | In-game approval | Use case |
|---|---|---|---|
| **CONSOLE** | `/agentlink pair` (default) | **bypassed** — including `admin_only_tools` | You run Claude Code (or another MCP host) in your own terminal alongside the server. Terminal-side oversight replaces in-game oversight. |
| **GUEST** | `/agentlink pair-guest`, or the legacy master `token` field in `agent-link.toml` | enforced as before | Sharing access with someone you don't fully trust; the addon Claude bridge handling in-game `/agent` calls; remote MCP hosts. |

CONSOLE tier still gets full audit coverage — every call records `outcome: console_trusted` and the new `tier: console` field, so an admin can run `/agentlink audit tail` to review what was done. The bypass only skips the prompt, not the recordkeeping.

### Why this matters

Before: `/agent` called from in-game (no terminal visibility) and Claude Code from the terminal (full visibility) both triggered identical in-game approval prompts. The latter was redundant — you can already approve/deny in the terminal where the request originated. After: console-tier callers run immediately; guest-tier callers (notably the addon's Claude bridge serving `/agent` requests) still go through the existing approval flow.

### Migration

- `/agentlink pair` previously implicitly used the GUEST master token. **It is now CONSOLE.** If your existing setup link relied on going through approval, regenerate via `/agentlink pair-guest`.
- The legacy master `token` field in `agent-link.toml` keeps working and is implicit GUEST.
- The setup link payload now includes `token_tier`, and the `/pair` response carries a freshly minted per-pair token instead of handing out the master token. Each successful `/pair` produces a unique token recorded in the new `config/agent-link/issued_tokens.json` (hashes only — plaintext is never persisted).

### New commands

- `/agentlink pair-guest` — mint a guest-tier setup link
- `/agentlink tokens` — list issued tokens (hash prefix, tier, label, last_used_at)
- `/agentlink tokens revoke <hash-prefix>` — revoke by SHA-256 hash prefix

### Audit log

A new `tier` field is added to every line. Existing parsers should ignore unknown fields; nothing else changed.

```jsonl
{"ts":"2026-05-27T01:23:45Z","tool":"we_set","tier":"console","outcome":"console_trusted","reason":"console-tier token","result_ok":true,...}
```

## `we_set` fix

The 0.2.6 build crashed when called without a `from`/mask:

```
[WE_ERROR] WE replaceBlocks failed: NullPointerException
```

WorldEdit's `replaceBlocks(Region, Mask, Pattern)` rejects a null Mask. The bridge now detects no-mask calls and routes them through `setBlocks(Region, Pattern)` instead. This was caught during the 0.2.6 live test on Forge 1.20.1 + WorldEdit 7.3.

## Version unification

Base was at 0.2.6, addon was at 0.3.6 (the addon ran ahead because it once needed faster iteration). Both are now **0.4.0-alpha**. The addon's `base_agentlink_version_range` is bumped to `[0.4.0-alpha,)`. From here on:

- Both ship in the same release with the same number
- A `0.x.y` bump means breaking; `0.4.x` means additive
- The addon repo's release-staging directory will mirror this

## Compatibility

- Old `agent-link.toml` keeps working. New `audit.*` and `roles.*` keys carry over. The legacy master token still authenticates as GUEST.
- Old setup links **predating 0.4.0** still work for legacy MCP hosts that already have the master token. New links from `/agentlink pair` will mint a CONSOLE token; if you don't want that, use `/agentlink pair-guest`.
- `mc-agent-link-agent 0.4.0-alpha` requires base `[0.4.0-alpha,)`.

## Reference

`docs/tools.md` updated:
- Approval section split into "token tier" + "per-tool tier" axes
- Operator commands table includes the new `/agentlink pair-guest` and `/agentlink tokens` subcommands

# AgentLink Spigot Plugin

This is the modern Spigot/Paper base-link implementation of `mc-agent-link`, targeting Minecraft 1.20+ and built as Java 17 bytecode. It only connects Claude Code, Codex, Cursor, or another MCP agent to the server. It does not include the in-game control features from `mc-agent-link-agent`.

The default jar is compiled against the lowest common Bukkit API used by this project, 1.20.1, and does not use NMS. The same jar is therefore intended for modern Spigot/Paper servers. Runtime platform capabilities are reported to the agent instead of assuming a loader-specific implementation.

## Explicitly excluded

- No `/agent` command and no GUI
- No in-game agent request queue, steer API, or Claude Code bridge
- No `mc-agent-link-agent` installation is required

## Included

- MCP Streamable HTTP at `http://127.0.0.1:25581/mcp`
- v0 WebSocket compatibility transport at `127.0.0.1:25580`
- One-use local setup-endpoint pairing and hash-only issued-token registry
- Console and guest token tiers
- OP/admin approval for guest writes
- `whoami`, server health, player/world reads, event polling, and JVM thread diagnostics
- File reads/writes constrained by `write-allow` / `write-deny`
- Undoable block writes: `set_block`, `fill_blocks`, `set_blocks`, and `undo_blocks`
- Basic player, entity, and world operation tools

## Build

JDK 17 is required to build the plugin. The Minecraft server's own Java requirement still follows the Minecraft version: 1.20.1 commonly uses Java 17, while 1.20.5+/1.21 commonly uses Java 21. The plugin's Java 17 bytecode runs on both.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17.0.3.1'
.\gradlew.bat clean build
```

Default universal artifact: `build/libs/agent-link-spigot-modern-0.5.0-alpha.jar`.

To compile and validate multiple modern API targets:

```powershell
.\build-modern.ps1 -JavaHome 'C:\Program Files\Java\jdk-17.0.3.1'
```

The script builds `1.20.1`, `1.20.6`, `1.21.1`, and `1.21.11` targets into `build/modern-releases/`. Use `agent-link-spigot-modern-*.jar` for normal cross-version installs; the other jars are version-pinned targets for compatibility testing or server-specific packaging.

## Install

Put the universal jar in the server's `plugins/` directory and start Spigot/Paper 1.20+. Before the first pairing, the plugin prints a local setup endpoint such as `http://127.0.0.1:25581/pair/setup/<random-id>`. Give that URL to the MCP agent; it reads the JSON directly, so GitHub is not required. Use the returned `mcp` object as `mcpServers.minecraft`.

Configuration is stored at `plugins/AgentLink/config.yml`. The default bind is local-only. Before remote use, protect the ports with a firewall and narrow `mcp.allowed-origins` to trusted origins.

## Administration commands

The plugin registers `/agentlink` only:

```text
/agentlink pair
/agentlink pair-guest
/agentlink status
/agentlink tokens
/agentlink revoke <hash-prefix>
/agentlink approvals
/agentlink approve <id>
/agentlink deny <id>
/agentlink reload
```

The `pair` command mints a console-tier token that bypasses guest approval. `pair-guest` mints a guest token whose writes require an online OP or `agentlink.admin` player. `reload` reads the file again; port and token changes require a server restart.

## Verification

Use the console local setup endpoint to pair first, then call `initialize`, `tools/list`,
`whoami`, `ping`, and `get_server_capabilities`. Pairing survives a server restart; run
`/agentlink pair` or `/agentlink pair-guest` only when adding another agent.

A browser or PowerShell GET request to `/mcp` returning `405 Method Not Allowed` is expected:
the MCP listener accepts authenticated JSON-RPC POST requests only. The tool list intentionally
contains no `agent_*`, `steer`, or in-game request-queue tools.

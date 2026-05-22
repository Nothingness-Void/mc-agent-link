# mc-agent-link

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

Lets AI agents (via the [Model Context Protocol](https://modelcontextprotocol.io)) connect to a running Minecraft server. Run commands, query players, stream events — anything an op could do, an agent can do.

> **Status:** early scaffolding. Forge 1.20.1 server-side first. NeoForge / Fabric / Paper planned.

## Why

Modern AI agents (Claude Code, Cursor, custom agents) speak MCP. Minecraft servers don't. RCON works but is request-only and limited. This project bridges the gap with a real-time WebSocket protocol on the server side and an MCP server on the agent side, so multiple agents can connect, observe, and act concurrently.

## Architecture

```
 ┌──────────────────────┐    stdio      ┌──────────────────────┐    WebSocket    ┌─────────────────────────────┐
 │  Agent (Claude Code, │ ◄──────────►  │  mcp-server (Node)   │ ◄────────────►  │  Forge mod (Java)           │
 │  Cursor, custom...)  │   MCP         │  packages/mcp-server │   agent-link    │  minecraft/forge-mod        │
 └──────────────────────┘               └──────────────────────┘   protocol      └─────────────────────────────┘
                                                                                  in-process WS server inside MC
```

- **Forge mod** runs *inside* the Minecraft server JVM, exposing a local WebSocket endpoint. All work is dispatched on the main server thread to stay thread-safe with the world state.
- **MCP server** is a thin Node.js process. It speaks the agent-link protocol on one side and MCP on the other. One mod can serve many agents through one bridge.
- **Multi-agent**: the mod accepts N concurrent WebSocket connections. The bridge multiplexes MCP hosts.

## Repo layout

```
mc-agent-link/
├── docs/
│   └── protocol.md            # agent-link wire protocol spec
├── minecraft/
│   └── forge-mod/             # Forge 1.20.1 mod (Java 17, Gradle)
└── packages/
    └── mcp-server/            # Node MCP bridge (TypeScript)
```

## Quick start

Not ready yet. Tracking [issues](https://github.com/Nothingness-Void/mc-agent-link/issues) for the first usable build.

## License

[Apache-2.0](LICENSE)

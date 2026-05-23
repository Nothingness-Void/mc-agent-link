#!/usr/bin/env node
import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
  type Tool,
} from "@modelcontextprotocol/sdk/types.js";
import { AgentLinkClient, AgentLinkError } from "./client.js";

const URL = process.env.AGENT_LINK_URL ?? "ws://127.0.0.1:25580";
const TOKEN = process.env.AGENT_LINK_TOKEN ?? "";

if (!TOKEN) {
  process.stderr.write(
    "agent-link-mcp: AGENT_LINK_TOKEN is required (set it in your MCP host config).\n",
  );
  process.exit(1);
}

const client = new AgentLinkClient({
  url: URL,
  token: TOKEN,
  reconnectMs: 5000,
  onLog: (lvl, msg) => process.stderr.write(`[agent-link ${lvl}] ${msg}\n`),
});

interface ToolDef {
  spec: Tool;
  /** Maps MCP args to agent-link tool name + payload. */
  toAgentLink: (args: Record<string, unknown>) => { tool: string; args: Record<string, unknown> };
}

const TOOLS: ToolDef[] = [
  {
    spec: {
      name: "ping",
      description: "Liveness probe. Returns the server's uptime in ms.",
      inputSchema: { type: "object", properties: {}, additionalProperties: false },
    },
    toAgentLink: () => ({ tool: "ping", args: {} }),
  },
  {
    spec: {
      name: "run_console_command",
      description:
        "Execute a server console command (op level 4). Returns the captured output. Leading slash is optional.",
      inputSchema: {
        type: "object",
        properties: { command: { type: "string", description: 'e.g. "list" or "/say hi"' } },
        required: ["command"],
        additionalProperties: false,
      },
    },
    toAgentLink: (a) => ({ tool: "run_console_command", args: { command: String(a.command ?? "") } }),
  },
  {
    spec: {
      name: "list_online_players",
      description: "List currently online players with name, UUID, ping, and dimension.",
      inputSchema: { type: "object", properties: {}, additionalProperties: false },
    },
    toAgentLink: () => ({ tool: "list_online_players", args: {} }),
  },
  {
    spec: {
      name: "get_player_info",
      description: "Detailed info for one online player: position, dimension, health, food, gamemode, ping.",
      inputSchema: {
        type: "object",
        properties: { name: { type: "string" } },
        required: ["name"],
        additionalProperties: false,
      },
    },
    toAgentLink: (a) => ({ tool: "get_player_info", args: { name: String(a.name ?? "") } }),
  },
  {
    spec: {
      name: "broadcast",
      description: "Send a system message to every online player.",
      inputSchema: {
        type: "object",
        properties: {
          message: { type: "string" },
          color: {
            type: "string",
            description: "Optional Minecraft chat color: red, yellow, gold, green, aqua, ...",
          },
        },
        required: ["message"],
        additionalProperties: false,
      },
    },
    toAgentLink: (a) => ({
      tool: "broadcast",
      args: { message: String(a.message ?? ""), color: a.color },
    }),
  },
  {
    spec: {
      name: "get_server_stats",
      description:
        "TPS, MSPT, memory used/max, loaded chunk count, and player count. Cheap to call.",
      inputSchema: { type: "object", properties: {}, additionalProperties: false },
    },
    toAgentLink: () => ({ tool: "get_server_stats", args: {} }),
  },
  {
    spec: {
      name: "get_recent_events",
      description:
        "Pull recent server events (chat, joins, leaves, deaths) on demand. " +
        "Use this when the user asks what's been happening on the server. " +
        "Default returns up to 50 most recent events. Pass `since_seq` to fetch only new events since a previous call's `head_seq`. " +
        "Pass `topics` to filter (e.g. just chat). The mod buffers ~1024 events; older ones are dropped.",
      inputSchema: {
        type: "object",
        properties: {
          since_seq: {
            type: "number",
            description:
              "Only return events with seq > this value. Use the previous call's head_seq for incremental polling. Omit or 0 to get the tail of recent events.",
          },
          limit: {
            type: "number",
            description: "Max events to return (default 50, hard cap 200).",
          },
          topics: {
            type: "array",
            items: { type: "string", enum: ["chat", "player_join", "player_leave", "player_death"] },
            description: "Optional filter. Omit to receive all topics.",
          },
        },
        additionalProperties: false,
      },
    },
    toAgentLink: (a) => {
      const args: Record<string, unknown> = {};
      if (typeof a.since_seq === "number") args.since_seq = a.since_seq;
      if (typeof a.limit === "number") args.limit = a.limit;
      if (Array.isArray(a.topics)) args.topics = a.topics;
      return { tool: "get_recent_events", args };
    },
  },
  {
    spec: {
      name: "get_recent_logs",
      description:
        "Pull recent server log lines (everything visible in the server console: /say output, mod logs, stack traces). " +
        "Use this when investigating crashes, errors, or unexpected behavior. " +
        "Default returns up to 100 most recent lines. Pass `since_seq` for incremental polling, " +
        "`levels` to filter by severity, or `contains` for substring search. The mod buffers ~2048 lines.",
      inputSchema: {
        type: "object",
        properties: {
          since_seq: {
            type: "number",
            description:
              "Only return logs with seq > this value. Use the previous call's head_seq for incremental polling. Omit or 0 for the tail.",
          },
          limit: {
            type: "number",
            description: "Max lines to return (default 100, hard cap 500).",
          },
          levels: {
            type: "array",
            items: { type: "string", enum: ["TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL"] },
            description: "Optional level filter. Case-insensitive on the mod side. Omit for all levels.",
          },
          contains: {
            type: "string",
            description: "Optional substring filter applied to the message.",
          },
        },
        additionalProperties: false,
      },
    },
    toAgentLink: (a) => {
      const args: Record<string, unknown> = {};
      if (typeof a.since_seq === "number") args.since_seq = a.since_seq;
      if (typeof a.limit === "number") args.limit = a.limit;
      if (Array.isArray(a.levels)) args.levels = a.levels;
      if (typeof a.contains === "string" && a.contains.length > 0) args.contains = a.contains;
      return { tool: "get_recent_logs", args };
    },
  },
  {
    spec: {
      name: "list_mods",
      description:
        "List installed mods (id, version, display name, description). " +
        "Use this when investigating crashes or compatibility issues — pair with " +
        "`read_server_file` on `crash-reports/*.txt` and `get_recent_logs`.",
      inputSchema: { type: "object", properties: {}, additionalProperties: false },
    },
    toAgentLink: () => ({ tool: "list_mods", args: {} }),
  },
  {
    spec: {
      name: "read_server_file",
      description:
        "Read a file under the server root (read-only). Use this for `crash-reports/*.txt`, " +
        "`logs/*.log`, mod config files, spark output, world settings, etc. " +
        "Paths are relative to the server root; absolute paths and `..` escapes are rejected. " +
        "Default cap is 256 KiB; set `max_bytes` up to 4 MiB. Returns base64 if the file is not valid UTF-8.",
      inputSchema: {
        type: "object",
        properties: {
          path: { type: "string", description: 'e.g. "crash-reports/crash-2025-01-15.txt"' },
          offset: { type: "number", description: "Byte offset to start reading from. Default 0." },
          max_bytes: { type: "number", description: "Max bytes to read. Default 262144, hard cap 4194304." },
        },
        required: ["path"],
        additionalProperties: false,
      },
    },
    toAgentLink: (a) => {
      const args: Record<string, unknown> = { path: String(a.path ?? "") };
      if (typeof a.offset === "number") args.offset = a.offset;
      if (typeof a.max_bytes === "number") args.max_bytes = a.max_bytes;
      return { tool: "read_server_file", args };
    },
  },
  {
    spec: {
      name: "list_dir",
      description:
        "List the contents of a directory under the server root. " +
        'Pass an empty string or "." to list the server root. ' +
        "Returns each entry's name, is_dir, size (for files), and mtime_ms. " +
        "Default cap is 500 entries.",
      inputSchema: {
        type: "object",
        properties: {
          path: { type: "string", description: 'Server-root-relative directory; e.g. "crash-reports", "config", or "" for root.' },
          max_entries: { type: "number", description: "Default 500, hard cap 5000." },
        },
        additionalProperties: false,
      },
    },
    toAgentLink: (a) => {
      const args: Record<string, unknown> = {};
      if (typeof a.path === "string") args.path = a.path;
      if (typeof a.max_entries === "number") args.max_entries = a.max_entries;
      return { tool: "list_dir", args };
    },
  },
  {
    spec: {
      name: "write_config_file",
      description:
        "Write a file under `config/` (the only writable area). " +
        "If the file already exists, the prior contents are auto-backed-up to " +
        "`config/.agent-link-backup/<name>.<timestamp>.bak` before being overwritten. " +
        "Set `overwrite: true` to replace an existing file. " +
        "Use this to tune mod configs after diagnosis — never to install jars or touch `mods/`.",
      inputSchema: {
        type: "object",
        properties: {
          path: { type: "string", description: 'Must start with "config/". e.g. "config/forge-common.toml".' },
          content: { type: "string", description: "File contents. UTF-8 string by default; pass base64 if `encoding: \"base64\"`." },
          encoding: { type: "string", enum: ["utf-8", "base64"], description: "Default utf-8." },
          overwrite: { type: "boolean", description: "Default false. Required true to replace an existing file." },
        },
        required: ["path", "content"],
        additionalProperties: false,
      },
    },
    toAgentLink: (a) => {
      const args: Record<string, unknown> = {
        path: String(a.path ?? ""),
        content: String(a.content ?? ""),
      };
      if (typeof a.encoding === "string") args.encoding = a.encoding;
      if (typeof a.overwrite === "boolean") args.overwrite = a.overwrite;
      return { tool: "write_config_file", args };
    },
  },
  {
    spec: {
      name: "tick_profile",
      description:
        "Tick-time distribution over the last ~100 ticks (avg / p50 / p95 / p99 / max in mspt, plus tps). " +
        "Use this when investigating lag — it reveals whether 19.5 TPS is steady-state or hides spikes. " +
        "Pair with `thread_dump` to find what's blocking the server thread, or with `/spark profiler` for sampled flame graphs.",
      inputSchema: { type: "object", properties: {}, additionalProperties: false },
    },
    toAgentLink: () => ({ tool: "tick_profile", args: {} }),
  },
  {
    spec: {
      name: "thread_dump",
      description:
        "JVM thread dump (name, state, top stack frames per thread). " +
        "Pair with `tick_profile` to see what `Server thread` is doing when mspt is high. " +
        "Default 30 frames per thread; set `only_server: true` to filter to threads with 'Server' in the name.",
      inputSchema: {
        type: "object",
        properties: {
          max_frames: { type: "number", description: "Frames per thread. Default 30, hard cap 200." },
          only_server: { type: "boolean", description: "Only include threads whose name contains 'Server'." },
        },
        additionalProperties: false,
      },
    },
    toAgentLink: (a) => {
      const args: Record<string, unknown> = {};
      if (typeof a.max_frames === "number") args.max_frames = a.max_frames;
      if (typeof a.only_server === "boolean") args.only_server = a.only_server;
      return { tool: "thread_dump", args };
    },
  },
];

const TOOL_BY_NAME = new Map(TOOLS.map((t) => [t.spec.name, t]));

const INSTRUCTIONS = `
You are connected to a running Minecraft server through agent-link.

# What you can do

Operate a Minecraft server like a remote operator: run console commands, read
files, inspect mods and live state, diagnose lag, and tune mod configs. The
server is real — actions like \`broadcast\`, \`run_console_command\`, and
\`write_config_file\` are visible to players or persist on disk.

# Tool groups

- **Operations**: \`ping\`, \`run_console_command\` (op level 4),
  \`list_online_players\`, \`get_player_info\`, \`broadcast\`,
  \`get_server_stats\`.
- **Observation (pull)**: \`get_recent_events\` for chat / join / leave / death,
  \`get_recent_logs\` for the full server console (stack traces included).
  Both use ring buffers and a \`since_seq\` cursor for incremental polling.
- **Diagnosis**: \`tick_profile\` (avg/p50/p95/p99/max mspt),
  \`thread_dump\` (JVM threads), \`list_mods\`.
- **Filesystem (sandboxed)**: \`list_dir\`, \`read_server_file\` (any path under
  the server root, read-only), \`write_config_file\` (writes ONLY under
  \`config/\`; existing files are auto-backed-up under
  \`config/.agent-link-backup/\`).

# Diagnosing lag — recommended loop

1. \`tick_profile\` — is the avg fine but p99 bad? That's a spike, not steady load.
2. \`thread_dump\` (try \`only_server: true\` first) — what was \`Server thread\`
   doing when sampled?
3. If the server has the \`spark\` mod installed, \`run_console_command\` with
   \`/spark profiler start\` → wait 20–60s → \`/spark profiler stop\`. The
   command output contains a URL; local profile dumps land in \`spark/\` and
   can be read via \`read_server_file\`. Without spark, rely on tick_profile +
   thread_dump.
4. \`list_mods\` to map a hot package or stack frame back to a mod id.
5. \`read_server_file\` on \`config/<that-mod>.toml\` to see its settings.
6. \`write_config_file\` to tune the value (auto-backed-up). Then ask the
   operator to \`/reload\` or restart — DO NOT restart on your own.

# Investigating a crash

Crash reports live under \`crash-reports/\`. Use \`list_dir { path: "crash-reports" }\`
then \`read_server_file\` on the newest \`.txt\`. Pair with \`list_mods\` and
\`get_recent_logs\` to identify the failing mod and the events leading up to it.

# Safety rules — please follow

- Writes are limited to \`config/\` by the server. Don't try to bypass; you'll
  just get \`INVALID_ARGS\`. Never assume you can write to \`mods/\`, \`world/\`,
  or anywhere else.
- \`run_console_command\` runs at op level 4. Treat it like a root shell: do
  not run \`stop\`, \`/op\`, \`/deop\`, \`/ban\`, or world-mutating commands
  (\`/fill\`, \`/kill @e\`) without explicit user confirmation.
- \`broadcast\` is visible to every online player. Use it sparingly.
- When you change a config, tell the user what you changed, where the backup
  is, and that a reload/restart is needed for it to take effect.
- Prefer reading and reporting first; act only when the user asks for an action.

# Performance

\`get_recent_events\` and \`get_recent_logs\` return \`head_seq\` — pass it as
\`since_seq\` next call to fetch only what's new. Don't refetch the full tail
every turn.
`.trim();

const server = new Server(
  { name: "agent-link", version: "0.1.0" },
  { capabilities: { tools: {} }, instructions: INSTRUCTIONS },
);

server.setRequestHandler(ListToolsRequestSchema, async () => ({
  tools: TOOLS.map((t) => t.spec),
}));

server.setRequestHandler(CallToolRequestSchema, async (req) => {
  const def = TOOL_BY_NAME.get(req.params.name);
  if (!def) {
    return {
      isError: true,
      content: [{ type: "text", text: `Unknown tool: ${req.params.name}` }],
    };
  }
  const { tool, args } = def.toAgentLink(req.params.arguments ?? {});
  try {
    const result = await client.call(tool, args);
    return {
      content: [{ type: "text", text: JSON.stringify(result, null, 2) }],
    };
  } catch (e) {
    const err = e as Error;
    const code = e instanceof AgentLinkError ? e.code : "INTERNAL_ERROR";
    return {
      isError: true,
      content: [{ type: "text", text: `[${code}] ${err.message}` }],
    };
  }
});

async function main() {
  await client.connect();
  process.stderr.write(`agent-link-mcp: connected to ${URL}\n`);
  const transport = new StdioServerTransport();
  await server.connect(transport);
}

main().catch((e) => {
  process.stderr.write(`agent-link-mcp fatal: ${(e as Error).message}\n`);
  process.exit(1);
});

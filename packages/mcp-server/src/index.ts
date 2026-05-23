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
];

const TOOL_BY_NAME = new Map(TOOLS.map((t) => [t.spec.name, t]));

const server = new Server(
  { name: "agent-link", version: "0.1.0" },
  { capabilities: { tools: {} } },
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

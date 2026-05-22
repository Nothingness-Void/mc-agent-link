import WebSocket from "ws";
import { randomUUID } from "node:crypto";

export interface ConnectOptions {
  url: string;
  token: string;
  /** Reconnect delay in ms when the socket drops. 0 disables auto-reconnect. */
  reconnectMs?: number;
  onEvent?: (topic: string, data: unknown, ts: number) => void;
  onLog?: (level: "info" | "warn" | "error", msg: string) => void;
}

interface PendingRequest {
  resolve: (result: unknown) => void;
  reject: (err: Error) => void;
  toolName: string;
  startedAt: number;
}

export interface ServerInfo {
  mc_version: string;
  loader: string;
  loader_version: string;
  agent_link_version: string;
}

export class AgentLinkClient {
  private ws?: WebSocket;
  private pending = new Map<string, PendingRequest>();
  private serverInfo?: ServerInfo;
  private connectingPromise?: Promise<void>;
  private closed = false;

  constructor(private readonly opts: ConnectOptions) {}

  async connect(): Promise<ServerInfo> {
    if (this.connectingPromise) await this.connectingPromise;
    if (this.serverInfo) return this.serverInfo;
    this.connectingPromise = this.openSocket();
    await this.connectingPromise;
    this.connectingPromise = undefined;
    return this.serverInfo!;
  }

  private openSocket(): Promise<void> {
    return new Promise((resolve, reject) => {
      const ws = new WebSocket(this.opts.url);
      this.ws = ws;
      let settled = false;

      ws.on("open", () => {
        ws.send(JSON.stringify({ v: 0, type: "hello", token: this.opts.token }));
      });

      ws.on("message", (raw) => {
        let frame: any;
        try {
          frame = JSON.parse(raw.toString());
        } catch {
          this.opts.onLog?.("warn", "non-JSON frame from server");
          return;
        }

        if (frame.type === "welcome") {
          this.serverInfo = frame.server;
          if (!settled) {
            settled = true;
            resolve();
          }
          return;
        }

        if (frame.type === "response") {
          const id = String(frame.id);
          const pending = this.pending.get(id);
          if (!pending) return;
          this.pending.delete(id);
          if (frame.ok) pending.resolve(frame.result);
          else pending.reject(new AgentLinkError(frame.error?.code ?? "INTERNAL_ERROR", frame.error?.message ?? ""));
          return;
        }

        if (frame.type === "event") {
          this.opts.onEvent?.(frame.topic, frame.data, frame.ts);
          return;
        }
      });

      ws.on("error", (err) => {
        this.opts.onLog?.("error", `socket error: ${err.message}`);
        if (!settled) {
          settled = true;
          reject(err);
        }
      });

      ws.on("close", (code, reason) => {
        const reasonStr = reason?.toString() ?? "";
        this.opts.onLog?.("warn", `socket closed (${code}) ${reasonStr}`);
        for (const [id, p] of this.pending.entries()) {
          p.reject(new AgentLinkError("DISCONNECTED", `socket closed: ${reasonStr || code}`));
          this.pending.delete(id);
        }
        if (!settled) {
          settled = true;
          reject(new AgentLinkError(code === 4401 ? "INVALID_TOKEN" : "DISCONNECTED", reasonStr || `closed ${code}`));
        }
        if (!this.closed && this.opts.reconnectMs && this.opts.reconnectMs > 0) {
          setTimeout(() => {
            this.serverInfo = undefined;
            this.connect().catch((e) =>
              this.opts.onLog?.("error", `reconnect failed: ${(e as Error).message}`),
            );
          }, this.opts.reconnectMs);
        }
      });
    });
  }

  close() {
    this.closed = true;
    this.ws?.close();
  }

  async call<T = unknown>(tool: string, args: Record<string, unknown> = {}): Promise<T> {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN || !this.serverInfo) {
      await this.connect();
    }
    const id = randomUUID();
    const frame = { v: 0, type: "request", id, tool, args };
    return new Promise<T>((resolve, reject) => {
      this.pending.set(id, {
        resolve: (r) => resolve(r as T),
        reject,
        toolName: tool,
        startedAt: Date.now(),
      });
      this.ws!.send(JSON.stringify(frame));
    });
  }
}

export class AgentLinkError extends Error {
  constructor(public readonly code: string, message: string) {
    super(`[${code}] ${message}`);
    this.name = "AgentLinkError";
  }
}

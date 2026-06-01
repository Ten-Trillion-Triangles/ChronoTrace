import type { IngestBatch } from "../generated/contracts.js";
import type { ChronoTransport, CommandHandler } from "../transport.js";

const INITIAL_RECONNECT_DELAY_MS = 1_000;
const MAX_RECONNECT_DELAY_MS = 30_000;
const RECONNECT_JITTER_MS = 250;

export interface WebSocketTransportOptions {
  readonly url: string;
  readonly webSocketFactory?: (url: string) => WebSocket;
  /** When false, suppress the auto-reconnect loop. Defaults to true. */
  readonly autoReconnect?: boolean;
}

export class WebSocketTransport implements ChronoTransport {
  private socket?: WebSocket;
  private commandHandler?: CommandHandler;
  private reconnectAttempts = 0;
  private reconnectTimer?: ReturnType<typeof setTimeout>;
  private closed = false;

  constructor(private readonly options: WebSocketTransportOptions) {}

  async connect(): Promise<void> {
    this.closed = false;
    await this.openSocket();
  }

  private async openSocket(): Promise<void> {
    const factory = this.options.webSocketFactory ?? ((url: string) => new WebSocket(url));
    this.socket = factory(this.options.url);
    this.socket.addEventListener("message", (event) => {
      if (!this.commandHandler) {
        return;
      }

      try {
        const parsed = JSON.parse(String(event.data));
        this.commandHandler(parsed);
      } catch {
        return;
      }
    });

    // Reconnect on unexpected close (server killed, network drop). Only schedule a
    // reconnect if the consumer hasn't called close() and autoReconnect is enabled.
    this.socket.addEventListener("close", () => {
      if (this.closed || this.options.autoReconnect === false) {
        return;
      }
      this.scheduleReconnect();
    });

    this.socket.addEventListener("error", () => {
      // Errors are followed by a close event; reconnect logic lives there.
    });

    if (this.socket.readyState === WebSocket.OPEN) {
      this.reconnectAttempts = 0;
      return;
    }

    await new Promise<void>((resolve, reject) => {
      if (!this.socket) {
        reject(new Error("WebSocket was not created"));
        return;
      }

      const onOpen = () => {
        this.socket?.removeEventListener("error", onError);
        this.reconnectAttempts = 0;
        resolve();
      };
      const onError = () => {
        this.socket?.removeEventListener("open", onOpen);
        reject(new Error("WebSocket connection failed"));
      };

      this.socket.addEventListener("open", onOpen, { once: true });
      this.socket.addEventListener("error", onError, { once: true });
    });
  }

  private scheduleReconnect(): void {
    if (this.closed || this.reconnectTimer) {
      return;
    }
    // Exponential backoff: 1s, 2s, 4s, 8s, … capped at 30s, with up to 250ms jitter.
    const base = Math.min(
      INITIAL_RECONNECT_DELAY_MS * Math.pow(2, this.reconnectAttempts),
      MAX_RECONNECT_DELAY_MS,
    );
    const jitter = Math.random() * RECONNECT_JITTER_MS;
    const delayMs = base + jitter;
    this.reconnectAttempts++;
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = undefined;
      this.openSocket().catch(() => {
        // openSocket rejects when the connection fails; the close-event listener
        // will reschedule the next attempt.
      });
    }, delayMs);
  }

  async send(batch: IngestBatch): Promise<void> {
    if (!this.socket || this.socket.readyState !== WebSocket.OPEN) {
      throw new Error("ChronoTrace WebSocket transport is not connected");
    }
    this.socket.send(JSON.stringify(batch));
  }

  async close(): Promise<void> {
    this.closed = true;
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = undefined;
    }
    this.socket?.close();
  }

  isConnected(): boolean {
    return this.socket?.readyState === WebSocket.OPEN;
  }

  setCommandHandler(handler: CommandHandler): void {
    this.commandHandler = handler;
  }
}

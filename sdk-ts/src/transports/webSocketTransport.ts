import type { IngestBatch } from "../generated/contracts.js";
import type { ChronoTransport, CommandHandler } from "../transport.js";

export interface WebSocketTransportOptions {
  readonly url: string;
  readonly webSocketFactory?: (url: string) => WebSocket;
}

export class WebSocketTransport implements ChronoTransport {
  private socket?: WebSocket;
  private commandHandler?: CommandHandler;

  constructor(private readonly options: WebSocketTransportOptions) {}

  async connect(): Promise<void> {
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

    if (this.socket.readyState === WebSocket.OPEN) {
      return;
    }

    await new Promise<void>((resolve, reject) => {
      if (!this.socket) {
        reject(new Error("WebSocket was not created"));
        return;
      }

      this.socket.addEventListener("open", () => resolve(), { once: true });
      this.socket.addEventListener("error", () => reject(new Error("WebSocket connection failed")), {
        once: true
      });
    });
  }

  async send(batch: IngestBatch): Promise<void> {
    if (!this.socket || this.socket.readyState !== WebSocket.OPEN) {
      throw new Error("ChronoTrace WebSocket transport is not connected");
    }
    this.socket.send(JSON.stringify(batch));
  }

  async close(): Promise<void> {
    this.socket?.close();
  }

  isConnected(): boolean {
    return this.socket?.readyState === WebSocket.OPEN;
  }

  setCommandHandler(handler: CommandHandler): void {
    this.commandHandler = handler;
  }
}

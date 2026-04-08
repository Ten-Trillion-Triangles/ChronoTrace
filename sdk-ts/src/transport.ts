import type { IngestBatch, RemoteRule } from "./generated/contracts.js";

export interface RemoteCommand {
  type: "upsert_rule" | "delete_rule";
  rule?: RemoteRule;
  ruleId?: string;
}

export type CommandHandler = (command: RemoteCommand) => void;

export interface ChronoTransport {
  connect(): Promise<void>;
  send(batch: IngestBatch): Promise<void>;
  close(): Promise<void>;
  isConnected(): boolean;
  setCommandHandler?(handler: CommandHandler): void;
}

export class NoopTransport implements ChronoTransport {
  async connect(): Promise<void> {}
  async send(_batch: IngestBatch): Promise<void> {}
  async close(): Promise<void> {}
  isConnected(): boolean {
    return true;
  }
}

export class RecordingTransport implements ChronoTransport {
  private readonly sent: IngestBatch[] = [];

  async connect(): Promise<void> {}

  async send(batch: IngestBatch): Promise<void> {
    this.sent.push(batch);
  }

  async close(): Promise<void> {}

  isConnected(): boolean {
    return true;
  }

  batches(): IngestBatch[] {
    return [...this.sent];
  }
}

export { HttpTransport } from "./transports/httpTransport.js";
export { WebSocketTransport } from "./transports/webSocketTransport.js";


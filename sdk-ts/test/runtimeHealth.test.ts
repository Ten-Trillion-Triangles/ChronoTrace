import { describe, expect, it } from "vitest";
import {
  ChronoTrace,
  type ChronoTransport,
  createNodeChronoTrace,
  type IngestBatch,
} from "../src/index.js";

class FlakyTransport implements ChronoTransport {
  private remainingFailures: number;
  private readonly sent: IngestBatch[] = [];

  constructor(failures: number) {
    this.remainingFailures = failures;
  }

  async connect(): Promise<void> {}

  async send(batch: IngestBatch): Promise<void> {
    if (this.remainingFailures > 0) {
      this.remainingFailures -= 1;
      throw new Error("transport unavailable");
    }
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

class ProcessEmitter {
  private readonly listeners = new Map<string, Set<(...args: unknown[]) => void>>();

  on(event: "uncaughtException", listener: (error: Error) => void): void;
  on(event: "unhandledRejection", listener: (reason: unknown, promise: Promise<unknown>) => void): void;
  on(
    event: "uncaughtException" | "unhandledRejection",
    listener: ((error: Error) => void) | ((reason: unknown, promise: Promise<unknown>) => void),
  ): void {
    const bucket = this.listeners.get(event) ?? new Set();
    bucket.add(listener as (...args: unknown[]) => void);
    this.listeners.set(event, bucket);
  }

  off(event: "uncaughtException", listener: (error: Error) => void): void;
  off(event: "unhandledRejection", listener: (reason: unknown, promise: Promise<unknown>) => void): void;
  off(
    event: "uncaughtException" | "unhandledRejection",
    listener: ((error: Error) => void) | ((reason: unknown, promise: Promise<unknown>) => void),
  ): void {
    this.listeners.get(event)?.delete(listener as (...args: unknown[]) => void);
  }

  emit(event: string, ...args: unknown[]): void {
    for (const listener of this.listeners.get(event) ?? []) {
      listener(...args);
    }
  }

  listenerCount(event: string): number {
    return this.listeners.get(event)?.size ?? 0;
  }
}

describe("ChronoTrace runtime health", () => {
  it("preserves buffered logs after a failed flush and reports state transitions", async () => {
    const transport = new FlakyTransport(1);
    const client = createNodeChronoTrace({
      appId: "payments",
      transport,
    });

    await client.init();
    await client.info("first failure");

    expect(client.getRuntimeHealth().state).toBe("DEGRADED_BUFFERING");
    expect(client.getRuntimeHealth().bufferedLogs).toBe(1);

    await client.info("second success");

    expect(client.getRuntimeHealth().state).toBe("CONNECTED");
    expect(client.getRuntimeHealth().bufferedLogs).toBe(0);
    expect(transport.batches().flatMap((batch) => batch.logs)).toHaveLength(2);

    await client.shutdown();
  });

  it("registers node fatal hooks and flushes on fatal events", async () => {
    const transport = new FlakyTransport(0);
    const processLike = new ProcessEmitter();
    const client = createNodeChronoTrace({
      appId: "payments",
      transport,
      nodeProcess: processLike,
    });

    await client.init();
    expect(processLike.listenerCount("uncaughtException")).toBe(1);
    expect(processLike.listenerCount("unhandledRejection")).toBe(1);

    processLike.emit("uncaughtException", new Error("boom"));
    await Promise.resolve();

    expect(client.getRuntimeHealth().fatalFlushes).toBe(1);

    await client.shutdown();

    expect(processLike.listenerCount("uncaughtException")).toBe(0);
    expect(processLike.listenerCount("unhandledRejection")).toBe(0);
  });

  it("exposes runtime health through the facade", async () => {
    ChronoTrace.init({
      appId: "payments",
      transport: new FlakyTransport(0),
    });

    expect(ChronoTrace.runtimeHealth().state).toBe("RECONNECT_BACKOFF");

    await ChronoTrace.shutdown();
  });
});

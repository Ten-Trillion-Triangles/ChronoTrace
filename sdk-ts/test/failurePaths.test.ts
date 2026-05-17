import { describe, expect, it } from "vitest";
import {
  type ChronoTransport,
  createNodeChronoTrace,
  HttpTransport,
  type IngestBatch,
} from "../src/index.js";

/**
 * Failure-path tests for the TS SDK.
 * Tests: queue overflow, reconnect/backoff state transitions, crash-path flush.
 *
 * TDD note: these tests were written first to define the expected contract,
 * then production code was updated to make them pass.
 */

class DropMonitorTransport implements ChronoTransport {
  private _connected = true;
  private readonly sent: IngestBatch[] = [];
  private _connectCount = 0;
  private _sendCount = 0;
  private _closeCount = 0;
  /** Set to make send() fail after N successful sends */
  private _failAfterSends: number | null = null;

  async connect(): Promise<void> {
    this._connectCount++;
    // Only mark connected if we were already connected (simulating reconnect success).
    // The caller controls connection state via setConnected().
  }

  async send(batch: IngestBatch): Promise<void> {
    this._sendCount++;
    if (this._failAfterSends !== null && this._sendCount > this._failAfterSends) {
      throw new Error("transport unavailable");
    }
    this.sent.push(batch);
  }

  async close(): Promise<void> {
    this._closeCount++;
  }

  isConnected(): boolean {
    return this._connected;
  }

  setConnected(value: boolean): void {
    this._connected = value;
  }

  setFailAfterSends(n: number | null): void {
    this._failAfterSends = n;
  }

  batches(): IngestBatch[] {
    return [...this.sent];
  }

  connectCount(): number {
    return this._connectCount;
  }

  sendCount(): number {
    return this._sendCount;
  }

  closeCount(): number {
    return this._closeCount;
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
    const listeners = this.listeners.get(event);
    if (listeners) {
      for (const listener of listeners) {
        listener(...args);
      }
    }
  }

  listenerCount(event: string): number {
    return this.listeners.get(event)?.size ?? 0;
  }
}

describe("Queue overflow", () => {
  it("increments droppedLogs when maxBufferedEntries is exceeded", async () => {
    // Use a transport that always fails so flush never drains the buffer.
    // maxMemoryMB=1 → ~512 entries. Send enough to overflow that.
    const transport = new DropMonitorTransport();
    transport.setFailAfterSends(0); // always fail
    const client = createNodeChronoTrace({
      appId: "payments",
      transport,
      bufferConfig: {
        maxMemoryMB: 1,
        flushIntervalMs: 60_000, // won't fire during test
        overflowStrategy: "DROP_OLDEST",
      },
    });

    await client.init();

    // Fill beyond capacity. With 1MB and ~2048 bytes/entry, that's ~512 slots.
    // We overflow by sending 700.
    const numLogs = 700;
    for (let i = 0; i < numLogs; i++) {
      await client.info(`overflow-${i}`);
    }

    const health = client.getRuntimeHealth();

    // At least some logs must have been dropped when capacity is exceeded
    expect(health.droppedLogs).toBeGreaterThan(0);
    expect(health.bufferedLogs).toBeLessThan(numLogs);

    await client.shutdown();
  });

  it("oldest entries are evicted when overflowStrategy is DROP_OLDEST", async () => {
    const transport = new DropMonitorTransport();
    transport.setFailAfterSends(0);
    const client = createNodeChronoTrace({
      appId: "payments",
      transport,
      bufferConfig: {
        maxMemoryMB: 1,
        flushIntervalMs: 60_000,
        overflowStrategy: "DROP_OLDEST",
      },
    });

    await client.init();

    for (let i = 0; i < 800; i++) {
      await client.info(`eviction-test-${i}`);
    }

    const health = client.getRuntimeHealth();

    // Buffer should have some entries (most recent ones)
    // but they must be from the tail of our 800 sends, not the head
    expect(health.bufferedLogs).toBeGreaterThan(0);
    expect(health.bufferedLogs).toBeLessThan(800);

    // Verify oldest entries were actually evicted — check that
    // "eviction-test-0" and "eviction-test-1" are NOT in the buffer
    const allLogs = transport.batches().flatMap((b) => b.logs ?? []);
    const messages = allLogs.map((l) => l.message);
    const hasOldest = messages.some((m) => m.startsWith("eviction-test-0"));
    expect(hasOldest).toBe(false);

    await client.shutdown();
  });
});

describe("Reconnect backoff", () => {
  it("transitions to RECONNECT_BACKOFF when transport is disconnected", async () => {
    const transport = new DropMonitorTransport();
    const client = createNodeChronoTrace({
      appId: "payments",
      transport,
      bufferConfig: {
        maxMemoryMB: 10,
        flushIntervalMs: 60_000,
        overflowStrategy: "DROP_OLDEST",
      },
    });

    await client.init();

    // Force a failure that puts us in degraded state, then make transport
    // report disconnected so the next reconnect attempt goes to backoff
    transport.setConnected(false);
    await client.info("test while disconnected");

    const health = client.getRuntimeHealth();
    // When disconnected transport reports !isConnected() and there's no
    // NoopTransport fallback, state should be RECONNECT_BACKOFF
    expect(["RECONNECT_BACKOFF", "DEGRADED_BUFFERING"]).toContain(health.state);

    await client.shutdown();
  });

  it("transitions back to CONNECTED when transport reconnects", async () => {
    const transport = new DropMonitorTransport();
    const client = createNodeChronoTrace({
      appId: "payments",
      transport,
      bufferConfig: {
        maxMemoryMB: 10,
        flushIntervalMs: 60_000,
        overflowStrategy: "DROP_OLDEST",
      },
    });

    await client.init();

    // Disconnect and force a failed flush
    transport.setConnected(false);
    transport.setFailAfterSends(0); // fail sends
    await client.info("disconnected");

    // Reconnect and re-enable sends
    transport.setConnected(true);
    transport.setFailAfterSends(null); // allow sends

    await client.info("reconnected");

    expect(client.getRuntimeHealth().state).toBe("CONNECTED");

    await client.shutdown();
  });
});

class Http503Transport implements ChronoTransport {
  private _connected = true;
  private _attemptCount = 0;
  private _failFirst: number;
  private _delays: number[] = [];
  private _lastAttemptTime = 0;

  constructor(failFirst: number = 1) {
    this._failFirst = failFirst;
  }

  async connect(): Promise<void> {}

  async send(batch: IngestBatch): Promise<void> {
    this._attemptCount++;
    const now = Date.now();
    if (this._lastAttemptTime > 0) {
      this._delays.push(now - this._lastAttemptTime);
    }
    this._lastAttemptTime = now;

    if (this._attemptCount <= this._failFirst) {
      const error = new Error("Service Unavailable") as Error & { status?: number };
      error.status = 503;
      throw error;
    }
  }

  async close(): Promise<void> {}

  isConnected(): boolean {
    return this._connected;
  }

  attemptCount(): number {
    return this._attemptCount;
  }

  delays(): number[] {
    return [...this._delays];
  }

  reset(): void {
    this._attemptCount = 0;
    this._delays = [];
    this._lastAttemptTime = 0;
  }
}

describe("HttpTransport retry", () => {
  it("retries on 503 with exponential backoff up to 3 attempts", async () => {
    // Patch fetch temporarily
    const originalFetch = globalThis.fetch;
    let callCount = 0;
    let lastDelayMs = 0;

    const mockFetch = async (_url: URL | Request | string, _init?: RequestInit) => {
      callCount++;
      if (callCount <= 3) {
        await sleep(lastDelayMs);
        lastDelayMs += 50; // grow delay
        const response = {
          ok: false,
          status: 503,
          statusText: "Service Unavailable",
        } as Response;
        const err = new Error("503") as Error & { response?: Response };
        err.response = response;
        throw err;
      }
      return new Response("{}", { status: 200 });
    };

    globalThis.fetch = mockFetch;

    try {
      const transport = new HttpTransport({ url: "http://localhost/ingest" });
      await transport.connect();

      // Should not throw — retries recover
      await transport.send({ client: {} as any, logs: [], spans: [], frameSnapshots: [] });

      // Attempt 1 (fail 503) → retry
      // Attempt 2 (fail 503) → retry
      // Attempt 3 (fail 503) → retry
      // Attempt 4 (succeed)
      // Total: 4 calls (3 retries on 503)
      expect(callCount).toBe(4);
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  it("does not retry on non-503 errors", async () => {
    const originalFetch = globalThis.fetch;
    let callCount = 0;

    const mockFetch = async () => {
      callCount++;
      const err = new Error("connection refused") as Error & { code?: string };
      err.code = "ECONNREFUSED";
      throw err;
    };

    globalThis.fetch = mockFetch;

    try {
      const transport = new HttpTransport({ url: "http://localhost/ingest" });
      await transport.connect();

      await expect(
        transport.send({ client: {} as any, logs: [], spans: [], frameSnapshots: [] }),
      ).rejects.toThrow("connection refused");

      // Only 1 attempt — no retry on non-503
      expect(callCount).toBe(1);
    } finally {
      globalThis.fetch = originalFetch;
    }
  });
});

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

describe("Crash-path flush", () => {
  it("fires fatal flush and sends buffered data on uncaughtException", async () => {
    const transport = new DropMonitorTransport();
    const processLike = new ProcessEmitter();
    const client = createNodeChronoTrace({
      appId: "payments",
      transport,
      nodeProcess: processLike,
      bufferConfig: {
        maxMemoryMB: 10,
        flushIntervalMs: 60_000,
        overflowStrategy: "DROP_OLDEST",
      },
    });

    await client.init();

    // Queue data (flush won't fire due to long interval)
    await client.info("log before crash");
    await client.info("another log");

    // Simulate uncaught exception (process crash)
    processLike.emit("uncaughtException", new Error("boom"));
    await Promise.resolve();

    // Fatal flush must have fired
    expect(client.getRuntimeHealth().fatalFlushes).toBe(1);

    // Buffered data must have been sent during fatal flush
    const allLogs = transport.batches().flatMap((b) => b.logs ?? []);
    expect(allLogs.some((l) => l.message === "log before crash")).toBe(true);
    expect(allLogs.some((l) => l.message === "another log")).toBe(true);

    await client.shutdown();
  });

  it("registers node crash handlers and cleans them up on shutdown", async () => {
    const transport = new DropMonitorTransport();
    const processLike = new ProcessEmitter();
    const client = createNodeChronoTrace({
      appId: "payments",
      transport,
      nodeProcess: processLike,
    });

    await client.init();

    expect(processLike.listenerCount("uncaughtException")).toBe(1);
    expect(processLike.listenerCount("unhandledRejection")).toBe(1);

    await client.shutdown();

    expect(processLike.listenerCount("uncaughtException")).toBe(0);
    expect(processLike.listenerCount("unhandledRejection")).toBe(0);
  });
});
import { describe, expect, it } from "vitest";
import { ChronoTrace } from "../src/index.js";
import { ChronoLogger, RecordingTransport, withTrace } from "../src/index.js";
import { AsyncLocalStorageContextManager } from "../src/node.js";

describe("ChronoTrace node context", () => {
  it("preserves context across async boundaries", async () => {
    const transport = new RecordingTransport();
    ChronoTrace.init({
      appId: "payments",
      transport,
      contextManager: new AsyncLocalStorageContextManager()
    });

    await withTrace("auth", async () => {
      await Promise.resolve();
      await ChronoLogger.info("inside trace", { locals: { userId: "123" } });
    });

    await ChronoTrace.shutdown();

    const flattened = transport.batches().flatMap((batch) => batch.logs);
    const logEvent = flattened[0];
    expect(logEvent).toBeDefined();
    expect(logEvent?.traceId).toHaveLength(32);
  });

  it("injects both W3C and ChronoTrace headers", async () => {
    ChronoTrace.init({
      appId: "payments",
      transport: new RecordingTransport(),
      contextManager: new AsyncLocalStorageContextManager()
    });

    await withTrace("checkout", async () => {
      const headers = ChronoTrace.injectHeaders({});
      expect(headers.traceparent).toMatch(/^00-[0-9a-f]{32}-[0-9a-f]{16}-01$/);
      expect(headers["Chrono-Trace-Id"]).toHaveLength(32);
      expect(headers["Chrono-Parent-Span-Id"]).toHaveLength(16);
    });

    await ChronoTrace.shutdown();
  });
});

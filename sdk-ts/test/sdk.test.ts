import { describe, expect, it } from "vitest";
import {
  ChronoLogger,
  ChronoTrace,
  RecordingTransport,
  parseRuleExpression,
  evaluateRule,
  withSpan,
  withTrace,
} from "../src/index";

describe("ChronoTrace TS SDK", () => {
  it("records nested traces and redacts sensitive fields", async () => {
    const transport = new RecordingTransport();
    ChronoTrace.init({
      appId: "payments",
      transport,
    });

    await withTrace("root", async () => {
      await ChronoLogger.info("starting");
      await withSpan("child", async () => {
        await ChronoLogger.error("boom", { password: "secret" });
      });
    });
    await ChronoTrace.shutdown();

    const logs = transport.batches().flatMap((batch) => batch.logs ?? []);
    const spans = transport.batches().flatMap((batch) => batch.spans ?? []);
    const frames = transport.batches().flatMap((batch) => batch.frameSnapshots ?? []);
    const errorLog = logs.find((log) => log.level === "ERROR");
    const firstFrame = frames[0];

    expect(logs.some((log) => log.fields?.password === "[REDACTED]")).toBe(true);
    expect(new Set(logs.map((log) => log.traceId)).size).toBe(1);
    expect(spans.length).toBeGreaterThanOrEqual(2);
    expect(frames).toHaveLength(1);
    expect(errorLog).toBeDefined();
    expect(firstFrame).toBeDefined();
    expect(firstFrame?.logId).toBe(errorLog?.logId);
    expect(firstFrame?.localsJson ?? "").toContain("password");
    expect(firstFrame?.serializationMetadata?.redactedFields ?? []).toContain("password");
  });

  it("evaluates basic remote rule expressions", () => {
    expect(
      evaluateRule(parseRuleExpression("locals.user_id == '123' AND metadata.appId == 'payments'"), {
        locals: { user_id: "123" },
        metadata: { appId: "payments" },
      }),
    ).toBe(true);
  });

  it("captures a manual trace snapshot when capture locals are provided", async () => {
    const transport = new RecordingTransport();
    ChronoTrace.init({
      appId: "payments",
      transport,
    });

    const userId = "user-42";
    await withTrace("checkout", async () => {
      await ChronoLogger.info("inside-manual-trace");
    }, { captureLocals: { userId } });
    await ChronoTrace.shutdown();

    const frames = transport.batches().flatMap((batch) => batch.frameSnapshots ?? []);
    const firstFrame = frames[0];
    expect(frames.some((frame) => frame?.captureReason === "manual_trace")).toBe(true);
    expect(firstFrame).toBeDefined();
    expect(firstFrame?.localsJson ?? "").toContain(userId);
  });
});

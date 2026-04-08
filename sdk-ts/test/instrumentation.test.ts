import { describe, expect, it } from "vitest";
import { instrumentSource } from "../src/instrumentation.js";

describe("instrumentSource", () => {
  it("injects hidden locals into logger calls", () => {
    const input = `
      async function run(userId: string) {
        const password = "secret";
        await ChronoLogger.error("boom");
      }
    `;

    const output = instrumentSource(input, "example.ts");

    expect(output).toContain("__chronotrace_locals");
    expect(output).toContain("password");
    expect(output).toContain("userId");
  });

  it("injects capture locals into trace/span helpers", () => {
    const input = `
      async function run(cartId: string) {
        await withTrace("checkout", async () => {
          await withSpan("validate", async () => {});
        });
      }
    `;

    const output = instrumentSource(input, "trace.ts");

    expect(output).toContain("captureLocals");
    expect(output).toContain("cartId");
  });
});

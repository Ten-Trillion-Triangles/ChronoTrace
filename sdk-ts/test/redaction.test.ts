import { describe, expect, it } from "vitest";
import { defaultCaptureConfig } from "../src/config.js";
import { sanitizeFields } from "../src/redaction.js";

describe("sanitizeFields", () => {
  it("masks denied and matching secret values", () => {
    const result = sanitizeFields(
      {
        password: "hunter2",
        token: "abc",
        nested: {
          secret: "sk_123456"
        }
      },
      defaultCaptureConfig
    );

    expect(result).toEqual({
      password: "[REDACTED]",
      token: "[REDACTED]",
      nested: {
        secret: "[REDACTED]"
      }
    });
  });

  it("redacts via deny field patterns and truncates cycles", () => {
    const circular: Record<string, unknown> = { secretValue: "safe" };
    circular.self = circular;

    const result = sanitizeFields(
      circular,
      {
        ...defaultCaptureConfig,
        denyFieldPatterns: [/secret/i]
      }
    );

    expect(result).toEqual({
      secretValue: "[REDACTED]",
      self: "[Circular]"
    });
  });
});

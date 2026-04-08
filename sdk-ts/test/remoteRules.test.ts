import { describe, expect, it } from "vitest";
import { evaluateRule, parseRuleExpression } from "../src/remoteRules.js";

describe("remote rule parsing", () => {
  it("parses and evaluates logical expressions", () => {
    const rule = parseRuleExpression("locals.userId == '123' AND locals.region startsWith 'us-'");
    const result = evaluateRule(rule, {
      locals: {
        userId: "123",
        region: "us-east-1"
      }
    });

    expect(result).toBe(true);
  });

  it("supports NOT and regex matches", () => {
    const rule = parseRuleExpression("NOT locals.email matches '.*@example.com'");
    const result = evaluateRule(rule, {
      locals: {
        email: "user@other.com"
      }
    });

    expect(result).toBe(true);
  });
});

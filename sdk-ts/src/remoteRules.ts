export type RuleComparisonOperator =
  | "=="
  | "!="
  | "<"
  | "<="
  | ">"
  | ">="
  | "contains"
  | "startsWith"
  | "endsWith"
  | "matches"
  | "in";

export type { RemoteRule } from "./generated/contracts.js";

export type RuleAstNode =
  | { type: "identifier"; path: string[] }
  | { type: "literal"; value: string | number | boolean | null }
  | { type: "comparison"; operator: RuleComparisonOperator; left: RuleAstNode; right: RuleAstNode }
  | { type: "logical"; operator: "AND" | "OR"; left: RuleAstNode; right: RuleAstNode }
  | { type: "not"; value: RuleAstNode };

interface Token {
  kind: "identifier" | "string" | "number" | "boolean" | "null" | "operator" | "paren";
  value: string;
}

const operatorTokens = [
  "==",
  "!=",
  "<=",
  ">=",
  "<",
  ">",
  "contains",
  "startsWith",
  "endsWith",
  "matches",
  "in",
  "AND",
  "OR",
  "NOT"
];

function tokenize(expression: string): Token[] {
  const tokens: Token[] = [];
  let cursor = 0;

  while (cursor < expression.length) {
    const character = expression[cursor];

    if (/\s/.test(character)) {
      cursor += 1;
      continue;
    }

    if (character === "(" || character === ")") {
      tokens.push({ kind: "paren", value: character });
      cursor += 1;
      continue;
    }

    if (character === "'" || character === "\"") {
      const quote = character;
      cursor += 1;
      let value = "";
      while (cursor < expression.length && expression[cursor] !== quote) {
        value += expression[cursor];
        cursor += 1;
      }
      cursor += 1;
      tokens.push({ kind: "string", value });
      continue;
    }

    const operator = operatorTokens.find((candidate) => expression.startsWith(candidate, cursor));
    if (operator) {
      tokens.push({ kind: "operator", value: operator });
      cursor += operator.length;
      continue;
    }

    const numberMatch = expression.slice(cursor).match(/^\d+(\.\d+)?/);
    if (numberMatch) {
      tokens.push({ kind: "number", value: numberMatch[0] });
      cursor += numberMatch[0].length;
      continue;
    }

    const identifierMatch = expression.slice(cursor).match(/^[A-Za-z_][A-Za-z0-9_.]*/);
    if (!identifierMatch) {
      throw new Error(`Unexpected token near "${expression.slice(cursor)}"`);
    }

    const identifier = identifierMatch[0];
    if (identifier === "true" || identifier === "false") {
      tokens.push({ kind: "boolean", value: identifier });
    } else if (identifier === "null") {
      tokens.push({ kind: "null", value: identifier });
    } else {
      tokens.push({ kind: "identifier", value: identifier });
    }
    cursor += identifier.length;
  }

  return tokens;
}

class RuleParser {
  private cursor = 0;

  constructor(private readonly tokens: Token[]) {}

  parse(): RuleAstNode {
    const node = this.parseOr();
    if (this.cursor !== this.tokens.length) {
      throw new Error("Unexpected trailing tokens");
    }
    return node;
  }

  private parseOr(): RuleAstNode {
    let node = this.parseAnd();
    while (this.matchOperator("OR")) {
      node = {
        type: "logical",
        operator: "OR",
        left: node,
        right: this.parseAnd()
      };
    }
    return node;
  }

  private parseAnd(): RuleAstNode {
    let node = this.parseUnary();
    while (this.matchOperator("AND")) {
      node = {
        type: "logical",
        operator: "AND",
        left: node,
        right: this.parseUnary()
      };
    }
    return node;
  }

  private parseUnary(): RuleAstNode {
    if (this.matchOperator("NOT")) {
      return {
        type: "not",
        value: this.parseUnary()
      };
    }

    if (this.matchParen("(")) {
      const value = this.parseOr();
      this.expectParen(")");
      return value;
    }

    return this.parseComparison();
  }

  private parseComparison(): RuleAstNode {
    const left = this.parseValue();
    const operator = this.consume();
    if (!operator || operator.kind !== "operator" || !operatorTokens.includes(operator.value)) {
      throw new Error("Expected comparison operator");
    }

    if (operator.value === "AND" || operator.value === "OR" || operator.value === "NOT") {
      throw new Error("Expected comparison operator");
    }

    return {
      type: "comparison",
      operator: operator.value as RuleComparisonOperator,
      left,
      right: this.parseValue()
    };
  }

  private parseValue(): RuleAstNode {
    const token = this.consume();
    if (!token) {
      throw new Error("Unexpected end of expression");
    }

    switch (token.kind) {
      case "identifier":
        return { type: "identifier", path: token.value.split(".") };
      case "string":
        return { type: "literal", value: token.value };
      case "number":
        return { type: "literal", value: Number(token.value) };
      case "boolean":
        return { type: "literal", value: token.value === "true" };
      case "null":
        return { type: "literal", value: null };
      default:
        throw new Error("Unexpected token type in value position");
    }
  }

  private consume(): Token | undefined {
    const token = this.tokens[this.cursor];
    this.cursor += 1;
    return token;
  }

  private matchOperator(operator: string): boolean {
    const token = this.tokens[this.cursor];
    if (token?.kind === "operator" && token.value === operator) {
      this.cursor += 1;
      return true;
    }
    return false;
  }

  private matchParen(paren: string): boolean {
    const token = this.tokens[this.cursor];
    if (token?.kind === "paren" && token.value === paren) {
      this.cursor += 1;
      return true;
    }
    return false;
  }

  private expectParen(paren: string): void {
    if (!this.matchParen(paren)) {
      throw new Error(`Expected ${paren}`);
    }
  }
}

function resolveValue(node: RuleAstNode, payload: Record<string, unknown>): unknown {
  switch (node.type) {
    case "literal":
      return node.value;
    case "identifier":
      return node.path.reduce<unknown>((current, part) => {
        if (!current || typeof current !== "object") {
          return undefined;
        }
        return (current as Record<string, unknown>)[part];
      }, payload);
    default:
      return evaluateRule(node, payload);
  }
}

function compareValues(operator: RuleComparisonOperator, left: unknown, right: unknown): boolean {
  switch (operator) {
    case "==":
      return left === right;
    case "!=":
      return left !== right;
    case "<":
      return Number(left) < Number(right);
    case "<=":
      return Number(left) <= Number(right);
    case ">":
      return Number(left) > Number(right);
    case ">=":
      return Number(left) >= Number(right);
    case "contains":
      return String(left).includes(String(right));
    case "startsWith":
      return String(left).startsWith(String(right));
    case "endsWith":
      return String(left).endsWith(String(right));
    case "matches":
      return new RegExp(String(right)).test(String(left));
    case "in":
      return Array.isArray(right) ? right.includes(left) : String(right).split(",").includes(String(left));
    default:
      return false;
  }
}

export function parseRuleExpression(expression: string): RuleAstNode {
  return new RuleParser(tokenize(expression)).parse();
}

export function evaluateRule(node: RuleAstNode, payload: Record<string, unknown>): boolean {
  switch (node.type) {
    case "comparison":
      return compareValues(
        node.operator,
        resolveValue(node.left, payload),
        resolveValue(node.right, payload)
      );
    case "logical":
      return node.operator === "AND"
        ? evaluateRule(node.left, payload) && evaluateRule(node.right, payload)
        : evaluateRule(node.left, payload) || evaluateRule(node.right, payload);
    case "not":
      return !evaluateRule(node.value, payload);
    case "identifier":
    case "literal":
      return Boolean(resolveValue(node, payload));
    default:
      return false;
  }
}

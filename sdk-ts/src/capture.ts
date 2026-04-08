import type {
  CallStackItem,
  CaptureReason,
  FrameSnapshot,
  SerializationMetadata,
} from "./generated/contracts.js";
import type { CaptureConfig } from "./config.js";
import type { TraceContext } from "./context.js";

export const INTERNAL_CAPTURE_LOCALS_KEY = "__chronotrace_locals";
const TRUNCATED_MARKER = "[Truncated]";
const CIRCULAR_MARKER = "[Circular]";
const REDACTED_MARKER = "[REDACTED]";
const UNDEFINED_MARKER = "[Undefined]";
const PROMISE_MARKER = "[Promise]";

interface SerializationState {
  truncated: boolean;
  maxDepthReached: boolean;
  redactedFields: Set<string>;
  droppedFields: Set<string>;
}

export interface CapturePayload {
  readonly frameId: string;
  readonly appId: string;
  readonly environment: string;
  readonly sdkInstanceId: string;
  readonly serviceName: string;
  readonly traceContext: TraceContext;
  readonly timestampUtc: number;
  readonly sequenceId: number;
  readonly captureReason: CaptureReason;
  readonly logId?: string;
  readonly fields?: Record<string, unknown>;
  readonly captureLocals?: Record<string, unknown>;
}

export interface CaptureFieldSplit {
  readonly logFields?: Record<string, unknown>;
  readonly captureLocals?: Record<string, unknown>;
}

function createState(): SerializationState {
  return {
    truncated: false,
    maxDepthReached: false,
    redactedFields: new Set<string>(),
    droppedFields: new Set<string>(),
  };
}

function matchesAllowList(path: string, config: CaptureConfig): boolean {
  if (config.allowFieldPatterns.length === 0) {
    return true;
  }
  return config.allowFieldPatterns.some((pattern) => pattern.test(path));
}

function shouldRedact(path: string, key: string, value: string, config: CaptureConfig): boolean {
  return config.denyFieldPatterns.some((pattern) => pattern.test(key) || pattern.test(path))
    || config.maskingRules.some((pattern) => pattern.test(key) || pattern.test(value));
}

function renderFunctionName(value: Function): string {
  return value.name ? `[Function: ${value.name}]` : "[Function]";
}

function serializeValue(
  value: unknown,
  config: CaptureConfig,
  state: SerializationState,
  path: string,
  seen: WeakSet<object>,
  depth: number,
): unknown {
  if (!matchesAllowList(path, config)) {
    state.truncated = true;
    state.droppedFields.add(path);
    return TRUNCATED_MARKER;
  }

  if (depth > config.maxSerializationDepth) {
    state.truncated = true;
    state.maxDepthReached = true;
    state.droppedFields.add(path);
    return TRUNCATED_MARKER;
  }

  if (value === undefined) {
    return UNDEFINED_MARKER;
  }
  if (value == null) {
    return null;
  }
  if (typeof value === "string") {
    if (config.maskingRules.some((rule) => rule.test(value))) {
      state.redactedFields.add(path);
      return REDACTED_MARKER;
    }
    return value.length > config.maxStringLength ? `${value.slice(0, config.maxStringLength)}...` : value;
  }
  if (typeof value === "number" || typeof value === "boolean") {
    return value;
  }
  if (typeof value === "bigint") {
    return value.toString();
  }
  if (typeof value === "symbol") {
    return value.toString();
  }
  if (typeof value === "function") {
    return renderFunctionName(value);
  }
  if (typeof Promise !== "undefined" && value instanceof Promise) {
    return PROMISE_MARKER;
  }
  if (value instanceof Date) {
    return value.toISOString();
  }
  if (value instanceof Error) {
    return {
      name: value.name,
      message: value.message,
    };
  }
  if (typeof value !== "object") {
    return String(value);
  }
  if (seen.has(value)) {
    state.droppedFields.add(path);
    return CIRCULAR_MARKER;
  }

  seen.add(value);

  if (Array.isArray(value)) {
    const truncated = value.length > config.maxCollectionEntries;
    if (truncated) {
      state.truncated = true;
      state.droppedFields.add(path);
    }
    return value.slice(0, config.maxCollectionEntries).map((entry, index) =>
      serializeValue(entry, config, state, `${path}[${index}]`, seen, depth + 1),
    );
  }

  const entries = Object.entries(value);
  const limitedEntries = entries.slice(0, config.maxCollectionEntries);
  if (entries.length > config.maxCollectionEntries) {
    state.truncated = true;
    state.droppedFields.add(path);
  }

  const result: Record<string, unknown> = {};
  for (const [key, entry] of limitedEntries) {
    const nextPath = path ? `${path}.${key}` : key;
    const entryAsString = typeof entry === "string" ? entry : String(entry);
    if (shouldRedact(nextPath, key, entryAsString, config)) {
      state.redactedFields.add(nextPath);
      result[key] = REDACTED_MARKER;
      continue;
    }
    result[key] = serializeValue(entry, config, state, nextPath, seen, depth + 1);
  }
  return result;
}

function finalizeMetadata(state: SerializationState): SerializationMetadata {
  return {
    truncated: state.truncated,
    maxDepthReached: state.maxDepthReached,
    redactedFields: [...state.redactedFields].sort(),
    droppedFields: [...state.droppedFields].sort(),
  };
}

function renderLogFieldValue(value: unknown): string {
  if (typeof value === "string") {
    return value;
  }
  return JSON.stringify(value);
}

export function splitCaptureFields(fields: Record<string, unknown> | undefined): CaptureFieldSplit {
  if (!fields) {
    return {};
  }
  const captureLocals = fields[INTERNAL_CAPTURE_LOCALS_KEY];
  const logFields = { ...fields };
  delete logFields[INTERNAL_CAPTURE_LOCALS_KEY];
  return {
    logFields: Object.keys(logFields).length > 0 ? logFields : undefined,
    captureLocals: typeof captureLocals === "object" && captureLocals != null
      ? (captureLocals as Record<string, unknown>)
      : undefined,
  };
}

export function sanitizeFields(
  fields: Record<string, unknown> | undefined,
  config: CaptureConfig,
): Record<string, unknown> | undefined {
  if (!fields) {
    return undefined;
  }
  const state = createState();
  return serializeValue(fields, config, state, "", new WeakSet<object>(), 0) as Record<string, unknown>;
}

export function sanitizeLogFields(
  fields: Record<string, unknown> | undefined,
  config: CaptureConfig,
): Record<string, string> {
  if (!fields) {
    return {};
  }
  const state = createState();
  const serialized = serializeValue(fields, config, state, "", new WeakSet<object>(), 0) as Record<string, unknown>;
  return Object.fromEntries(
    Object.entries(serialized).map(([key, value]) => [key, renderLogFieldValue(value)]),
  );
}

export function serializeSnapshotLocals(
  locals: Record<string, unknown> | undefined,
  config: CaptureConfig,
): { localsJson: string; metadata: SerializationMetadata } {
  const state = createState();
  const serialized = serializeValue(locals ?? {}, config, state, "", new WeakSet<object>(), 0);
  let localsJson = JSON.stringify(serialized);
  if (new TextEncoder().encode(localsJson).length > config.maxPayloadBytes) {
    state.truncated = true;
    state.droppedFields.add("$payload");
    localsJson = JSON.stringify(TRUNCATED_MARKER);
  }
  return {
    localsJson,
    metadata: finalizeMetadata(state),
  };
}

export function captureCallStack(skipFrames = 0): CallStackItem[] {
  const stack = new Error().stack;
  if (!stack) {
    return [];
  }

  const frames = stack
    .split("\n")
    .slice(1 + skipFrames)
    .map((line) => line.trim())
    .filter((line) => line.length > 0)
    .filter((line) => !line.includes("/capture.") && !line.includes("/client."));

  return frames.map(parseCallStackLine).filter((frame): frame is CallStackItem => frame != null);
}

function parseCallStackLine(line: string): CallStackItem | null {
  const v8Match = line.match(/^at\s+(.*?)\s+\((.*?):(\d+):(\d+)\)$/) ?? line.match(/^at\s+(.*?):(\d+):(\d+)$/);
  if (v8Match) {
    if (v8Match.length === 5) {
      return {
        functionName: v8Match[1],
        filePath: v8Match[2],
        lineNumber: Number(v8Match[3]),
        columnNumber: Number(v8Match[4]),
      };
    }
    return {
      functionName: "anonymous",
      filePath: v8Match[1],
      lineNumber: Number(v8Match[2]),
      columnNumber: Number(v8Match[3]),
    };
  }

  const firefoxMatch = line.match(/^(.*?)@(.*?):(\d+):(\d+)$/);
  if (firefoxMatch) {
    return {
      functionName: firefoxMatch[1] || "anonymous",
      filePath: firefoxMatch[2],
      lineNumber: Number(firefoxMatch[3]),
      columnNumber: Number(firefoxMatch[4]),
    };
  }

  return null;
}

export function createFrameSnapshot(
  payload: CapturePayload,
  config: CaptureConfig,
): FrameSnapshot {
  const localsSource = payload.captureLocals ?? payload.fields ?? {};
  const { localsJson, metadata } = serializeSnapshotLocals(localsSource, config);
  return {
    frameId: payload.frameId,
    traceId: payload.traceContext.traceId,
    spanId: payload.traceContext.spanId,
    appId: payload.appId,
    environment: payload.environment,
    sdkInstanceId: payload.sdkInstanceId,
    serviceName: payload.serviceName,
    timestampUtc: payload.timestampUtc,
    sequenceId: payload.sequenceId,
    captureReason: payload.captureReason,
    callStack: captureCallStack(2),
    localsJson,
    serializationMetadata: metadata,
    logId: payload.logId,
  };
}

import type { ContextManager, TraceContext } from "./context.js";
import type { CaptureReason, LogLevel, RemoteRule } from "./generated/contracts.js";
import type { NodeProcessLike } from "./runtime.js";
import type { ChronoTransport } from "./transport.js";

export type OverflowStrategy = "DROP_OLDEST" | "DROP_NEWEST" | "BLOCK_CALLER";
export type AuthMode = "none" | "apiKey" | "bearer" | "mTLS";
export type RuntimeFlavor = "auto" | "node" | "browser";

export interface CaptureConfig {
  autoCaptureLevels: LogLevel[];
  maxSerializationDepth: number;
  maxCollectionEntries: number;
  maxStringLength: number;
  maxPayloadBytes: number;
  maskingRules: RegExp[];
  denyFieldPatterns: RegExp[];
  allowFieldPatterns: RegExp[];
  manualCaptureReason: CaptureReason;
}

export interface BufferConfig {
  maxMemoryMB: number;
  flushIntervalMs: number;
  overflowStrategy: OverflowStrategy;
}

export type AuthConfig =
  | { mode: "none" }
  | { mode: "apiKey"; apiKey: string }
  | { mode: "bearer"; token: string }
  | { mode: "mTLS"; clientCertificateAlias: string };

export interface SpanOptions {
  parent?: TraceContext;
  attributes?: Record<string, unknown>;
  captureLocals?: Record<string, unknown>;
}

export interface ChronoTraceConfig {
  appId: string;
  environment?: string;
  serviceName?: string;
  serverUrl?: string;
  auth?: AuthConfig;
  runtime?: RuntimeFlavor;
  captureConfig?: Partial<CaptureConfig>;
  bufferConfig?: Partial<BufferConfig>;
  transport?: ChronoTransport;
  contextManager?: ContextManager;
  fetchImpl?: typeof fetch;
  webSocketFactory?: (url: string) => WebSocket;
  nodeProcess?: NodeProcessLike;
  rules?: RemoteRule[];
}

export const defaultCaptureConfig: CaptureConfig = {
  autoCaptureLevels: ["ERROR", "FATAL"],
  maxSerializationDepth: 3,
  maxCollectionEntries: 50,
  maxStringLength: 4_096,
  maxPayloadBytes: 256 * 1024,
  maskingRules: [/password/i, /token/i, /secret/i, /^sk_[a-zA-Z0-9]+$/],
  denyFieldPatterns: [],
  allowFieldPatterns: [],
  manualCaptureReason: "manual_trace",
};

export const defaultBufferConfig: BufferConfig = {
  maxMemoryMB: 50,
  flushIntervalMs: 2_000,
  overflowStrategy: "DROP_OLDEST",
};

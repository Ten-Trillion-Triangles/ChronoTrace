import {
  defaultBufferConfig,
  defaultCaptureConfig,
  type BufferConfig,
  type CaptureConfig,
  type ChronoTraceConfig,
  type RuntimeFlavor,
  type SpanOptions,
} from "./config.js";
import { createFrameSnapshot } from "./capture.js";
import { StackContextManager, type ContextManager, type TraceContext } from "./context.js";
import type {
  CaptureReason,
  FrameSnapshot,
  IngestBatch,
  LogLevel,
  LogRecord,
  RemoteRule,
  SpanRecord,
  SpanStatus,
} from "./generated/contracts.js";
import { newSpanId, newTraceId } from "./internal/id.js";
import { sanitizeLogFields, splitCaptureFields } from "./redaction.js";
import { evaluateRule, parseRuleExpression, type RuleAstNode } from "./remoteRules.js";
import type { RuntimeHealth, RuntimeState } from "./runtime.js";
import { NoopTransport, type ChronoTransport } from "./transport.js";
import { HttpTransport } from "./transports/httpTransport.js";
import { WebSocketTransport } from "./transports/webSocketTransport.js";

interface CompiledRule {
  readonly rule: RemoteRule;
  readonly ast: RuleAstNode;
}

interface PendingSpan {
  readonly context: TraceContext;
  readonly attributes?: Record<string, unknown>;
  readonly captureLocals?: Record<string, unknown>;
  readonly startedAtUtc: number;
}

export interface SpanHandle {
  readonly context: TraceContext;
  end(status?: SpanStatus): Promise<void>;
}

const ApproxBufferedEntryBytes = 2_048;

type BufferedKind = "log" | "span" | "frame";

function errorMessage(error: unknown): string {
  if (error instanceof Error) {
    return error.message;
  }
  return String(error);
}

function resolveRuntimeFlavor(config: ChronoTraceConfig): RuntimeFlavor {
  if (config.runtime && config.runtime !== "auto") {
    return config.runtime;
  }
  if (typeof process !== "undefined" && process.versions?.node) {
    return "node";
  }
  return "browser";
}

function resolveTransport(config: ChronoTraceConfig): ChronoTransport {
  if (config.transport) {
    return config.transport;
  }
  if (config.serverUrl?.startsWith("ws")) {
    return new WebSocketTransport({
      url: config.serverUrl,
      webSocketFactory: config.webSocketFactory,
    });
  }
  if (config.serverUrl?.startsWith("http")) {
    return new HttpTransport({
      url: config.serverUrl,
      fetchImpl: config.fetchImpl,
    });
  }
  return new NoopTransport();
}

export class ChronoTraceClient {
  private readonly captureConfig: CaptureConfig;
  private readonly bufferConfig: BufferConfig;
  private readonly contextManager: ContextManager;
  private readonly runtimeFlavor: RuntimeFlavor;
  private readonly transport: ChronoTransport;
  private readonly compiledRules = new Map<string, CompiledRule>();
  private readonly sdkInstanceId = newTraceId();
  private readonly maxBufferedEntries: number;
  private readonly pendingLogs: LogRecord[] = [];
  private readonly pendingSpans: SpanRecord[] = [];
  private readonly pendingFrames: FrameSnapshot[] = [];
  private readonly hookCleanup: Array<() => void> = [];
  private flushTimer?: ReturnType<typeof setInterval>;
  private flushPromise?: Promise<void>;
  private initialized = false;
  private sequenceId = 0;
  private runtimeState: RuntimeState;
  private droppedLogs = 0;
  private droppedSpans = 0;
  private droppedFrames = 0;
  private fatalFlushes = 0;
  private lastFlushError?: string;

  constructor(private readonly config: ChronoTraceConfig) {
    this.captureConfig = { ...defaultCaptureConfig, ...config.captureConfig };
    this.bufferConfig = { ...defaultBufferConfig, ...config.bufferConfig };
    this.contextManager = config.contextManager ?? new StackContextManager();
    this.runtimeFlavor = resolveRuntimeFlavor(config);
    this.transport = resolveTransport(config);
    this.maxBufferedEntries = Math.max(
      1,
      Math.floor((this.bufferConfig.maxMemoryMB * 1024 * 1024) / ApproxBufferedEntryBytes),
    );
    this.runtimeState = this.isLocalFallbackTransport() ? "LOCAL_FALLBACK" : "RECONNECT_BACKOFF";
    this.transport.setCommandHandler?.((command) => {
      if (command.type === "upsert_rule" && command.rule) {
        this.setRemoteRule(command.rule);
      }
      if (command.type === "delete_rule" && command.ruleId) {
        this.compiledRules.delete(command.ruleId);
      }
    });
    for (const rule of config.rules ?? []) {
      this.setRemoteRule(rule);
    }
  }

  async init(): Promise<this> {
    if (this.initialized) {
      return this;
    }
    this.initialized = true;
    this.registerLifecycleHooks();
    this.flushTimer = setInterval(() => {
      void this.flush();
    }, this.bufferConfig.flushIntervalMs);
    await this.connectTransport();
    return this;
  }

  async shutdown(): Promise<void> {
    if (this.flushTimer) {
      clearInterval(this.flushTimer);
      this.flushTimer = undefined;
    }
    while (this.hookCleanup.length > 0) {
      this.hookCleanup.pop()?.();
    }
    await this.flush();
    await this.transport.close();
    this.initialized = false;
  }

  getCurrentContext(): TraceContext | undefined {
    return this.contextManager.getCurrentContext();
  }

  getRuntimeHealth(): RuntimeHealth {
    return {
      state: this.runtimeState,
      droppedLogs: this.droppedLogs,
      droppedSpans: this.droppedSpans,
      droppedFrames: this.droppedFrames,
      bufferedLogs: this.pendingLogs.length,
      bufferedSpans: this.pendingSpans.length,
      bufferedFrames: this.pendingFrames.length,
      fatalFlushes: this.fatalFlushes,
      lastFlushError: this.lastFlushError,
    };
  }

  async withTrace<T>(
    name: string,
    fn: () => Promise<T> | T,
    options?: SpanOptions,
  ): Promise<T> {
    return this.withSpan(name, fn, options);
  }

  async withSpan<T>(
    name: string,
    fn: () => Promise<T> | T,
    options?: Omit<SpanOptions, "parent">,
  ): Promise<T> {
    const handle = this.startSpan(name, options);
    return this.contextManager.runWithContext(handle.context, async () => {
      try {
        const result = await fn();
        await handle.end("OK");
        return result;
      } catch (error) {
        await handle.end("ERROR");
        throw error;
      }
    });
  }

  startSpan(name: string, options?: Omit<SpanOptions, "parent">): SpanHandle {
    const parent = this.getCurrentContext();
    const timestampUtc = Date.now();
    const context: TraceContext = {
      traceId: parent?.traceId ?? newTraceId(),
      spanId: newSpanId(),
      parentSpanId: parent?.spanId,
      name,
      startedAt: new Date().toISOString(),
      attributes: options?.attributes,
    };
    const pending: PendingSpan = {
      context,
      attributes: options?.attributes,
      captureLocals: options?.captureLocals,
      startedAtUtc: timestampUtc,
    };
    if (options?.captureLocals && Object.keys(options.captureLocals).length > 0) {
      this.enqueueFrame({
        traceContext: context,
        captureReason: "manual_trace",
        fields: undefined,
        captureLocals: options.captureLocals,
        timestampUtc,
      });
    }
    return {
      context,
      end: async (status = "OK") => {
        this.enqueueCompletedSpan(pending, status);
        await this.flush();
      },
    };
  }

  async debug(message: string, fields?: Record<string, unknown>): Promise<void> {
    await this.log("DEBUG", message, fields);
  }

  async info(message: string, fields?: Record<string, unknown>): Promise<void> {
    await this.log("INFO", message, fields);
  }

  async warn(message: string, fields?: Record<string, unknown>): Promise<void> {
    await this.log("WARN", message, fields);
  }

  async error(message: string, fields?: Record<string, unknown>): Promise<void> {
    await this.log("ERROR", message, fields);
  }

  async fatal(message: string, fields?: Record<string, unknown>): Promise<void> {
    await this.log("FATAL", message, fields);
  }

  injectHeaders(carrier: Record<string, string> = {}): Record<string, string> {
    const context = this.getCurrentContext();
    if (!context) {
      return carrier;
    }
    carrier.traceparent = `00-${context.traceId}-${context.spanId}-01`;
    carrier["Chrono-Trace-Id"] = context.traceId;
    carrier["Chrono-Parent-Span-Id"] = context.spanId;
    return carrier;
  }

  extractHeaders(headers: Record<string, string>): TraceContext | undefined {
    const traceparent = headers.traceparent;
    if (traceparent) {
      const parts = traceparent.split("-");
      if (parts.length === 4) {
        return {
          traceId: parts[1],
          spanId: parts[2],
          name: "remote",
          startedAt: new Date().toISOString(),
        };
      }
    }
    if (headers["Chrono-Trace-Id"] && headers["Chrono-Parent-Span-Id"]) {
      return {
        traceId: headers["Chrono-Trace-Id"],
        spanId: headers["Chrono-Parent-Span-Id"],
        name: "remote",
        startedAt: new Date().toISOString(),
      };
    }
    return undefined;
  }

  setRemoteRule(rule: RemoteRule): void {
    this.compiledRules.set(rule.ruleId, {
      rule,
      ast: parseRuleExpression(rule.expression),
    });
  }

  async flush(): Promise<void> {
    if (this.flushPromise) {
      await this.flushPromise;
      return;
    }
    this.flushPromise = this.flushInternal(false).finally(() => {
      this.flushPromise = undefined;
    });
    await this.flushPromise;
  }

  private async log(
    level: LogLevel,
    message: string,
    fields?: Record<string, unknown>,
  ): Promise<void> {
    const fieldSplit = splitCaptureFields(fields);
    const captureReason = this.resolveCaptureReason(level, fieldSplit.logFields);
    const context = this.resolveTraceContext(captureReason);
    const timestampUtc = Date.now();
    const sequenceId = this.nextSequenceId();
    const logId = newTraceId();
    const linkedFrameId = captureReason && context
      ? this.enqueueFrameSnapshot({
        traceContext: context,
        captureReason,
        logId,
        fields: fieldSplit.logFields,
        captureLocals: fieldSplit.captureLocals,
        timestampUtc,
      })
      : undefined;

    this.enqueueLog({
      logId,
      appId: this.config.appId,
      environment: this.config.environment ?? "development",
      sdkInstanceId: this.sdkInstanceId,
      serviceName: this.config.serviceName ?? this.config.appId,
      traceId: context?.traceId,
      spanId: context?.spanId,
      parentSpanId: context?.parentSpanId,
      timestampUtc,
      sequenceId,
      level,
      message,
      fields: sanitizeLogFields(fieldSplit.logFields, this.captureConfig),
      captureReason,
      linkedFrameId,
    });
    await this.flush();
  }

  private enqueueCompletedSpan(
    pending: PendingSpan,
    status: SpanStatus,
  ): void {
    const context = pending.context;
    this.enqueueSpan({
      spanId: context.spanId,
      traceId: context.traceId,
      appId: this.config.appId,
      environment: this.config.environment ?? "development",
      serviceName: this.config.serviceName ?? this.config.appId,
      operationName: context.name ?? "span",
      parentSpanId: context.parentSpanId,
      startTimeUtc: pending.startedAtUtc,
      endTimeUtc: Date.now(),
      status,
      attributes: sanitizeLogFields(pending.attributes, this.captureConfig),
    });
  }

  private resolveCaptureReason(
    level: LogLevel,
    fields: Record<string, unknown> | undefined,
  ): CaptureReason | undefined {
    if (this.captureConfig.autoCaptureLevels.includes(level)) {
      return "auto_capture_level";
    }
    if (!fields) {
      return undefined;
    }
    for (const compiled of this.compiledRules.values()) {
      if ((compiled.rule.enabled ?? true) && evaluateRule(compiled.ast, {
        locals: fields,
        metadata: {
          appId: this.config.appId,
          environment: this.config.environment ?? "development",
          serviceName: this.config.serviceName ?? this.config.appId,
        },
      })) {
        return compiled.rule.captureMode ?? "remote_rule";
      }
    }
    return undefined;
  }

  private resolveTraceContext(captureReason: CaptureReason | undefined): TraceContext | undefined {
    const context = this.getCurrentContext();
    if (context || !captureReason) {
      return context;
    }
    return {
      traceId: newTraceId(),
      spanId: newSpanId(),
      name: "capture",
      startedAt: new Date().toISOString(),
    };
  }

  private enqueueFrameSnapshot(
    payload: Omit<Parameters<ChronoTraceClient["buildFrameSnapshot"]>[0], "frameId" | "sequenceId">,
  ): string {
    const frame = this.buildFrameSnapshot(payload);
    this.enqueueFrame(frame);
    return frame.frameId;
  }

  private buildFrameSnapshot(
    payload: {
      traceContext: TraceContext;
      captureReason: CaptureReason;
      fields?: Record<string, unknown>;
      captureLocals?: Record<string, unknown>;
      logId?: string;
      timestampUtc: number;
    },
  ): FrameSnapshot {
    return createFrameSnapshot({
      frameId: newTraceId(),
      appId: this.config.appId,
      environment: this.config.environment ?? "development",
      sdkInstanceId: this.sdkInstanceId,
      serviceName: this.config.serviceName ?? this.config.appId,
      traceContext: payload.traceContext,
      timestampUtc: payload.timestampUtc,
      sequenceId: this.nextSequenceId(),
      captureReason: payload.captureReason,
      logId: payload.logId,
      fields: payload.fields,
      captureLocals: payload.captureLocals,
    }, this.captureConfig);
  }

  private nextSequenceId(): number {
    this.sequenceId += 1;
    return this.sequenceId;
  }

  async flushFatal(): Promise<void> {
    this.fatalFlushes += 1;
    this.runtimeState = "FATAL_FLUSH";
    if (this.flushTimer) {
      clearInterval(this.flushTimer);
      this.flushTimer = undefined;
    }
    await this.flushInternal(true);
  }

  private async flushInternal(fatal: boolean): Promise<void> {
    if (this.pendingLogs.length === 0 && this.pendingSpans.length === 0 && this.pendingFrames.length === 0) {
      return;
    }
    if (!this.initialized) {
      await this.init();
    }
    if (!this.transport.isConnected()) {
      await this.connectTransport();
      if (!this.transport.isConnected()) {
        return;
      }
    }
    const batch = this.drainBatch();
    try {
      await this.transport.send(batch);
      this.lastFlushError = undefined;
      this.runtimeState = this.isLocalFallbackTransport() ? "LOCAL_FALLBACK" : "CONNECTED";
    } catch (error) {
      this.lastFlushError = errorMessage(error);
      this.restoreBatch(batch);
      if (fatal) {
        this.runtimeState = "FATAL_FLUSH";
      } else if (this.transport.isConnected()) {
        this.runtimeState = "DEGRADED_BUFFERING";
      } else if (this.isLocalFallbackTransport()) {
        this.runtimeState = "LOCAL_FALLBACK";
      } else {
        this.runtimeState = "RECONNECT_BACKOFF";
      }
    }
  }

  private drainBatch(): IngestBatch {
    return {
      client: {
        appId: this.config.appId,
        environment: this.config.environment ?? "development",
        sdkInstanceId: this.sdkInstanceId,
        serviceName: this.config.serviceName ?? this.config.appId,
      },
      logs: this.pendingLogs.splice(0, this.pendingLogs.length),
      spans: this.pendingSpans.splice(0, this.pendingSpans.length),
      frameSnapshots: this.pendingFrames.splice(0, this.pendingFrames.length),
    };
  }

  private restoreBatch(batch: IngestBatch): void {
    this.prependBuffered(this.pendingLogs, batch.logs ?? [], "log");
    this.prependBuffered(this.pendingSpans, batch.spans ?? [], "span");
    this.prependBuffered(this.pendingFrames, batch.frameSnapshots ?? [], "frame");
  }

  private async connectTransport(): Promise<void> {
    if (this.isLocalFallbackTransport()) {
      this.runtimeState = "LOCAL_FALLBACK";
      return;
    }
    if (this.transport.isConnected()) {
      this.runtimeState = "CONNECTED";
      this.lastFlushError = undefined;
      return;
    }
    try {
      await this.transport.connect();
      this.runtimeState = this.transport.isConnected() ? "CONNECTED" : "RECONNECT_BACKOFF";
      if (this.transport.isConnected()) {
        this.lastFlushError = undefined;
      }
    } catch (error) {
      this.lastFlushError = errorMessage(error);
      this.runtimeState = "RECONNECT_BACKOFF";
    }
  }

  private enqueueLog(log: LogRecord): void {
    this.enqueueBuffered(this.pendingLogs, log, "log");
  }

  private enqueueSpan(span: SpanRecord): void {
    this.enqueueBuffered(this.pendingSpans, span, "span");
  }

  private enqueueFrame(frame: FrameSnapshot | Omit<Parameters<ChronoTraceClient["buildFrameSnapshot"]>[0], "frameId" | "sequenceId">): void {
    const materialized = "frameId" in frame ? frame : this.buildFrameSnapshot(frame);
    this.enqueueBuffered(this.pendingFrames, materialized, "frame");
  }

  private enqueueBuffered<T>(buffer: T[], item: T, kind: BufferedKind): void {
    if (buffer.length < this.maxBufferedEntries) {
      buffer.push(item);
      return;
    }
    this.bumpDropped(kind);
    if (this.bufferConfig.overflowStrategy === "DROP_OLDEST") {
      buffer.shift();
      buffer.push(item);
    }
  }

  private prependBuffered<T>(buffer: T[], items: readonly T[], kind: BufferedKind): void {
    for (const item of [...items].reverse()) {
      if (buffer.length >= this.maxBufferedEntries) {
        this.bumpDropped(kind);
        if (this.bufferConfig.overflowStrategy !== "DROP_OLDEST") {
          continue;
        }
        buffer.pop();
      }
      buffer.unshift(item);
    }
  }

  private bumpDropped(kind: BufferedKind): void {
    if (kind === "log") {
      this.droppedLogs += 1;
      return;
    }
    if (kind === "span") {
      this.droppedSpans += 1;
      return;
    }
    this.droppedFrames += 1;
  }

  private registerLifecycleHooks(): void {
    if (this.runtimeFlavor === "node") {
      this.registerNodeHooks();
      return;
    }
    this.registerBrowserHooks();
  }

  private registerNodeHooks(): void {
    const processLike = this.config.nodeProcess ?? (typeof process !== "undefined" ? process : undefined);
    if (!processLike) {
      return;
    }
    const onUncaughtException = (_error: Error) => {
      void this.flushFatal();
    };
    const onUnhandledRejection = (_reason: unknown, _promise: Promise<unknown>) => {
      void this.flushFatal();
    };
    processLike.on("uncaughtException", onUncaughtException);
    processLike.on("unhandledRejection", onUnhandledRejection);
    this.hookCleanup.push(() => processLike.off("uncaughtException", onUncaughtException));
    this.hookCleanup.push(() => processLike.off("unhandledRejection", onUnhandledRejection));
  }

  private registerBrowserHooks(): void {
    const globalTarget = globalThis as {
      addEventListener?: (type: string, listener: () => void) => void;
      removeEventListener?: (type: string, listener: () => void) => void;
      document?: {
        visibilityState?: string;
        addEventListener?: (type: string, listener: () => void) => void;
        removeEventListener?: (type: string, listener: () => void) => void;
      };
    };
    const onPageExit = () => {
      void this.flushFatal();
    };
    globalTarget.addEventListener?.("beforeunload", onPageExit);
    globalTarget.addEventListener?.("pagehide", onPageExit);
    this.hookCleanup.push(() => globalTarget.removeEventListener?.("beforeunload", onPageExit));
    this.hookCleanup.push(() => globalTarget.removeEventListener?.("pagehide", onPageExit));
    const onVisibilityHidden = () => {
      if (globalTarget.document?.visibilityState === "hidden") {
        void this.flushFatal();
      }
    };
    globalTarget.document?.addEventListener?.("visibilitychange", onVisibilityHidden);
    this.hookCleanup.push(() => globalTarget.document?.removeEventListener?.("visibilitychange", onVisibilityHidden));
  }

  private isLocalFallbackTransport(): boolean {
    return this.transport instanceof NoopTransport;
  }
}

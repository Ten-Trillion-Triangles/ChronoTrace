export type RuntimeState =
  | "CONNECTED"
  | "DEGRADED_BUFFERING"
  | "RECONNECT_BACKOFF"
  | "LOCAL_FALLBACK"
  | "FATAL_FLUSH";

export interface RuntimeHealth {
  readonly state: RuntimeState;
  readonly droppedLogs: number;
  readonly droppedSpans: number;
  readonly droppedFrames: number;
  readonly bufferedLogs: number;
  readonly bufferedSpans: number;
  readonly bufferedFrames: number;
  readonly fatalFlushes: number;
  readonly lastFlushError?: string;
}

export interface NodeProcessLike {
  on(event: "uncaughtException", listener: (error: Error) => void): void;
  on(event: "unhandledRejection", listener: (reason: unknown, promise: Promise<unknown>) => void): void;
  off(event: "uncaughtException", listener: (error: Error) => void): void;
  off(event: "unhandledRejection", listener: (reason: unknown, promise: Promise<unknown>) => void): void;
}

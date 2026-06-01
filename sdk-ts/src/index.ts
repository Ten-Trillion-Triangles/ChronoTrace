import { ChronoTraceClient } from "./client.js";
import { evaluateRule, parseRuleExpression } from "./remoteRules.js";
import {
  HttpTransport,
  NoopTransport,
  RecordingTransport,
  WebSocketTransport,
} from "./transport.js";
import type { ChronoTraceConfig, SpanOptions } from "./config.js";

let client: ChronoTraceClient | undefined;

function requireClient(): ChronoTraceClient {
  if (!client) {
    throw new Error("ChronoTrace.init must be called before use");
  }
  return client;
}

export class ChronoTrace {
  static init(config: ChronoTraceConfig): void {
    client = new ChronoTraceClient(config);
  }

  static currentContext() {
    return requireClient().getCurrentContext();
  }

  static injectHeaders(carrier: Record<string, string> = {}) {
    return requireClient().injectHeaders(carrier);
  }

  static extractHeaders(carrier: Record<string, string>) {
    return requireClient().extractHeaders(carrier);
  }

  static runtimeHealth() {
    return requireClient().getRuntimeHealth();
  }

  static async shutdown(): Promise<void> {
    if (!client) {
      return;
    }
    await client.shutdown();
  }
}

export class ChronoLogger {
  static trace(message: string, fields?: Record<string, unknown>): Promise<void> {
    return requireClient().trace(message, fields);
  }

  static debug(message: string, fields?: Record<string, unknown>): Promise<void> {
    return requireClient().debug(message, fields);
  }

  static info(message: string, fields?: Record<string, unknown>): Promise<void> {
    return requireClient().info(message, fields);
  }

  static warn(message: string, fields?: Record<string, unknown>): Promise<void> {
    return requireClient().warn(message, fields);
  }

  static error(message: string, fields?: Record<string, unknown>): Promise<void> {
    return requireClient().error(message, fields);
  }

  static fatal(message: string, fields?: Record<string, unknown>): Promise<void> {
    return requireClient().fatal(message, fields);
  }
}

export async function withTrace<T>(
  name: string,
  block: () => Promise<T> | T,
  options?: SpanOptions,
): Promise<T> {
  return requireClient().withTrace(name, block, options);
}

export async function withSpan<T>(
  name: string,
  block: () => Promise<T> | T,
  options?: SpanOptions,
): Promise<T> {
  return requireClient().withSpan(name, block, options);
}

export function startSpan(name: string, options?: SpanOptions) {
  return requireClient().startSpan(name, options);
}

export {
  AsyncLocalStorageContextManager,
  createNodeChronoTrace,
} from "./node.js";
export { instrumentSource } from "./instrumentation.js";
export { createChronoTraceVitePlugin } from "./vite.js";
export { evaluateRule, parseRuleExpression };
export {
  HttpTransport,
  NoopTransport,
  RecordingTransport,
  WebSocketTransport,
};
export type * from "./types.js";

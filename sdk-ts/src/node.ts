import { AsyncLocalStorage } from "node:async_hooks";
import type { ChronoTraceConfig } from "./config.js";
import type { ContextManager, TraceContext } from "./context.js";
import { ChronoTraceClient } from "./client.js";

export class AsyncLocalStorageContextManager implements ContextManager {
  private readonly storage = new AsyncLocalStorage<TraceContext>();

  getCurrentContext(): TraceContext | undefined {
    return this.storage.getStore();
  }

  runWithContext<T>(context: TraceContext, fn: () => T): T {
    return this.storage.run(context, fn);
  }
}

export function createNodeChronoTrace(config: ChronoTraceConfig): ChronoTraceClient {
  return new ChronoTraceClient({
    ...config,
    runtime: "node",
    contextManager: config.contextManager ?? new AsyncLocalStorageContextManager()
  });
}

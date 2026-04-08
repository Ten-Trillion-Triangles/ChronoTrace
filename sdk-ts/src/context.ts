export interface TraceContext {
  traceId: string;
  spanId: string;
  parentSpanId?: string;
  name?: string;
  startedAt?: string;
  attributes?: Record<string, unknown>;
}

export interface ContextManager {
  getCurrentContext(): TraceContext | undefined;
  runWithContext<T>(context: TraceContext, fn: () => Promise<T> | T): Promise<T> | T;
}

export class StackContextManager implements ContextManager {
  private readonly stack: TraceContext[] = [];

  getCurrentContext(): TraceContext | undefined {
    return this.stack[this.stack.length - 1];
  }

  runWithContext<T>(context: TraceContext, fn: () => Promise<T> | T): Promise<T> | T {
    this.stack.push(context);
    const result = fn();
    if (result && typeof (result as Promise<T>).then === "function") {
      return (result as Promise<T>).finally(() => {
        this.stack.pop();
      });
    }
    this.stack.pop();
    return result;
  }
}

export class AsyncLocalStorageContextManager implements ContextManager {
  private readonly fallback = new StackContextManager();
  private readonly storage?: {
    getStore(): TraceContext | undefined;
    run<T>(store: TraceContext, callback: () => Promise<T> | T): Promise<T> | T;
  };

  constructor() {
    const maybeRequire = typeof require === "function" ? require : undefined;
    if (maybeRequire) {
      try {
        const asyncHooks = maybeRequire("node:async_hooks") as {
          AsyncLocalStorage: new <T>() => {
            getStore(): T | undefined;
            run<R>(store: T, callback: () => Promise<R> | R): Promise<R> | R;
          };
        };
        this.storage = new asyncHooks.AsyncLocalStorage<TraceContext>();
      } catch {
        this.storage = undefined;
      }
    }
  }

  getCurrentContext(): TraceContext | undefined {
    return this.storage?.getStore() ?? this.fallback.getCurrentContext();
  }

  runWithContext<T>(context: TraceContext, fn: () => Promise<T> | T): Promise<T> | T {
    if (this.storage) {
      return this.storage.run(context, fn);
    }
    return this.fallback.runWithContext(context, fn);
  }
}

export const contextManager: ContextManager =
  typeof process !== "undefined" && !!process.versions?.node
    ? new AsyncLocalStorageContextManager()
    : new StackContextManager();

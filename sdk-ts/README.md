# ChronoTrace SDK for TypeScript

**Version:** 1.0.0

AI-native temporal logging SDK for TypeScript and JavaScript environments. Trace, log, instrument — with context propagation, redaction rules, and transport flexibility.

## Installation

```bash
npm install @chronotrace/sdk-ts
```

## Quick Start

```typescript
import { ChronoTrace, ChronoLogger, withTrace } from "@chronotrace/sdk-ts";

// Initialize the client
ChronoTrace.init({
  appId: "my-service",
  serverUrl: "http://localhost:8080",
});

// Log messages
await ChronoLogger.info("User logged in", { userId: "u_123" });

// Trace a function
const result = await withTrace("process-order", async () => {
  return await processOrder("ord_456");
});
```

## Features

- **Temporal Context Propagation** — AsyncLocalStorage-based context that flows through async operations
- **Structured Logging** — Info, debug, warn, error, fatal levels with field redacted by rules
- **Span/Trace Instrumentation** — Decorate functions with `withTrace` / `withSpan`
- **Vite Plugin** — Auto-instrument your Vite dev server
- **Node.js Support** — `createNodeChronoTrace` for native Node environments
- **Remote Redaction Rules** — Fetch and evaluate redaction rules from the server at runtime
- **Transport Abstraction** — HTTP, WebSocket, Recording, and Noop transports

## API

### ChronoTrace

- `ChronoTrace.init(config)` — Initialize the client
- `ChronoTrace.injectHeaders(carrier)` — Inject trace headers into a carrier object
- `ChronoTrace.extractHeaders(carrier)` — Extract trace headers from a carrier object
- `ChronoTrace.currentContext()` — Get the current trace context
- `ChronoTrace.runtimeHealth()` — Get runtime health status
- `ChronoTrace.shutdown()` — Gracefully shutdown the client

### ChronoLogger

- `ChronoLogger.trace(message, fields?)`
- `ChronoLogger.debug(message, fields?)`
- `ChronoLogger.info(message, fields?)`
- `ChronoLogger.warn(message, fields?)`
- `ChronoLogger.error(message, fields?)`
- `ChronoLogger.fatal(message, fields?)`

### Utilities

- `withTrace(name, block, options?)` — Wrap an async function in a trace span
- `withSpan(name, block, options?)` — Create a named span
- `startSpan(name, options?)` — Start a span manually
- `instrumentSource(source, options?)` — Instrument source code for tracing
- `createChronoTraceVitePlugin()` — Vite plugin for auto-instrumentation

## Configuration

```typescript
import { ChronoTrace } from "@chronotrace/sdk-ts";

ChronoTrace.init({
  appId: "my-service",        // Required: unique application identifier
  serverUrl: "http://localhost:8080",  // Server URL (http/https for HTTP, ws/wss for WebSocket)
  serviceName: "my-service",   // Service name (default: "unknown")
  environment: "development",   // Environment tag (optional)
  auth: { mode: "apiKey", apiKey: "ctr_sk_..." },  // Auth config (optional)
  captureConfig: {             // Capture behavior (optional)
    autoCaptureLevels: ["ERROR", "FATAL"],
    maxStringLength: 4096,
  },
  bufferConfig: {              // Buffer behavior (optional)
    maxMemoryMB: 50,
    flushIntervalMs: 2000,
    overflowStrategy: "DROP_OLDEST",
  },
});
```

All configuration fields:

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `appId` | `string` | **Yes** | — | Unique application identifier |
| `serverUrl` | `string` | No | `http://localhost:8080` | ChronoTrace server URL |
| `serviceName` | `string` | No | `"unknown"` | Service identifier |
| `environment` | `string` | No | — | Environment tag (e.g. `"production"`) |
| `auth` | `AuthConfig` | No | `{ mode: "none" }` | Authentication settings |
| `runtime` | `"auto" \| "node" \| "browser"` | No | `"auto"` | Runtime flavor |
| `captureConfig` | `CaptureConfig` | No | see defaults | What to capture and how |
| `bufferConfig` | `BufferConfig` | No | see defaults | Buffer/flush behavior |
| `transport` | `ChronoTransport` | No | HTTP transport | Override transport layer |
| `contextManager` | `ContextManager` | No | — | Custom context manager |
| `fetchImpl` | `typeof fetch` | No | global `fetch` | HTTP client override |
| `webSocketFactory` | `(url: string) => WebSocket` | No | native WS | WebSocket factory |
| `rules` | `RemoteRule[]` | No | — | Client-side redaction rules |

AuthConfig examples:

```typescript
// No auth (default)
auth: { mode: "none" }

// API key
auth: { mode: "apiKey", apiKey: "ctr_sk_..." }

// Bearer token
auth: { mode: "bearer", token: "eyJ..." }

// mTLS (Node.js only)
auth: { mode: "mTLS", clientCertificateAlias: "chronotrace-client" }
```

## Entry Points

| Field | Value | Purpose |
|---|---|---|
| `main` | `dist/src/index.js` | CommonJS require |
| `module` | `dist/src/index.js` | ESM import |
| `browser` | `dist/src/index.js` | Browser bundlers |
| `types` | `dist/src/index.d.ts` | TypeScript declarations |
| `exports["."]` | Conditional ESM/CJS | Modern bundlers |

## License

MIT
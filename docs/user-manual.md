# ChronoTrace User Manual

ChronoTrace is a distributed tracing and observability platform with three components:

- **Tracing SDKs** — Kotlin Multiplatform (JVM/JS/Wasm) and TypeScript (Node.js/browser) libraries that capture logs, spans, and frame snapshots (local variable state)
- **Server** — Kotlin/Ktor backend that ingests, stores, and serves trace data (file-based or ClickHouse)
- **MCP Server** — Model Context Protocol interface exposing trace query tools for AI integration

This guide covers SDK integration for all supported platforms, server configuration, and MCP tool usage.

---

## Table of Contents

1. [SDK Installation](#1-sdk-installation)
2. [SDK Configuration Reference](#2-sdk-configuration-reference)
3. [Kotlin/JVM SDK](#3-kotlinjvm-sdk)
4. [Kotlin/JS and Kotlin/Wasm SDKs](#4-kotlinjs-and-kotlinwasm-sdks)
5. [TypeScript/Node.js SDK](#5-typescriptnodejs-sdk)
6. [TypeScript/Browser SDK](#6-typescriptbrowser-sdk)
7. [Context Propagation](#7-context-propagation)
8. [Server Installation and Configuration](#8-server-installation-and-configuration)
9. [Storage Backends](#9-storage-backends)
10. [Authentication Modes](#10-authentication-modes)
11. [MCP Integration](#11-mcp-integration)
12. [API Reference](#12-api-reference)

---

## 1. SDK Installation

### Kotlin Multiplatform (JVM, JS, Wasm)

Add the Gradle dependency to your `build.gradle.kts`:

```kotlin
plugins {
    kotlin("multiplatform") version "2.1.0"
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation("com.chronotrace:sdk-kmp:0.1.0")
            }
        }
    }
}
```

For the Gradle plugin that enables automatic local variable capture (Kotlin/JVM only):

```kotlin
plugins {
    id("com.chronotrace.kotlin-plugin") version "0.1.0"
}
```

Apply the plugin to your JVM target:

```kotlin
chronotrace {
    captureLocals.set(true) // default: false
}
```

### TypeScript SDK (Node.js and Browser)

```bash
npm install @chronotrace/sdk-ts
```

For browser projects using Vite, add the plugin to `vite.config.ts`:

```typescript
import { defineConfig } from 'vite';
import { createChronoTraceVitePlugin } from '@chronotrace/sdk-ts/vite';

export default defineConfig({
    plugins: [createChronoTraceVitePlugin()]
});
```

---

## 2. SDK Configuration Reference

### ChronoTraceConfig (TypeScript)

```typescript
interface ChronoTraceConfig {
    appId: string;                          // Required. Identifies your application.
    environment?: string;                   // e.g. "local", "staging", "prod"
    serviceName?: string;                   // Logical service name within the app
    serverUrl?: string;                     // e.g. "http://localhost:8080" or "ws://localhost:8080"
    auth?: AuthConfig;                      // See AuthConfig below
    runtime?: "auto" | "node" | "browser";
    captureConfig?: Partial<CaptureConfig>;
    bufferConfig?: Partial<BufferConfig>;
    transport?: ChronoTransport;           // Override the default HTTP/WebSocket transport
    contextManager?: ContextManager;       // Default: AsyncLocalStorage (Node.js) or stack-based (browser)
    rules?: RemoteRule[];                  // Server-pushed capture rules evaluated client-side
}
```

### CaptureConfig

Controls when and how frame snapshots (local variable captures) are taken.

```typescript
interface CaptureConfig {
    autoCaptureLevels: LogLevel[];         // Log levels that trigger auto frame capture. Default: ["ERROR", "FATAL"]
    maxSerializationDepth: number;          // Max depth for serializing nested objects. Default: 3
    maxCollectionEntries: number;          // Max entries in collections. Default: 50
    maxStringLength: number;               // Max string length. Default: 4_096
    maxPayloadBytes: number;               // Max frame payload bytes. Default: 262_144
    maskingRules: RegExp[];                // Field names matching these patterns are masked. Default: [/password/i, /token/i, /secret/i, /^sk_[a-zA-Z0-9]+$/]
    denyFieldPatterns: RegExp[];          // Fields matching these patterns are dropped entirely
    allowFieldPatterns: RegExp[];          // If non-empty, only fields matching these patterns are kept
    manualCaptureReason: CaptureReason;   // Reason for manual captures. Default: "manual_trace"
}
```

### BufferConfig

```typescript
interface BufferConfig {
    maxMemoryMB: number;                  // Max memory for buffered records. Default: 50
    flushIntervalMs: number;              // Flush interval in ms. Default: 2_000
    overflowStrategy: OverflowStrategy;  // "DROP_OLDEST" | "DROP_NEWEST" | "BLOCK_CALLER". Default: "DROP_OLDEST"
}
```

### AuthConfig

```typescript
type AuthConfig =
    | { mode: "none" }
    | { mode: "apiKey"; apiKey: string }
    | { mode: "bearer"; token: string }
    | { mode: "mTLS"; clientCertificateAlias: string };
```

### ChronoConfig (Kotlin Multiplatform)

```kotlin
data class ChronoConfig(
    val appId: String,
    val environment: String? = null,
    val serviceName: String? = null,
    val serverUrl: String? = null,
    val auth: AuthConfig = AuthConfig.None,
    val captureConfig: CaptureConfig = defaultCaptureConfig,
    val bufferConfig: BufferConfig = defaultBufferConfig,
    val transport: ChronoTransport = NoopTransport,
)
```

---

## 3. Kotlin/JVM SDK

### Initialization

```kotlin
import com.chronotrace.sdk.*

suspend fun main() {
    ChronoTrace.init(
        ChronoConfig(
            appId = "my-service",
            environment = "prod",
            serviceName = "order-service",
            serverUrl = "http://localhost:8080",
            auth = AuthConfig.ApiKey("ctk_your_key_here"),
        )
    )

    // Your application code here...

    ChronoTrace.shutdown()
}
```

### Logging

```kotlin
import com.chronotrace.sdk.ChronoLogger

suspend fun example() {
    ChronoLogger.trace("entering processOrder")
    ChronoLogger.info("order created", mapOf("orderId" to orderId, "amount" to total))
    ChronoLogger.warn("rate limit approaching", mapOf("remaining" to remaining))
    ChronoLogger.error("payment failed", mapOf("error" to e.message, "orderId" to orderId))
    ChronoLogger.fatal("unrecoverable error", mapOf("exception" to e.toString()))
}
```

When `autoCaptureLevels` includes `ERROR` or `FATAL`, a frame snapshot is automatically captured at the log call site, serializing the current local variables.

### Spans and Traces

```kotlin
import com.chronotrace.sdk.*

suspend fun processOrder(orderId: String, amount: Double): OrderResult {
    // withTrace wraps a block and auto-ends on completion
    return withTrace("processOrder $orderId") {
        ChronoLogger.info("processing order", mapOf("orderId" to orderId, "amount" to amount))
        // ...
        OrderResult.Success
    }
}

// Or use withSpan (identical behavior):
suspend fun anotherExample() {
    withSpan("database query") {
        db.query("SELECT * FROM orders")
    }
}

// Or use startSpan for manual control:
suspend fun manualSpan() {
    val span = ChronoTrace.startSpan("background-task")
    try {
        // work
    } finally {
        span.end()
    }
}
```

### Automatic Frame Capture with the Kotlin Plugin

When the `chronotrace-kotlin-plugin` Gradle plugin is applied (JVM only), the compiler plugin injects local variable capture automatically at `withTrace` and `withSpan` call sites. All local variables within the block are serialized into the frame snapshot:

```kotlin
// Gradle:
chronotrace {
    captureLocals.set(true)
}

// Application code — locals are injected by the compiler plugin:
withTrace("processPayment") {
    val cardToken = request.token    // captured in frame snapshot
    val amount = request.amount      // captured in frame snapshot
    val userId = getUserId()         // captured in frame snapshot
    paymentEngine.charge(cardToken, amount, userId)
}
```

Without the plugin, local variables are not captured unless you pass them explicitly via `captureLocals` in the options parameter (see the TypeScript section for the pattern).

### Context Propagation

Inject trace context into outgoing HTTP headers:

```kotlin
suspend fun callDownstream() {
    val headers = mutableMapOf<String, String>()
    ChronoTrace.injectHeaders(headers)

    // headers now contains:
    // - "traceparent": "00-{traceId}-{spanId}-01"
    // - "Chrono-Trace-Id": "{traceId}"
    // - "Chrono-Parent-Span-Id": "{spanId}"

    httpClient.post("http://other-service/api") {
        headers.forEach { (k, v) -> header(k, v) }
    }
}
```

Extract trace context from incoming headers:

```kotlin
suspend fun handleRequest(request: Request) {
    val headers = request.headers.toMap()
    val context = ChronoTrace.extractHeaders(headers)
    // Returns ChronoSpanContext(traceId, spanId, parentSpanId) or null
}
```

### Flush on Shutdown

The JVM SDK registers a shutdown hook that calls `flushFatal()` on process exit, ensuring all buffered records are sent before the process terminates.

---

## 4. Kotlin/JS and Kotlin/Wasm SDKs

The Kotlin Multiplatform SDK supports JS (browser and Node.js) and WasmJs targets with an identical API to the JVM SDK. The main difference is in platform initialization.

### Kotlin/JS

```kotlin
// commonMain — shared across all targets
import com.chronotrace.sdk.*

suspend fun main() {
    ChronoTrace.init(
        ChronoConfig(
            appId = "web-frontend",
            environment = "prod",
            serverUrl = "http://localhost:8080",
            auth = AuthConfig.ApiKey("ctk_your_key_here"),
        )
    )
}
```

### Kotlin/Wasm

Same API as JS. The WasmJs target runs in browser WebAssembly environments:

```kotlin
ChronoTrace.init(
    ChronoConfig(
        appId = "wasm-app",
        serverUrl = "http://localhost:8080",
    )
)
```

### Platform-Specific Notes

- **Stack context**: On JS/Wasm, the stack is captured using V8 stack frame parsing. Stack traces may be less complete than JVM.
- **Shutdown**: There is no shutdown hook on JS/Wasm. Call `ChronoTrace.shutdown()` explicitly if you need graceful flush before page unload.
- **Context propagation**: Use `extractHeaders`/`injectHeaders` the same way as JVM.

---

## 5. TypeScript/Node.js SDK

### Initialization

```typescript
import { ChronoTrace } from '@chronotrace/sdk-ts';

ChronoTrace.init({
    appId: 'my-node-service',
    environment: 'prod',
    serviceName: 'api-gateway',
    serverUrl: 'http://localhost:8080',
    auth: { mode: 'apiKey', apiKey: 'ctk_your_key_here' },
    runtime: 'node',
});
```

### Logging

```typescript
import { ChronoLogger } from '@chronotrace/sdk-ts';

async function handleOrder(orderId: string, amount: number) {
    await ChronoLogger.info('processing order', { orderId, amount });
    try {
        await processPayment(orderId, amount);
        await ChronoLogger.info('order complete', { orderId });
    } catch (err) {
        // With autoCaptureLevels: ["ERROR", "FATAL"], a frame snapshot is captured automatically
        await ChronoLogger.error('payment failed', { orderId, error: String(err) });
    }
}
```

### Spans and Traces

```typescript
import { withTrace, withSpan, startSpan } from '@chronotrace/sdk-ts';

async function processOrder(orderId: string) {
    // withTrace wraps a block — captures locals if the Vite plugin is applied
    return await withTrace('processOrder', async () => {
        await ChronoLogger.info('starting', { orderId });
        return await db.orders.findOne({ orderId });
    });
}

// startSpan returns a handle you can end manually
function backgroundTask() {
    const span = startSpan('background-sync', { attributes: { priority: 'low' } });
    doWork().finally(() => span.end());
}
```

### Manual Local Variable Capture

To capture specific local variables without the Vite plugin, pass `captureLocals` in the options:

```typescript
async function processPayment(cardToken: string, amount: number, userId: string) {
    return await withSpan('processPayment', async () => {
        // Pass captureLocals to include specific locals in the frame snapshot
        const result = await paymentEngine.charge({ cardToken, amount, userId });
        await ChronoLogger.info('payment done', { result: result.status });
        return result;
    }, { captureLocals: { cardToken, amount, userId } });
}
```

### Context Propagation

```typescript
import { ChronoTrace } from '@chronotrace/sdk-ts';

// Inject into outgoing HTTP headers
function callDownstream() {
    const headers: Record<string, string> = {};
    ChronoTrace.injectHeaders(headers);
    // headers: { traceparent, Chrono-Trace-Id, Chrono-Parent-Span-Id }

    return fetch('http://other-service/api', {
        headers: { ...headers, 'Content-Type': 'application/json' }
    });
}

// Extract from incoming headers
function handleRequest(req: Request) {
    const headers = req.headers;
    const ctx = ChronoTrace.extractHeaders(headers);
    // ctx: { traceId: string, spanId: string, parentSpanId: string | null } | null
}
```

### Using a WebSocket Transport

```typescript
import { ChronoTrace, WebSocketTransport } from '@chronotrace/sdk-ts';

ChronoTrace.init({
    appId: 'my-service',
    serverUrl: 'ws://localhost:8080',  // ws:// or wss:// for WebSocket
    // transport is chosen automatically based on URL scheme
});
```

### Node.js Context Manager

By default, the Node.js SDK uses `AsyncLocalStorage` to maintain trace context across asynchronous operations:

```typescript
import { createNodeChronoTrace } from '@chronotrace/sdk-ts/node';

// createNodeChronoTrace() returns a pre-configured ChronoTrace with
// AsyncLocalStorage context manager — use when you need async context propagation
const chrono = createNodeChronoTrace({
    appId: 'my-service',
    serverUrl: 'http://localhost:8080',
});

export { chrono };
```

---

## 6. TypeScript/Browser SDK

### Initialization

```typescript
import { ChronoTrace } from '@chronotrace/sdk-ts';

ChronoTrace.init({
    appId: 'web-frontend',
    environment: 'prod',
    serverUrl: 'https://chronotrace.example.com',
    auth: { mode: 'apiKey', apiKey: 'ctk_your_key_here' },
    runtime: 'browser',
});
```

### Vite Plugin for Automatic Local Capture

When using Vite, add `createChronoTraceVitePlugin()` to your `vite.config.ts`. This transforms your source code at build time to automatically capture local variables in `withTrace` / `withSpan` calls:

```typescript
// vite.config.ts
import { defineConfig } from 'vite';
import { createChronoTraceVitePlugin } from '@chronotrace/sdk-ts/vite';

export default defineConfig({
    plugins: [
        createChronoTraceVitePlugin({
            // Optional: filter which files to instrument (default: all)
            include: ['src/**/*.ts', 'src/**/*.tsx'],
        })
    ]
});
```

With the Vite plugin applied, this code:

```typescript
async function checkout(cartId: string, total: number) {
    return await withTrace('checkout', async () => {
        const cart = await getCart(cartId);
        await chargeCustomer(cart.userId, total);
        return { success: true };
    });
}
```

Has `cartId` and `total` automatically serialized into the frame snapshot, without any explicit `captureLocals` call.

### Manual Instrumentation for Non-Vite Projects

For browser projects not using Vite, use `instrumentSource` to transform code at runtime or build time:

```typescript
import { instrumentSource } from '@chronotrace/sdk-ts/instrumentation';

// instrumentSource(code, options) returns transformed code with capture calls injected
const transformed = instrumentSource(originalCode, {
    captureLocals: true,
    autoCaptureLevels: ['ERROR', 'FATAL'],
});
```

---

## 7. Context Propagation

ChronoTrace propagates trace context across service boundaries using HTTP headers. Two formats are supported simultaneously:

| Header | Description |
|--------|-------------|
| `traceparent` | W3C `traceparent` header. Format: `00-{traceId(32hex)}-{spanId(16hex)}-{flags}` |
| `Chrono-Trace-Id` | Chrono-specific trace ID header |
| `Chrono-Parent-Span-Id` | Chrono-specific parent span ID header |

All SDKs support `injectHeaders(carrier)` and `extractHeaders(carrier)`:

```kotlin
// Kotlin
val headers = mutableMapOf<String, String>()
ChronoTrace.injectHeaders(headers)  // adds traceparent, Chrono-Trace-Id, Chrono-Parent-Span-Id
val ctx = ChronoTrace.extractHeaders(headers)
```

```typescript
// TypeScript
const headers: Record<string, string> = {};
ChronoTrace.injectHeaders(headers);
const ctx = ChronoTrace.extractHeaders(headers);
```

Downstream services that call `extractHeaders` automatically join the same trace. The W3C `traceparent` header ensures compatibility with distributed tracing systems that use the standard format.

---

## 8. Server Installation and Configuration

### Running the Server

The server is a Kotlin/Ktor application packaged as a JAR. Run with:

```bash
java -jar chronotrace-server.jar
```

Or via Gradle:

```bash
./gradlew :chronotrace-server:run
```

### Environment Variables

All configuration is via environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `8080` | HTTP server port |
| `CHRONOTRACE_BIND_HOST` | `127.0.0.1` | Interface to bind to |
| `CHRONOTRACE_AUTH_MODE` | `none` | Auth mode: `none`, `apiKey`, `bearer` |
| `CHRONOTRACE_STORAGE_MODE` | `file` | Storage backend: `file`, `clickhouse`, or `memory` |
| `CHRONOTRACE_DATA_DIR` | (current dir) | Directory for file-based storage |
| `CHRONOTRACE_API_KEYS` | (none) | Comma-separated list of API keys |
| `CHRONOTRACE_BEARER_TOKENS` | (none) | Comma-separated list of bearer tokens |
| `CHRONOTRACE_RETENTION_LOGS_DAYS` | `30` | Log retention in days |
| `CHRONOTRACE_RETENTION_SPANS_DAYS` | `30` | Span retention in days |
| `CHRONOTRACE_RETENTION_FRAMES_DAYS` | `7` | Frame snapshot retention in days |
| `CHRONOTRACE_WS_IDLE_TIMEOUT_MS` | `60000` | WebSocket idle timeout in ms (set `0` to disable) |

### ClickHouse Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `CHRONOTRACE_CLICKHOUSE_JDBC_URL` | (none) | JDBC URL, e.g. `jdbc:clickhouse://localhost:8123` |
| `CHRONOTRACE_CLICKHOUSE_DATABASE` | `chronotrace` | Database name |
| `CHRONOTRACE_CLICKHOUSE_USERNAME` | (none) | Username |
| `CHRONOTRACE_CLICKHOUSE_PASSWORD` | (none) | Password |
| `CHRONOTRACE_CLICKHOUSE_CONNECT_TIMEOUT_MS` | `5000` | Connection timeout |
| `CHRONOTRACE_CLICKHOUSE_INGEST_QUEUE_CAPACITY` | `0` (sync) | Async ingest queue size (0 = synchronous) |
| `CHRONOTRACE_CLICKHOUSE_INGEST_QUEUE_TIMEOUT_MS` | `5000` | Queue offer timeout |
| `CHRONOTRACE_ASYNC_INSERT` | `false` | Use ClickHouse async inserts |
| `CHRONOTRACE_BOUNCE_ON_REJECTED` | `true` | Circuit breaker: reject new batches when queue is full |

### Valkey (Rate Limiting) Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `CHRONOTRACE_VALKEY_HOST` | (none) | Valkey/Redis host for per-key quota tracking |
| `CHRONOTRACE_VALKEY_PORT` | `6379` | Port |
| `CHRONOTRACE_VALKEY_DATABASE` | `0` | Database number |
| `CHRONOTRACE_VALKEY_PASSWORD` | (none) | Password |
| `CHRONOTRACE_VALKEY_KEY_PREFIX` | `chronotrace` | Key prefix |

When Valkey is configured, per-key sliding-window rate limits are enforced. Without Valkey, rate limiting is disabled.

### Docker Compose

For local development with ClickHouse:

```bash
docker compose up -d
```

This starts ClickHouse on port `19999` and the ChronoTrace server on port `8080`.

---

## 9. Storage Backends

### File Storage (default)

Records are stored as JSON files under `CHRONOTRACE_DATA_DIR`. Simple to operate, no external dependencies.

```
CHRONOTRACE_STORAGE_MODE=FILE
CHRONOTRACE_DATA_DIR=/var/lib/chronotrace/data
```

### ClickHouse Storage

High-performance columnar storage with async insert support, circuit breaker, and TTL-based retention policies defined per record type.

```
CHRONOTRACE_STORAGE_MODE=CLICKHOUSE
CHRONOTRACE_CLICKHOUSE_JDBC_URL=jdbc:clickhouse://localhost:8123
CHRONOTRACE_CLICKHOUSE_DATABASE=chronotrace
CHRONOTRACE_CLICKHOUSE_USERNAME=default
CHRONOTRACE_CLICKHOUSE_PASSWORD=secret
CHRONOTRACE_ASYNC_INSERT=true
CHRONOTRACE_BOUNCE_ON_REJECTED=true
CHRONOTRACE_RETENTION_LOGS_DAYS=30
CHRONOTRACE_RETENTION_SPANS_DAYS=30
CHRONOTRACE_RETENTION_FRAMES_DAYS=7
```

### In-Memory Storage

Records are held in process memory and lost on restart. Useful for tests.

```
CHRONOTRACE_STORAGE_MODE=IN_MEMORY
```

---

## 10. Authentication Modes

### `none` (default)

No authentication. All endpoints are open.

```
CHRONOTRACE_AUTH_MODE=none
```

### `apiKey`

API keys passed as `X-API-Key` header. Keys are checked against `CHRONOTRACE_API_KEYS`.

```
CHRONOTRACE_AUTH_MODE=apiKey
CHRONOTRACE_API_KEYS=ctk_key1,ctk_key2,ctk_key3
```

SDK usage:

```typescript
ChronoTrace.init({
    appId: 'my-app',
    serverUrl: 'http://localhost:8080',
    auth: { mode: 'apiKey', apiKey: 'ctk_key1' },
});
```

### `bearer`

Bearer tokens passed as `Authorization: Bearer <token>` header.

```
CHRONOTRACE_AUTH_MODE=bearer
CHRONOTRACE_BEARER_TOKENS=secret-token-1,secret-token-2
```

SDK usage:

```typescript
ChronoTrace.init({
    appId: 'my-app',
    serverUrl: 'http://localhost:8080',
    auth: { mode: 'bearer', token: 'secret-token-1' },
});
```

### `mTLS`

Mutual TLS authentication using a client certificate alias (requires server-side TLS configuration).

```typescript
ChronoTrace.init({
    appId: 'my-app',
    serverUrl: 'https://localhost:8443',
    auth: { mode: 'mTLS', clientCertificateAlias: 'my-cert' },
});
```

### Rate Limiting

When Valkey is configured, each API key is subject to a per-key sliding-window rate limit. Limits are configured per-key via the key management API (`POST /api/v1/admin/keys`). Without Valkey, rate limiting is disabled.

### Audit Logging

All auth-protected endpoints (`/api/v1/*`) log an audit entry containing: keyId, action, endpoint, HTTP method, outcome, status code, duration, and optionally the appId/sdkInstanceId. Audit logs are stored in-memory and can be retrieved via the key management API.

---

## 11. MCP Integration

ChronoTrace exposes 11 MCP tools for AI tooling integration. Connect any MCP-compatible AI client to the server at `http://localhost:8080/mcp`.

### Connecting an MCP Client

MCP clients connect via SSE (Server-Sent Events) over HTTP. The MCP endpoint is:

```
GET /mcp
```

Clients send `ToolCallRequest` objects and receive `ToolCallResponse` objects. The request/response schema is defined in `chronotrace-contract`.

### Available MCP Tools

#### `search_logs`

Search logs with filters. Returns newest-first results sorted by timestamp+sequence.

```json
{
  "name": "search_logs",
  "input": {
    "appId": "my-service",
    "environment": "prod",
    "level": "ERROR",
    "textQuery": "payment failed",
    "startTimeUtc": 1715000000000,
    "endTimeUtc": 1716000000000,
    "limit": 50
  }
}
```

#### `get_log`

Fetch a single log by its `logId`.

```json
{
  "name": "get_log",
  "input": { "logId": "log-abc123" }
}
```

#### `get_frame_snapshot`

Fetch a frame snapshot by `frameId` or by `logId` (to get the frame linked to a specific log).

```json
{
  "name": "get_frame_snapshot",
  "input": { "frameId": "frame-xyz789" }
}
```

```json
{
  "name": "get_frame_snapshot",
  "input": { "logId": "log-abc123" }
}
```

Returns `callStack` (array of `{functionName, filePath, lineNumber, columnNumber}`) and `localsJson` (JSON-encoded local variable map). Also includes `serializationMetadata` describing truncation, max-depth hits, redacted fields, and dropped fields.

#### `get_trace`

Fetch a complete trace: all spans, logs, and frame snapshots for a traceId, sorted temporally.

```json
{
  "name": "get_trace",
  "input": { "traceId": "abc123def456" }
}
```

#### `step_frames`

Navigate temporally adjacent frames. Takes a `frameId` and `direction` (`forward` or `backward`), returns up to `count` frames.

```json
{
  "name": "step_frames",
  "input": { "frameId": "frame-xyz789", "direction": "forward", "count": 3 }
}
```

#### `list_remote_rules`

List remote capture rules. Optionally filter by `appId`.

```json
{
  "name": "list_remote_rules",
  "input": { "appId": "my-service" }
}
```

#### `upsert_remote_rule`

Create or update a remote capture rule. Remote rules are CEL expressions pushed to connected SDKs, which evaluate them against each log emission and trigger frame capture when matched.

```json
{
  "name": "upsert_remote_rule",
  "input": {
    "rule": {
      "ruleId": "high-value-errors",
      "enabled": true,
      "targetApps": ["my-service"],
      "ttlSeconds": 3600,
      "priority": 10,
      "expression": "level == 'ERROR' && fields['amount'] > 1000",
      "captureMode": "auto_capture_level",
      "sampleLimit": 5,
      "createdBy": "ops-bot"
    }
  }
}
```

CEL expression variables available: `level` (string), `message` (string), `fields` (map of string→string), `traceId` (string), `appId` (string).

#### `delete_remote_rule`

Delete a rule by `ruleId`.

```json
{
  "name": "delete_remote_rule",
  "input": { "ruleId": "high-value-errors" }
}
```

#### `create_purge_job`

Submit an async purge job to delete logs matching a selector.

```json
{
  "name": "create_purge_job",
  "input": {
    "requestedBy": "ops-bot",
    "field": "appId",
    "value": "my-service"
  }
}
```

Valid `field` values: `appId`, `environment`, `traceId`, `spanId`.

Returns a `PurgeJob` with status `ACCEPTED`. Poll `get_purge_job` to track progress through `RUNNING` → `COMPLETED` (or `FAILED`).

#### `get_purge_job`

Poll purge job status.

```json
{
  "name": "get_purge_job",
  "input": { "purgeJobId": "purge-123456-1" }
}
```

Returns:

```json
{
  "purgeJobId": "purge-123456-1",
  "requestedAtUtc": 1715000000000,
  "requestedBy": "ops-bot",
  "selector": { "field": "appId", "value": "my-service" },
  "status": "COMPLETED",
  "completedAtUtc": 1715000010000,
  "recordsDeleted": 142
}
```

---

## 12. API Reference

### Public Endpoints (no auth)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/health` | System health + storage mode |
| GET | `/metrics` | Prometheus-format metrics |

### Protected Endpoints (require auth)

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/ingest` | Ingest a batch of logs, spans, frame snapshots |
| WS | `/api/v1/ingest/ws` | WebSocket ingest (bi-directional) |
| POST | `/api/v1/logs/search` | Search logs with filters |
| GET | `/api/v1/logs/{logId}` | Get a single log |
| GET | `/api/v1/frames/{frameId}` | Get a single frame snapshot |
| GET | `/api/v1/traces/{traceId}` | Get a full trace (all spans + logs + frames) |
| GET | `/api/v1/admin/keys` | List all API keys |
| POST | `/api/v1/admin/keys` | Create a new API key |
| POST | `/api/v1/admin/keys/{keyId}/rotate` | Rotate an API key |
| DELETE | `/api/v1/admin/keys/{keyId}` | Revoke an API key |
| GET | `/api/v1/admin/audit` | Query audit log (supports `?keyId=`, `?action=`, `?startTimeUtc=`, `?endTimeUtc=`) |

### Prometheus Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `chronotrace_ingest_total` | Counter | Total ingest calls |
| `chronotrace_ingest_errors_total` | Counter | Total ingest errors |
| `chronotrace_query_latency_ms` | Histogram | Query latency in ms |
| `chronotrace_websocket_connections` | Gauge | Active WebSocket connections |
| `chronotrace_ingest_queue_depth` | Gauge | Async ingest queue depth (ClickHouse only) |

### Health Response

```json
{
  "status": "ok",
  "storageMode": "clickhouse",
  "version": "0.1.0",
  "uptimeSeconds": 86400
}
```

---

## Quick-Start Summary

**1. Start the server:**

```bash
docker compose up -d  # starts ClickHouse + server
```

**2. Add the SDK:**

```bash
# TypeScript
npm install @chronotrace/sdk-ts

# Kotlin Multiplatform
// Add to build.gradle.kts: implementation("com.chronotrace:sdk-kmp:0.1.0")
```

**3. Initialize and log:**

```typescript
import { ChronoTrace, ChronoLogger } from '@chronotrace/sdk-ts';

ChronoTrace.init({
    appId: 'my-app',
    serverUrl: 'http://localhost:8080',
    auth: { mode: 'apiKey', apiKey: 'ctk_dev_key' },
});

await ChronoLogger.info('app started', { version: '1.0.0' });
```

```kotlin
import com.chronotrace.sdk.*

suspend fun main() {
    ChronoTrace.init(ChronoConfig(
        appId = "my-app",
        serverUrl = "http://localhost:8080",
        auth = AuthConfig.ApiKey("ctk_dev_key"),
    ))
    ChronoLogger.info("app started", mapOf("version" to "1.0.0"))
}
```

**4. Query traces:**

Connect your AI tooling to `http://localhost:8080/mcp` and use `search_logs` or `get_trace` to explore trace data.
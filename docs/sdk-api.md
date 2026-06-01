# ChronoTrace Kotlin SDK API Reference

**Version:** 1.0.0

This reference documents the public API surface of the ChronoTrace Kotlin Multiplatform SDK (sdk-kmp). All APIs are available across JVM, JS, and Wasm targets unless noted otherwise.

For installation and setup, see [Install and Build Guide](install.md). For usage patterns, see [User Manual](user-manual.md).

---

## 1. Initialization

### ChronoTrace.init

```kotlin
fun init(config: ChronoConfig)
```

Initialize the ChronoTrace SDK with the provided configuration. Must be called before any other SDK operations. Subsequent calls replace the existing runtime.

**Parameters:**
- `config: ChronoConfig` — Application configuration including service identity and transport

**Example:**
```kotlin
ChronoTrace.init(
    ChronoConfig(
        appId = "my-service",
        serviceName = "auth-service",
        environment = "production"
    )
)
```

### ChronoTrace.shutdown

```kotlin
suspend fun shutdown()
```

Flush all buffered logs and spans, then shut down the SDK runtime. After shutdown, the SDK is non-functional; call `init` again to restart. This is a suspending function to ensure all pending writes complete.

**Example:**
```kotlin
suspend fun cleanup() {
    ChronoTrace.shutdown()
}
```

---

## 2. Configuration

### ChronoConfig

```kotlin
data class ChronoConfig(
    val appId: String,
    val serviceName: String,
    val environment: String = "local",
    val sdkInstanceId: String = ChronoIds.nextId("sdk"),
    val captureConfig: CaptureConfig = CaptureConfig(),
    val bufferConfig: BufferConfig = BufferConfig(),
    val transport: ChronoTransport = NoopTransport,
)
```

Root configuration for the SDK.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `appId` | `String` | (required) | Application identifier, used in trace correlation |
| `serviceName` | `String` | (required) | Name of this service, appears in server-side records |
| `environment` | `String` | `"local"` | Deployment environment (e.g., `"production"`, `"staging"`) |
| `sdkInstanceId` | `String` | auto-generated | Unique instance identifier for this SDK process |
| `captureConfig` | `CaptureConfig` | `CaptureConfig()` | Controls automatic instrumentation and data limits |
| `bufferConfig` | `BufferConfig` | `BufferConfig()` | Controls in-memory buffering behavior |
| `transport` | `ChronoTransport` | `NoopTransport` | Backend transport for ingested records |

### CaptureConfig

```kotlin
data class CaptureConfig(
    val autoCaptureLevels: Set<LogLevel> = setOf(LogLevel.ERROR, LogLevel.FATAL),
    val maxCollectionEntries: Int = 50,
    val maxStringLength: Int = 4_096,
    val maxPayloadBytes: Int = 256 * 1024,
    val maxSerializationDepth: Int = 3,
    val maskingKeys: List<Regex> = listOf(Regex("password", RegexOption.IGNORE_CASE), Regex("token", RegexOption.IGNORE_CASE)),
    val maskingValues: List<Regex> = emptyList(),
    val allowFieldPatterns: List<Regex> = emptyList(),
)
```

Controls automatic instrumentation and data capture limits.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `autoCaptureLevels` | `Set<LogLevel>` | `{ERROR, FATAL}` | Log levels that trigger automatic span capture for exceptions |
| `maxCollectionEntries` | `Int` | `50` | Maximum number of entries in collections before truncation |
| `maxStringLength` | `Int` | `4096` | Maximum string length before truncation |
| `maxPayloadBytes` | `Int` | `262144` | Maximum serialized payload size in bytes |
| `maxSerializationDepth` | `Int` | `3` | Maximum depth for nested object serialization |
| `maskingKeys` | `List<Regex>` | `["password", "token"]` (case-insensitive) | Field names matching these patterns are masked |
| `maskingValues` | `List<Regex>` | `[]` | Value patterns to mask (unused by default) |
| `allowFieldPatterns` | `List<Regex>` | `[]` | Exceptions to masking rules — matching fields are not masked even if their key matches a masking pattern |

### BufferConfig

```kotlin
data class BufferConfig(
    val maxEntries: Int = 512,
    val overflowStrategy: OverflowStrategy = OverflowStrategy.DROP_OLDEST,
)
```

Controls in-memory buffering of logs and spans before transport.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `maxEntries` | `Int` | `512` | Maximum number of records held in the buffer |
| `overflowStrategy` | `OverflowStrategy` | `DROP_OLDEST` | Strategy used when the buffer is full |

### OverflowStrategy

```kotlin
enum class OverflowStrategy {
    DROP_OLDEST,
    DROP_NEWEST,
}
```

Determines what happens when the buffer exceeds `maxEntries`.

| Value | Behavior |
|-------|----------|
| `DROP_OLDEST` | Discard the oldest record to make room for new ones |
| `DROP_NEWEST` | Discard the incoming record and keep existing ones |

---

## 3. Logging

All logging methods are `suspend` functions. They return `Unit` and emit records to the configured transport through an in-memory buffer.

### ChronoLogger.trace

```kotlin
suspend fun trace(message: String, fields: Map<String, Any?> = emptyMap())
```

Emit a TRACE-level log record.

### ChronoLogger.debug

```kotlin
suspend fun debug(message: String, fields: Map<String, Any?> = emptyMap())
```

Emit a DEBUG-level log record.

### ChronoLogger.info

```kotlin
suspend fun info(message: String, fields: Map<String, Any?> = emptyMap())
```

Emit an INFO-level log record.

### ChronoLogger.warn

```kotlin
suspend fun warn(message: String, fields: Map<String, Any?> = emptyMap())
```

Emit a WARN-level log record.

### ChronoLogger.error

```kotlin
suspend fun error(message: String, fields: Map<String, Any?> = emptyMap())
```

Emit an ERROR-level log record.

### ChronoLogger.fatal

```kotlin
suspend fun fatal(message: String, fields: Map<String, Any?> = emptyMap())
```

Emit a FATAL-level log record.

**Parameters (all logging methods):**
- `message: String` — Log message text
- `fields: Map<String, Any?>` — Additional structured fields to attach; values are serialized with the record

**Example:**
```kotlin
suspend fun authenticate(userId: String) {
    ChronoLogger.info("Authentication attempt", mapOf("userId" to userId))
    try {
        // auth logic
        ChronoLogger.debug("Authentication successful", mapOf("userId" to userId))
    } catch (e: Exception) {
        ChronoLogger.error("Authentication failed", mapOf("userId" to userId, "error" to e.message))
    }
}
```

---

## 4. Spans and Traces

### ChronoTrace.startSpan

```kotlin
suspend fun startSpan(name: String): SpanHandle
```

Start a new span with the given name. The span is created as a child of the current context if one exists.

**Parameters:**
- `name: String` — Span name, typically the operation being measured

**Returns:**
- `SpanHandle` — Handle to end the span

**Example:**
```kotlin
suspend fun processOrder(orderId: String): OrderResult {
    val span = ChronoTrace.startSpan("processOrder")
    return try {
        // work
        OrderResult.Success
    } finally {
        span.end()
    }
}
```

### SpanHandle.end

```kotlin
suspend fun end()
```

End the span. Call this in a `finally` block to ensure the span is closed even if an exception is thrown. Calling `end()` multiple times on the same handle is safe.

### withTrace

```kotlin
suspend fun <T> withTrace(name: String, block: suspend () -> T): T
```

Execute a block within a new span. The span is automatically ended when the block completes (normally or exceptionally). This is the recommended way to instrument suspended functions.

**Parameters:**
- `name: String` — Span name
- `block: suspend () -> T` — Suspended block to execute

**Returns:**
- `T` — The result of the block

**Example:**
```kotlin
suspend fun fetchUser(userId: String): User {
    return withTrace("fetchUser") {
        // span automatically started and ended around this block
        userRepository.findById(userId)
    }
}
```

### withSpan

```kotlin
suspend fun <T> withSpan(name: String, block: suspend () -> T): T
```

Alias for `withTrace`. Provided for ergonomic symmetry with `startSpan`.

### ChronoTrace.currentContext

```kotlin
fun currentContext(): ChronoSpanContext?
```

Return the current trace context, if any. Returns `null` if no span is active in the current coroutine context.

**Returns:**
- `ChronoSpanContext?` — Current span context or `null`

---

## 5. Context Propagation

### ChronoTrace.injectHeaders

```kotlin
fun injectHeaders(carrier: MutableMap<String, String>, context: ChronoSpanContext? = currentContext())
```

Inject the current trace context into a mutable map of HTTP headers. This propagates context across service boundaries via HTTP.

**Parameters:**
- `carrier: MutableMap<String, String>` — Mutable map to write header values into (typically HTTP request headers)
- `context: ChronoSpanContext?` — Context to inject; defaults to `currentContext()`; if `null`, this method does nothing

**Injected Headers:**
- `traceparent` — W3C Trace Context format (`00-{traceId}-{spanId}-01`)
- `Chrono-Trace-Id` — Raw trace ID
- `Chrono-Parent-Span-Id` — Raw parent span ID

**Example:**
```kotlin
suspend fun outgoingRequest(url: String, body: String) {
    val headers = mutableMapOf<String, String>()
    ChronoTrace.injectHeaders(headers)

    val request = HttpRequest.post(url)
        .header("Content-Type", "application/json")
        .headers(headers)

    client.execute(request, body)
}
```

### ChronoTrace.extractHeaders

```kotlin
fun extractHeaders(carrier: Map<String, String>): ChronoSpanContext?
```

Extract trace context from an immutable map of HTTP headers. Used on the receiving side to continue a trace from incoming headers.

**Parameters:**
- `carrier: Map<String, String>` — Map of HTTP headers to extract from

**Returns:**
- `ChronoSpanContext?` — Extracted context, or `null` if no valid trace context is found

**Example:**
```kotlin
suspend fun handleIncomingRequest(headers: Map<String, String>): Response {
    val context = ChronoTrace.extractHeaders(headers)
    return withTrace("handleRequest") {
        // incoming trace context automatically becomes parent
        handler.route(headers, context)
    }
}
```

---

## 6. Runtime Health

### ChronoTrace.runtimeHealth

```kotlin
suspend fun runtimeHealth(): RuntimeHealth
```

Return a snapshot of SDK runtime health including buffer occupancy, dropped record counts, and error state.

**Returns:**
- `RuntimeHealth` — Current health snapshot

### RuntimeHealth

```kotlin
data class RuntimeHealth(
    val state: RuntimeState,
    val droppedLogs: Int,
    val droppedSpans: Int,
    val droppedFrames: Int,
    val bufferedLogs: Int,
    val bufferedSpans: Int,
    val bufferedFrames: Int,
    val fatalFlushes: Int,
    val lastFlushError: String? = null,
)
```

Current runtime health snapshot.

| Field | Type | Description |
|-------|------|-------------|
| `state` | `RuntimeState` | Current operational state |
| `droppedLogs` | `Int` | Total log records dropped due to buffer overflow |
| `droppedSpans` | `Int` | Total spans dropped due to buffer overflow |
| `droppedFrames` | `Int` | Total local variable frames dropped |
| `bufferedLogs` | `Int` | Logs currently held in the buffer |
| `bufferedSpans` | `Int` | Spans currently held in the buffer |
| `bufferedFrames` | `Int` | Local variable frames currently in buffer |
| `fatalFlushes` | `Int` | Number of fatal flush errors encountered |
| `lastFlushError` | `String?` | Message of the most recent flush error, if any |

### RuntimeState

```kotlin
enum class RuntimeState {
    CONNECTED,
    DEGRADED_BUFFERING,
    RECONNECT_BACKOFF,
    LOCAL_FALLBACK,
    FATAL_FLUSH,
}
```

Possible runtime operational states.

| Value | Description |
|-------|-------------|
| `CONNECTED` | Normal operation; records are being sent to the server |
| `DEGRADED_BUFFERING` | Server is slow or unreachable; records are being buffered |
| `RECONNECT_BACKOFF` | SDK is backing off before retrying connection |
| `LOCAL_FALLBACK` | Server is unavailable; records are being written to local fallback storage |
| `FATAL_FLUSH` | A fatal flush error occurred; consult `lastFlushError` |

---

## 7. Transport

### ChronoTransport

```kotlin
interface ChronoTransport {
    suspend fun send(batch: IngestBatch)
}
```

Interface for implementing custom transport backends. The SDK calls `send` to deliver batches of ingested records.

**Methods:**
- `suspend fun send(batch: IngestBatch)` — Send a batch of records to the backend

### NoopTransport

```kotlin
object NoopTransport : ChronoTransport {
    override suspend fun send(batch: IngestBatch) = Unit
}
```

Default no-op transport. Records are dropped silently. Use this for testing or when no backend is configured.

### RecordingTransport

```kotlin
class RecordingTransport : ChronoTransport {
    override suspend fun send(batch: IngestBatch)
    fun sentBatches(): List<IngestBatch>
}
```

Test transport that records all batches in memory without sending anywhere. Use in tests to verify SDK behavior.

**Example:**
```kotlin
@Test
fun testSpanRecording() {
    val transport = RecordingTransport()
    ChronoTrace.init(ChronoConfig(
        appId = "test",
        serviceName = "test-service",
        transport = transport
    ))

    runBlocking {
        withTrace("test-span") { }
        ChronoTrace.shutdown()
    }

    val batches = transport.sentBatches()
    assert(batches.isNotEmpty())
}
```

---

## 8. Data Models

### ChronoSpanContext

```kotlin
data class ChronoSpanContext(
    val traceId: String,
    val spanId: String,
    val parentSpanId: String? = null,
)
```

Represents an active trace span.

| Field | Type | Description |
|-------|------|-------------|
| `traceId` | `String` | Global trace identifier |
| `spanId` | `String` | Local span identifier within the trace |
| `parentSpanId` | `String?` | Parent span ID if this is a child span, otherwise `null` |
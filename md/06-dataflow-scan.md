# Scan: Data Flow

## Files Scanned

### Kotlin Multiplatform SDK
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoCapture.kt`
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoBuffer.kt`
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoRuntime.kt`

### TypeScript SDK
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/buffer.ts`
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/capture.ts`
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/client.ts`
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/transports/httpTransport.ts`
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/transports/webSocketTransport.ts`

### Server
- `/home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoStore.kt`
- `/home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoStorage.kt`

Note: `ClickHouseStorage.kt` does not exist as a separate file. The ClickHouse implementation is defined inline within `ChronoStore.kt` as the `ClickHouseChronoStorage` class.

---

## SDK Capture Pipeline

### Kotlin Multiplatform SDK (JVM/Native)

**Entry Points:**
- `ChronoRuntime.log(level, message, fields)` - Logs a message with optional fields
- `ChronoRuntime.startSpan(name, captureLocals)` - Starts a new span with optional local variable capture
- `ChronoRuntime.endSpan(span, previous)` - Ends a span

**Capture Flow:**

1. **Field Splitting** (`splitCaptureFields` in ChronoCapture.kt):
   - Fields are split into `logFields` (public fields) and `captureLocals` (internal `__chronotrace_locals`)
   - This separates user-visible log fields from internal frame snapshot locals

2. **Frame Snapshot Creation** (`ChronoCapture.createFrameSnapshot`):
   - For every log event with fields, a `FrameSnapshot` is created
   - For span start with `captureLocals`, a frame snapshot is captured
   - The frame contains:
     - Call stack captured via `captureCallStack()` (expect function)
     - Serialized local variables as JSON (`localsJson`)
     - Serialization metadata (truncation status, redacted fields, dropped fields)
   - Payload size is checked against `maxPayloadBytes`; if exceeded, locals are truncated to `[Truncated]`

3. **Serialization Rules** (ChronoCapture.kt):
   - Depth limit via `maxSerializationDepth`
   - Collection entry limit via `maxCollectionEntries`
   - String length limit via `maxStringLength`
   - Field allowlist patterns control what gets captured
   - Masking patterns redact sensitive values
   - Circular references detected via `seen` Set, marked as `[Circular]`
   - Truncated paths marked as `[Truncated]`

4. **Buffering** (`ChronoBuffer<T>`):
   - Three separate buffers: `logBuffer`, `spanBuffer`, `frameBuffer`
   - Each buffer has configurable `maxEntries` and `OverflowStrategy` (DROP_OLDEST or DROP_NEWEST)
   - Buffers use `ArrayDeque<T>` for efficient add/remove at both ends

5. **Runtime State** (`ChronoRuntime`):
   - Tracks `droppedLogs`, `droppedSpans`, `droppedFrames` counters
   - Manages runtime states: `CONNECTED`, `RECONNECT_BACKOFF`, `DEGRADED_BUFFERING`, `LOCAL_FALLBACK`, `FATAL_FLUSH`

### TypeScript SDK (Browser/Node)

**Entry Points:**
- `ChronoTraceClient.log(level, message, fields)` - Logs with auto-capture support
- `ChronoTraceClient.startSpan(name, options)` - Starts span with `captureLocals` option
- `ChronoTraceClient.withSpan(name, fn, options)` - Wrapper that automatically ends span

**Capture Flow:**

1. **Field Splitting** (`splitCaptureFields` in capture.ts):
   - Identical logic to Kotlin SDK: separates `__chronotrace_locals` from public fields

2. **Frame Snapshot Creation** (`createFrameSnapshot` in capture.ts):
   - Call stack captured via `captureCallStack()` which parses `Error.stack`
   - Supports V8 (Chrome/Node) and Firefox stack trace formats
   - Filters out internal ChronoTrace frames from stack

3. **Serialization** (`serializeValue` in capture.ts):
   - Supports additional JavaScript types: `bigint`, `symbol`, `function`, `Date`, `Error`, `Promise`
   - `Promise` instances captured as `[Promise]`
   - `WeakSet<object>` used for circular reference detection

4. **Buffering** (`buffer.ts`):
   - `RingBuffer<T>` wraps `MemoryQueue<T>`
   - `MemoryQueue` tracks size in bytes (not count), using `estimateBytes()`
   - Strategies: `DROP_NEWEST`, `BLOCK_CALLER`, `DROP_OLDEST`
   - Size-based overflow detection prevents individual oversized events

5. **Remote Rules**:
   - Client supports dynamic rule evaluation via `evaluateRule()`
   - Rules can trigger auto-capture based on field content

---

## Transport Layer

### TypeScript SDK Transports

**HttpTransport** (`httpTransport.ts`):
- Sends `IngestBatch` as JSON via HTTP POST
- Content-Type: `application/json`
- Retries on 503 with exponential backoff (base 100ms, max 3 retries)
- Non-503 errors are not retried

**WebSocketTransport** (`webSocketTransport.ts`):
- Sends `IngestBatch` as JSON via WebSocket
- Bidirectional: also receives `Command` messages (e.g., `upsert_rule`, `delete_rule`)
- Commands processed via `setCommandHandler()` callback
- Requires explicit `connect()` call before `send()`

**NoopTransport**:
- Used when no `serverUrl` is configured
- All operations are no-ops; client operates in `LOCAL_FALLBACK` mode

### Kotlin Multiplatform SDK Transport

The Kotlin SDK uses the `ChronoTransport` interface (from contract) passed in via `ChronoConfig`:
- Configured via `config.transport`
- If `config.transport === NoopTransport`, runtime state is `LOCAL_FALLBACK`
- Flush sends `IngestBatch` to transport

**IngestBatch Structure**:
```typescript
interface IngestBatch {
  client: ClientMetadata;  // appId, environment, sdkInstanceId, serviceName
  logs: LogRecord[];
  spans: SpanRecord[];
  frameSnapshots: FrameSnapshot[];
}
```

### Common Data Types

**LogRecord**: logId, appId, environment, sdkInstanceId, serviceName, traceId, spanId, parentSpanId, timestampUtc, sequenceId, level, message, fields (sanitized to strings), captureReason, linkedFrameId

**SpanRecord**: spanId, traceId, appId, environment, serviceName, operationName, parentSpanId, startTimeUtc, endTimeUtc, status, attributes (sanitized)

**FrameSnapshot**: frameId, traceId, spanId, appId, environment, sdkInstanceId, serviceName, timestampUtc, sequenceId, captureReason, callStack (array of CallStackItem), localsJson (raw JSON string), serializationMetadata, logId

---

## Server Ingest Pipeline

### Endpoint

The server exposes an ingest endpoint (HTTP POST) that receives `IngestBatch`.

### ChronoStore.ingest() Flow

1. **Entry**: `ChronoStore.ingest(batch)` delegates to `storage.ingest(batch)`

2. **Queue Circuit Breaker** (ClickHouse mode only):
   - If `ingestQueueCapacity > 0`, uses `LinkedBlockingQueue<Runnable>` with single-threaded executor
   - `tryOfferBatch()` attempts to enqueue with `ingestQueueTimeoutMs` deadline
   - If queue full, throws `IngestRejectedException` → HTTP 503
   - If `ingestQueueCapacity == 0`, runs synchronously (`doIngestSync`)

3. **doIngestSync()**:
   - Gets JDBC connection from `DriverManager.getConnection()`
   - Executes three batch inserts: `insertLogs()`, `insertSpans()`, `insertFrames()`
   - Each INSERT uses `PreparedStatement.addBatch()` / `executeBatch()`
   - No explicit transactions (ClickHouse auto-commits each INSERT)

### ClickHouse Schema

**logs table**:
```sql
CREATE TABLE logs (
  log_id String,
  app_id String,
  environment String,
  sdk_instance_id String,
  service_name String,
  trace_id Nullable(String),
  span_id Nullable(String),
  parent_span_id Nullable(String),
  timestamp_utc Int64,
  sequence_id Int64,
  level String,
  message String,
  fields_json String,          -- JSON map of sanitized string fields
  capture_reason Nullable(String),
  linked_frame_id Nullable(String)
) ENGINE = MergeTree()
  ORDER BY (app_id, timestamp_utc, sequence_id)
  TTL toDateTime(timestamp_utc / 1000) + INTERVAL {retentionDaysLogs} DAY
```

**spans table**:
```sql
CREATE TABLE spans (
  span_id String,
  trace_id String,
  app_id String,
  environment String,
  service_name String,
  operation_name String,
  parent_span_id Nullable(String),
  start_time_utc Int64,
  end_time_utc Nullable(Int64),
  status String,
  attributes_json String
) ENGINE = MergeTree()
  ORDER BY (app_id, start_time_utc, span_id)
  TTL toDateTime(start_time_utc / 1000) + INTERVAL {retentionDaysSpans} DAY
```

**frame_snapshots table**:
```sql
CREATE TABLE frame_snapshots (
  frame_id String,
  trace_id String,
  span_id String,
  app_id String,
  environment String,
  sdk_instance_id String,
  service_name String,
  timestamp_utc Int64,
  sequence_id Int64,
  capture_reason Nullable(String),
  call_stack_json String,       -- JSON array of CallStackItem
  locals_json String,          -- Raw JSON of captured locals
  serialization_metadata_json String,
  log_id Nullable(String)
) ENGINE = MergeTree()
  ORDER BY (app_id, timestamp_utc, sequence_id)
  TTL toDateTime(timestamp_utc / 1000) + INTERVAL {retentionDaysFrames} DAY
```

### Storage Implementations

**ClickHouseChronoStorage** (in ChronoStore.kt):
- JDBC-based using ClickHouse's JDBC driver
- Bootstrap creates database and tables if not exist
- Connection per-operation (no pooling visible in code)
- TTL-based automatic data expiration

**FileChronoStorage**:
- JSON file-based storage for development
- Not examined in detail

**InMemoryChronoStorage**:
- In-memory lists for logs, spans, frames
- Used when no dataDir specified in FILE mode

---

## Storage Layer

### Insert Operations

1. **Log Insert**: Each log field map is JSON-serialized to `fields_json` string
2. **Span Insert**: Span attributes map JSON-serialized to `attributes_json` string  
3. **Frame Insert**: 
   - `call_stack` array serialized to `call_stack_json`
   - `localsJson` stored as-is (already a JSON string from SDK)
   - `serializationMetadata` JSON-serialized to `serialization_metadata_json`

### Query Operations

**searchLogs(request)**:
- Builds dynamic SQL with WHERE clauses for: startTimeUtc, endTimeUtc, appId, environment, level, traceId, spanId, textQuery (case-insensitive substring), hasFrame
- Returns `SearchLogsResponse` with list of matching `LogRecord`

**getLog(logId)**: Single log lookup by log_id

**getFrame(frameId)**: Single frame lookup by frame_id

**getFrameByLog(logId)**: Frame linked to a specific log (via linked_frame_id)

**getTrace(traceId)**: 
- Fetches all spans for traceId
- Fetches all logs for traceId  
- Fetches all frame snapshots for traceId
- Returns `TraceView` containing all three lists ordered by timestamp

**stepFrame(frameId, direction, count)**:
- Used for navigating time-sorted frame snapshots
- `direction` = "forward" or "backward"
- Returns previous/next N frames based on timestampUtc/sequenceId ordering

### Retention

ClickHouse TTL expressions automatically delete old data:
- Logs: `timestamp_utc + INTERVAL retentionDaysLogs DAY`
- Spans: `start_time_utc + INTERVAL retentionDaysSpans DAY`
- Frames: `timestamp_utc + INTERVAL retentionDaysFrames DAY`

---

## Query Pipeline

### Search API

1. Client sends `SearchLogsRequest` to server
2. Server routes to `ChronoStorage.searchLogs()`
3. ClickHouseStorage builds parameterized SQL query
4. Results mapped back to `LogRecord` objects via `logFromRow()`
5. Response includes list of matching logs

### Trace View API

1. Client requests `getTrace(traceId)` 
2. Server fetches spans, logs, frames independently
3. Each query filters by traceId and orders by timestamp
4. Returns aggregated `TraceView`

### Frame Navigation

1. Client requests `stepFrame(frameId, direction, count)`
2. Server fetches current frame to get timestampUtc/sequenceId
3. Builds query for frames before/after current frame
4. Returns list of frames in requested direction

---

## Data Transformation Points

### 1. Field Sanitization (SDK Capture)

**Kotlin** (`ChronoCapture.sanitizeLogFields`):
- Recursively serializes values using `Json` from kotlinx.serialization
- Applies masking rules (pattern matching on values)
- Applies deny patterns (key-based redaction)
- Returns `Map<String, String>` (all values as strings)

**TypeScript** (`sanitizeLogFields` in capture.ts):
- Uses `serializeValue()` to convert fields to JSON-compatible structure
- Then `renderLogFieldValue()` converts each value to string
- Strings stay as-is; other types JSON.stringify'd

### 2. Payload Size Check (SDK Capture)

Both SDKs check if serialized `localsJson` exceeds `maxPayloadBytes`:
- If exceeded, set `truncated = true`, add `"$payload"` to droppedFields
- Replace locals with just `"[Truncated]"` marker

### 3. JSON Serialization (Server Storage)

**Kotlin Server** uses `kotlinx.serialization.Json`:
- `json.encodeToString(MapSerializerStringString, map)` for fields/attributes
- `json.encodeToString(ListSerializer(CallStackItemSerializer), list)` for call stacks
- `json.encodeToString(SerializationMetadataSerializer, metadata)` for serialization metadata
- All complex types stored as JSON strings in ClickHouse

### 4. Timestamp Handling

- SDKs capture timestamps as `Long` (milliseconds since epoch UTC)
- ClickHouse stores as `Int64`
- Query responses convert back to SDK's timestamp type

### 5. Context Propagation Headers

**Injection** (TypeScript SDK):
```
traceparent: 00-{traceId}-{spanId}-01
Chrono-Trace-Id: {traceId}
Chrono-Parent-Span-Id: {spanId}
```

**Extraction** supports both W3C `traceparent` format and Chrono-specific headers.

---

## Summary Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                        SDK (Kotlin / TypeScript)                    │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  User Code ──► log() / startSpan() / withSpan()                    │
│                    │                                                 │
│                    ▼                                                 │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │ ChronoCapture (sanitizeLogFields, createFrameSnapshot)       │   │
│  │ - Split fields (logFields vs captureLocals)                   │   │
│  │ - Serialize locals to JSON                                     │   │
│  │ - Check payload size vs maxPayloadBytes                        │   │
│  │ - Apply masking/redaction/depth limits                         │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                    │                                                 │
│                    ▼                                                 │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │ ChronoBuffer / RingBuffer (DROP_OLDEST / DROP_NEWEST)         │   │
│  │ - logBuffer: LogRecord[]                                      │   │
│  │ - spanBuffer: SpanRecord[]                                    │   │
│  │ - frameBuffer: FrameSnapshot[]                               │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                    │                                                 │
│                    ▼                                                 │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │ ChronoRuntime.flush() / flushFatal()                         │   │
│  │ - Drain all buffers into IngestBatch                         │   │
│  │ - transport.send(batch)                                      │   │
│  │ - On failure: restore batch to buffers (prependAll)         │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     Transport Layer                                 │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  HttpTransport:  HTTP POST (application/json)                       │
│                   - Retry on 503 with exponential backoff           │
│                   - Max 3 retries                                   │
│                                                                     │
│  WebSocketTransport: WebSocket.send(JSON.stringify(batch))         │
│                   - Supports command messages (upsert_rule)         │
│                   - Requires connect() before send()               │
│                                                                     │
│  NoopTransport: No-op (local fallback mode)                        │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     Server Ingest Pipeline                          │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  HTTP Endpoint ──► ChronoStore.tryOfferBatch()                     │
│                           │                                         │
│                           ▼ (or circuit breaker)                   │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │ LinkedBlockingQueue<Runnable> (ClickHouse only)             │   │
│  │ - Capacity: ingestQueueCapacity                             │   │
│  │ - Timeout: ingestQueueTimeoutMs                             │   │
│  │ - 503 if queue full                                         │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                           │                                         │
│                           ▼                                         │
│  doIngestSync(): JDBC batch INSERT                                  │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │ - insertLogs() → logs table                                 │   │
│  │ - insertSpans() → spans table                               │   │
│  │ - insertFrames() → frame_snapshots table                    │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     ClickHouse Storage                             │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  Tables:                                                           │
│  ┌──────────────┬────────────────────────────────────────────┐    │
│  │ logs          │ ORDER BY (app_id, timestamp_utc, sequence_id)│    │
│  │ spans         │ ORDER BY (app_id, start_time_utc, span_id)   │    │
│  │ frame_snapshots │ ORDER BY (app_id, timestamp_utc, sequence_id)│ │
│  └──────────────┴────────────────────────────────────────────┘    │
│                                                                     │
│  JSON columns (stored as strings):                                 │
│  - fields_json (log fields map)                                    │
│  - attributes_json (span attributes map)                            │
│  - call_stack_json (array of CallStackItem)                        │
│  - serialization_metadata_json                                      │
│  - locals_json (raw JSON from SDK)                                 │
│                                                                     │
│  Retention: TTL expressions (auto-delete old data)                │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     Query Pipeline                                 │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  searchLogs() ──► Dynamic SQL with WHERE clauses                  │
│                     ORDER BY timestamp_utc, sequence_id            │
│                     LIMIT (max 500)                                │
│                                                                     │
│  getTrace(traceId) ──► Three parallel queries:                   │
│                        spans WHERE trace_id                        │
│                        logs WHERE trace_id                         │
│                        frames WHERE trace_id                       │
│                                                                     │
│  stepFrame(frameId, direction) ──► Temporal navigation             │
│                        timestamp_utc < or > current                 │
│                        sequence_id < or > current                  │
│                                                                     │
│  getLog(logId) ──► Single row lookup                               │
│  getFrame(frameId) ──► Single row lookup                           │
│  getFrameByLog(logId) ──► linked_frame_id index                   │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```
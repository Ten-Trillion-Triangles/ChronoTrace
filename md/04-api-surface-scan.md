# Scan: API Surface

## Files Scanned

### Server Kotlin Files
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoTraceServer.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ServerModule.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/AuthTypes.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoStoreOptions.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoStore.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoStoreBackend.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/McpTooling.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/McpModels.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ServerMetrics.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoPurgeState.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoStorage.kt

### TypeScript SDK Files
- /home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/index.ts
- /home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/client.ts
- /home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/types.ts
- /home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/config.ts
- /home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/transport.ts
- /home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/transports/httpTransport.ts
- /home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/transports/webSocketTransport.ts
- /home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/generated/contracts.ts

### Contract Files
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-contract/src/commonMain/kotlin/org/chronotrace/contract/ChronoContracts.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-contract/src/jvmMain/kotlin/org/chronotrace/contract/TypeScriptContractGenerator.kt

---

## Server Endpoints (Ktor)

All endpoints are defined in `ServerModule.kt` inside `Application.chronoTraceModule(store: ChronoStore)`.

### Public Endpoints (no auth)

| Method | Path | Handler Function | File:Line |
|--------|------|------------------|-----------|
| GET | `/health` | inline in routing | ServerModule.kt:75-77 |
| GET | `/metrics` | inline in routing | ServerModule.kt:79-87 |

### Protected Endpoints (auth + quota + audit)

| Method | Path | Handler Function | File:Line |
|--------|------|------------------|-----------|
| POST | `/api/v1/ingest` | inline in routing | ServerModule.kt:91-126 |
| WS | `/api/v1/ingest/ws` | inline in routing | ServerModule.kt:128-166 |
| POST | `/api/v1/logs/search` | inline in routing | ServerModule.kt:168-196 |
| GET | `/api/v1/logs/{logId}` | inline in routing | ServerModule.kt:198-218 |
| GET | `/api/v1/frames/{frameId}` | inline in routing | ServerModule.kt:220-240 |
| GET | `/api/v1/traces/{traceId}` | inline in routing | ServerModule.kt:242-261 |
| POST | `/api/v1/remote-rules` | inline in routing | ServerModule.kt:263-282 |
| GET | `/api/v1/remote-rules` | inline in routing | ServerModule.kt:284-302 |
| POST | `/api/v1/purge` | inline in routing | ServerModule.kt:304-327 |
| GET | `/api/v1/purge/{purgeJobId}` | inline in routing | ServerModule.kt:329-348 |
| POST | `/mcp` | inline in routing | ServerModule.kt:350-402 |

### Admin Endpoints (auth + admin role + audit)

| Method | Path | Handler Function | File:Line |
|--------|------|------------------|-----------|
| GET | `/api/v1/admin/keys` | inline in routing | ServerModule.kt:407-450 |
| POST | `/api/v1/admin/keys` | inline in routing | ServerModule.kt:452-510 |
| POST | `/api/v1/admin/keys/{keyId}/rotate` | inline in routing | ServerModule.kt:512-546 |
| DELETE | `/api/v1/admin/keys/{keyId}` | inline in routing | ServerModule.kt:548-580 |
| GET | `/api/v1/admin/audit/logs` | inline in routing | ServerModule.kt:582-618 |

---

## WebSocket Routes

| Path | Handler | File:Line |
|------|---------|-----------|
| `/api/v1/ingest/ws` | inline in routing — receives JSON IngestBatch frames, sends `{"accepted":true}` or `{"error":"..."}` | ServerModule.kt:128-166 |

---

## MCP Tools

All tools are defined in `McpTooling.kt`. The tool call handler is `McpTooling.call(request: ToolCallRequest): ToolCallResponse` at line 476.

### Tool: search_logs
- **Parameters**: `appId?`, `environment?`, `textQuery?`, `traceId?`, `spanId?`, `level?` (TRACE|DEBUG|INFO|WARN|ERROR|FATAL), `startTimeUtc?`, `endTimeUtc?`, `hasFrame?`, `cursor?`, `limit?` (1-500, default 100)
- **Handler**: `McpTooling.kt:479-490`
- **Returns**: `{items: LogRecord[], nextCursor: string|null}` — LogRecords newest-first per timestamp+sequence

### Tool: get_log
- **Parameters**: `logId` (required)
- **Handler**: `McpTooling.kt:493-495`
- **Returns**: `LogRecord | null`

### Tool: get_frame_snapshot
- **Parameters**: `frameId?` OR `logId?` (at least one required)
- **Handler**: `McpTooling.kt:498-501`
- **Returns**: `FrameSnapshot | null`

### Tool: get_trace
- **Parameters**: `traceId` (required)
- **Handler**: `McpTooling.kt:504-506`
- **Returns**: `{traceId: string, spans: SpanRecord[], logs: LogRecord[], frameSnapshots: FrameSnapshot[]}`

### Tool: step_frames
- **Parameters**: `frameId` (required), `direction?` ("forward"|"backward", default "forward"), `count?` (1-25, default 1)
- **Handler**: `McpTooling.kt:509-515`
- **Returns**: `FrameSnapshot[]` — adjacent frames in temporal order

### Tool: list_remote_rules
- **Parameters**: `appId?` — returns rules targeting this appId (and rules with empty targetApps list)
- **Handler**: `McpTooling.kt:518-520`
- **Returns**: `RemoteRule[]` sorted by priority (desc), then ruleId

### Tool: upsert_remote_rule
- **Parameters**: `rule` (full RemoteRule object required — ruleId, ttlSeconds, expression, createdBy required)
- **Handler**: `McpTooling.kt:523-525`
- **Returns**: The saved RemoteRule (echoed back)

### Tool: delete_remote_rule
- **Parameters**: `ruleId` (required)
- **Handler**: `McpTooling.kt:528-530`
- **Returns**: `{deleted: boolean}`

### Tool: create_purge_job
- **Parameters**: `requestedBy?` (default "mcp"), `field` (required: "appId"|"environment"|"traceId"|"spanId"), `value` (required)
- **Handler**: `McpTooling.kt:533-539`
- **Returns**: `PurgeJob` with status "ACCEPTED"

### Tool: get_purge_job
- **Parameters**: `purgeJobId` (required)
- **Handler**: `McpTooling.kt:542-544`
- **Returns**: `PurgeJob | null`

### Tool: get_system_health
- **Parameters**: none
- **Handler**: `McpTooling.kt:547-549`
- **Returns**: `SystemHealth` — authMode, totalLogs, totalSpans, totalFrames, totalRules, totalPurgeJobs, storageMode, clickhouseHealthy?, valkeyHealthy?

---

## TypeScript Client API

### Class: ChronoTraceClient (sdk-ts/src/client.ts)

| Method | Parameters | Return Type | File:Line |
|--------|-----------|-------------|-----------|
| `init()` | none | `Promise<ChronoTraceClient>` | client.ts:135-146 |
| `shutdown()` | none | `Promise<void>` | client.ts:148-159 |
| `getCurrentContext()` | none | `TraceContext \| undefined` | client.ts:161-163 |
| `getRuntimeHealth()` | none | `RuntimeHealth` | client.ts:165-177 |
| `withTrace<T>` | `name: string`, `fn: () => Promise<T> \| T`, `options?: SpanOptions` | `Promise<T>` | client.ts:179-185 |
| `withSpan<T>` | `name: string`, `fn: () => Promise<T> \| T`, `options?: Omit<SpanOptions, "parent">` | `Promise<T>` | client.ts:187-203 |
| `startSpan` | `name: string`, `options?: Omit<SpanOptions, "parent">` | `SpanHandle` | client.ts:205-238 |
| `debug(message, fields?)` | `message: string`, `fields?: Record<string, unknown>` | `Promise<void>` | client.ts:240-241 |
| `info(message, fields?)` | `message: string`, `fields?: Record<string, unknown>` | `Promise<void>` | client.ts:244-245 |
| `warn(message, fields?)` | `message: string`, `fields?: Record<string, unknown>` | `Promise<void>` | client.ts:248-249 |
| `error(message, fields?)` | `message: string`, `fields?: Record<string, unknown>` | `Promise<void>` | client.ts:252-253 |
| `fatal(message, fields?)` | `message: string`, `fields?: Record<string, unknown>` | `Promise<void>` | client.ts:256-257 |
| `injectHeaders` | `carrier: Record<string, string> = {}` | `Record<string, string>` | client.ts:260-269 |
| `extractHeaders` | `headers: Record<string, string>` | `TraceContext \| undefined` | client.ts:271-293 |
| `setRemoteRule(rule)` | `rule: RemoteRule` | `void` | client.ts:295-300 |
| `flush()` | none | `Promise<void>` | client.ts:302-311 |
| `flushFatal()` | none | `Promise<void>` | client.ts:452-459 |

### SpanHandle Interface
| Method | Parameters | Return Type |
|--------|-----------|-------------|
| `end(status?: SpanStatus)` | `status?: SpanStatus` | `Promise<void>` |

### Static API Wrappers (sdk-ts/src/index.ts)

| Method | Parameters | Return Type |
|--------|-----------|-------------|
| `ChronoTrace.init(config)` | `config: ChronoTraceConfig` | `void` |
| `ChronoTrace.currentContext()` | none | `TraceContext \| undefined` |
| `ChronoTrace.injectHeaders(carrier)` | `carrier: Record<string, string>` | `Record<string, string>` |
| `ChronoTrace.extractHeaders(carrier)` | `carrier: Record<string, string>` | `TraceContext \| undefined` |
| `ChronoTrace.runtimeHealth()` | none | `RuntimeHealth` |
| `ChronoTrace.shutdown()` | none | `Promise<void>` |
| `ChronoLogger.trace(message, fields?)` | `message: string`, `fields?: Record<string, unknown>` | `Promise<void>` |
| `ChronoLogger.debug(message, fields?)` | `message: string`, `fields?: Record<string, unknown>` | `Promise<void>` |
| `ChronoLogger.info(message, fields?)` | `message: string`, `fields?: Record<string, unknown>` | `Promise<void>` |
| `ChronoLogger.warn(message, fields?)` | `message: string`, `fields?: Record<string, unknown>` | `Promise<void>` |
| `ChronoLogger.error(message, fields?)` | `message: string`, `fields?: Record<string, unknown>` | `Promise<void>` |
| `ChronoLogger.fatal(message, fields?)` | `message: string`, `fields?: Record<string, unknown>` | `Promise<void>` |
| `withTrace<T>(name, block, options?)` | `name: string`, `block: () => Promise<T> \| T`, `options?: SpanOptions` | `Promise<T>` |
| `withSpan<T>(name, block, options?)` | `name: string`, `block: () => Promise<T> \| T`, `options?: SpanOptions` | `Promise<T>` |
| `startSpan(name, options?)` | `name: string`, `options?: SpanOptions` | `SpanHandle` |

### Transport API (sdk-ts/src/transport.ts)

| Class | Method | Parameters | Return Type |
|-------|--------|-----------|-------------|
| `HttpTransport` | `connect()` | none | `Promise<void>` |
| `HttpTransport` | `send(batch)` | `batch: IngestBatch` | `Promise<void>` |
| `HttpTransport` | `close()` | none | `Promise<void>` |
| `HttpTransport` | `isConnected()` | none | `boolean` |
| `WebSocketTransport` | `connect()` | none | `Promise<void>` |
| `WebSocketTransport` | `send(batch)` | `batch: IngestBatch` | `Promise<void>` |
| `WebSocketTransport` | `close()` | none | `Promise<void>` |
| `WebSocketTransport` | `isConnected()` | none | `boolean` |
| `WebSocketTransport` | `setCommandHandler(handler)` | `handler: CommandHandler` | `void` |
| `NoopTransport` | `connect()` | none | `Promise<void>` |
| `NoopTransport` | `send(_batch)` | `batch: IngestBatch` | `Promise<void>` |
| `NoopTransport` | `close()` | none | `Promise<void>` |
| `NoopTransport` | `isConnected()` | none | `boolean` |
| `RecordingTransport` | `connect()` | none | `Promise<void>` |
| `RecordingTransport` | `send(batch)` | `batch: IngestBatch` | `Promise<void>` |
| `RecordingTransport` | `close()` | none | `Promise<void>` |
| `RecordingTransport` | `isConnected()` | none | `boolean` |
| `RecordingTransport` | `batches()` | none | `IngestBatch[]` |

---

## Request/Response Shapes

### HTTP Endpoints

#### POST /api/v1/ingest
- **Request**: `IngestBatch`
  ```json
  {
    "client": { "appId": "string", "environment": "string", "sdkInstanceId": "string", "serviceName": "string" },
    "logs?: LogRecord[]",
    "spans?: SpanRecord[]",
    "frameSnapshots?: FrameSnapshot[]"
  }
  ```
- **Response**: `{ "accepted": true }` or HTTP 503 with `{"error":"ingest_rejected","message":"..."}`

#### WS /api/v1/ingest/ws
- **Inbound**: JSON `IngestBatch` frame
- **Outbound**: `{"accepted":true}` or `{"error":"..."}`

#### POST /api/v1/logs/search
- **Request**: `SearchLogsRequest`
  ```json
  {
    "startTimeUtc?: number", "endTimeUtc?: number", "appId?: string", "environment?: string",
    "level?: "TRACE"|"DEBUG"|"INFO"|"WARN"|"ERROR"|"FATAL"", "traceId?: string", "spanId?: string",
    "textQuery?: string", "hasFrame?: boolean", "cursor?: string", "limit?: number (1-500)"
  }
  ```
- **Response**: `SearchLogsResponse` — `{ items: LogRecord[], nextCursor?: string|null }`

#### GET /api/v1/logs/{logId}
- **Response**: `LogRecord` or `{"error":"Log not found"}`

#### GET /api/v1/frames/{frameId}
- **Response**: `FrameSnapshot` or `{"error":"Frame not found"}`

#### GET /api/v1/traces/{traceId}
- **Response**: `TraceView` — `{ traceId: string, spans: SpanRecord[], logs: LogRecord[], frameSnapshots: FrameSnapshot[] }`

#### POST /api/v1/remote-rules
- **Request**: `RemoteRule`
  ```json
  {
    "ruleId": "string", "enabled?: boolean", "targetApps?: string[]", "ttlSeconds": number,
    "priority?: number", "expression": "string",
    "captureMode?: "manual_trace"|"auto_capture_level"|"remote_rule"|"crash_flush"",
    "sampleLimit?: number", "createdBy": "string"
  }
  ```
- **Response**: The saved `RemoteRule` (echoed back)

#### GET /api/v1/remote-rules
- **Query params**: `appId?`
- **Response**: `RemoteRule[]`

#### POST /api/v1/purge
- **Request**: `{ "requestedBy?: string", "field": "string", "value": "string" }`
- **Response**: `PurgeJob` — `{ purgeJobId, requestedAtUtc, requestedBy, selector, status, clickhouseMutationId?, completedAtUtc?, stats? }`

#### GET /api/v1/purge/{purgeJobId}
- **Response**: `PurgeJob` or `{"error":"Purge job not found"}`

#### POST /mcp
- **Request**: `McpRequest` — `{ jsonrpc: "2.0", id?: string, method: string, params?: JsonObject }`
- **Supported methods**: `initialize`, `tools/list`, `tools/call`
- **Response**: `McpResponse` — `{ jsonrpc: "2.0", id?: string, result?: string, error?: McpError }`

#### GET /api/v1/admin/keys
- **Query params**: `role?`, `appId?`
- **Response**: JSON array of `ApiKeyMetadata` (no keyValue exposed)

#### POST /api/v1/admin/keys
- **Request**: `{ "role": "admin"|"client", "appId?: string", "quota?: { "limit": number, "windowSeconds": number } }`
- **Response**: `{ keyId: string, keyValue: string, role: string, quota?, appId?, createdAtUtc: number }` — keyValue only returned on create

#### POST /api/v1/admin/keys/{keyId}/rotate
- **Response**: `{ keyId: string, keyValue: string, rotatedAtUtc: number }`

#### DELETE /api/v1/admin/keys/{keyId}
- **Response**: HTTP 204 No Content

#### GET /api/v1/admin/audit/logs
- **Query params**: `apiKeyId?`, `action?`, `outcome?`, `startTimeUtc?`, `endTimeUtc?`, `appId?`, `limit?`, `cursor?`
- **Response**: `AuditLogResponse` — `{ entries: AuditLogEntry[], nextCursor?: string|null }`

### Core Data Shapes

#### LogRecord
```json
{
  "logId": "string", "appId": "string", "environment": "string", "sdkInstanceId": "string",
  "serviceName": "string", "traceId": "string|null", "spanId": "string|null", "parentSpanId": "string|null",
  "timestampUtc": number, "sequenceId": number, "level": "TRACE"|"DEBUG"|"INFO"|"WARN"|"ERROR"|"FATAL",
  "message": "string", "fields?: Record<string,string>",
  "captureReason": "manual_trace"|"auto_capture_level"|"remote_rule"|"crash_flush"|null",
  "linkedFrameId": "string|null", "triggeredRuleId": "string|null"
}
```

#### SpanRecord
```json
{
  "spanId": "string", "traceId": "string", "appId": "string", "environment": "string",
  "serviceName": "string", "operationName": "string", "parentSpanId": "string|null",
  "startTimeUtc": number, "endTimeUtc": number|null,
  "status": "OPEN"|"OK"|"ERROR"|"CANCELLED", "attributes?: Record<string,string>"
}
```

#### FrameSnapshot
```json
{
  "frameId": "string", "traceId": "string", "spanId": "string", "appId": "string",
  "environment": "string", "sdkInstanceId": "string", "serviceName": "string",
  "timestampUtc": number, "sequenceId": number,
  "captureReason": "manual_trace"|"auto_capture_level"|"remote_rule"|"crash_flush",
  "callStack": [{ "functionName": "string", "filePath": "string", "lineNumber": number, "columnNumber?: number|null }],
  "localsJson": "string", "serializationMetadata": { "truncated?: boolean", "maxDepthReached?: boolean", "redactedFields?: string[]", "droppedFields?: string[] },
  "logId": "string|null"
}
```

#### RemoteRule
```json
{
  "ruleId": "string", "enabled?: boolean", "targetApps?: string[]", "ttlSeconds": number,
  "priority?: number", "expression": "string",
  "captureMode": "manual_trace"|"auto_capture_level"|"remote_rule"|"crash_flush",
  "sampleLimit": number, "createdBy": "string", "createdAtUtc?: number|null", "expiresAtUtc?: number|null"
}
```

#### PurgeJob
```json
{
  "purgeJobId": "string", "requestedAtUtc": number, "requestedBy": "string",
  "selector": { "field": "string", "value": "string" },
  "status": "ACCEPTED"|"RUNNING"|"COMPLETED"|"FAILED",
  "clickhouseMutationId": "string|null", "completedAtUtc": number|null,
  "stats?: Record<string,string>"
}
```

#### SystemHealth
```json
{
  "authMode": "string", "totalLogs": number, "totalSpans": number, "totalFrames": number,
  "totalRules": number, "totalPurgeJobs": number, "storageMode": "file"|"memory"|"clickhouse",
  "clickhouseHealthy": boolean|null, "valkeyHealthy": boolean|null
}
```

#### TraceView
```json
{
  "traceId": "string", "spans": SpanRecord[], "logs": LogRecord[], "frameSnapshots": FrameSnapshot[]
}
```
# Scan: Module Architecture

## Files Scanned

### chronotrace-contract (3 files)
- `/home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-contract/src/commonMain/kotlin/org/chronotrace/contract/ChronoContracts.kt`
- `/home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-contract/src/jvmMain/kotlin/org/chronotrace/contract/TypeScriptContractGenerator.kt`
- `/home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-contract/src/commonTest/kotlin/org/chronotrace/contract/ChronoContractsTest.kt`

### chronotrace-server (11 files)
- `/home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoStore.kt` (1403 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ServerModule.kt` (829 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/AuthTypes.kt` (220 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoStoreOptions.kt` (56 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoTraceServer.kt` (53 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoStoreBackend.kt` (29 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/McpTooling.kt` (559 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/McpModels.kt` (26 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ServerMetrics.kt` (111 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoPurgeState.kt` (12 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoStorage.kt` (35 lines)

### sdk-kmp (28 files)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoCapture.kt` (235 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoTrace.kt` (124 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoRuntime.kt` (215 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoRuntimeHooks.kt` (5 lines, expect)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoBuffer.kt` (56 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoModels.kt` (60 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoTransport.kt` (21 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoLogger.kt` (12 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoPlatform.kt` (6 lines, expect)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoIds.kt` (17 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoRedaction.kt` (15 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoContextStorage.kt` (14 lines, expect)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/jvmMain/kotlin/com/chronotrace/sdk/ChronoRuntimeHooks.jvm.kt` (25 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/jsMain/kotlin/com/chronotrace/sdk/ChronoRuntimeHooks.js.kt` (5 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/wasmJsMain/kotlin/com/chronotrace/sdk/ChronoRuntimeHooks.wasmJs.kt` (5 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/jvmMain/kotlin/com/chronotrace/sdk/ChronoPlatform.jvm.kt` (6 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/jsMain/kotlin/com/chronotrace/sdk/ChronoPlatform.js.kt` (8 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/wasmJsMain/kotlin/com/chronotrace/sdk/ChronoPlatform.wasmJs.kt` (7 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/jvmMain/kotlin/com/chronotrace/sdk/ChronoContextStorage.jvm.kt` (31 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/jsMain/kotlin/com/chronotrace/sdk/ChronoContextStorage.js.kt` (24 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/wasmJsMain/kotlin/com/chronotrace/sdk/ChronoContextStorage.wasmJs.kt` (24 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/jvmMain/kotlin/com/chronotrace/sdk/ChronoCapture.jvm.kt` (19 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/jsMain/kotlin/com/chronotrace/sdk/ChronoCapture.js.kt` (34 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/wasmJsMain/kotlin/com/chronotrace/sdk/ChronoCapture.wasmJs.kt` (39 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/jvmTest/kotlin/com/chronotrace/sdk/MavenPublishConfigTest.kt`
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonTest/kotlin/com/chronotrace/sdk/ChronoSdkTest.kt`
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/jsTest/kotlin/com/chronotrace/sdk/JsBehavioralTest.kt`
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/wasmJsTest/kotlin/com/chronotrace/sdk/WasmBehavioralTest.kt`

### chronotrace-kotlin-plugin (2 files)
- `/home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-kotlin-plugin/src/main/kotlin/org/chronotrace/plugin/ChronoTraceIrGenerationExtension.kt` (235 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-kotlin-plugin/src/main/kotlin/org/chronotrace/plugin/ChronoTraceCompilerPluginRegistrar.kt` (17 lines)

### chronotrace-kotlin-plugin-gradle (1 file)
- `/home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-kotlin-plugin-gradle/src/main/kotlin/org/chronotrace/gradle/ChronoTraceKotlinPlugin.kt` (22 lines)

### sdk-ts (19 files)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/index.ts` (108 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/client.ts` (641 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/types.ts` (26 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/config.ts` (73 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/runtime.ts` (25 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/capture.ts` (313 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/context.ts` (74 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/buffer.ts` (59 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/redaction.ts` (7 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/transport.ts` (50 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/remoteRules.ts` (306 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/node.ts` (24 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/vite.ts` (28 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/instrumentation.ts` (148 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/internal/id.ts` (16 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/internal/size.ts` (7 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/transports/httpTransport.ts` (87 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/transports/webSocketTransport.ts` (66 lines)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/generated/contracts.ts` (180 lines)

---

## Module Inventory

### chronotrace-contract
**Role**: Shared data model and code generation module. Defines all wire-format types (serializable enums, data classes) used across SDKs and server. Also generates TypeScript contract definitions.

**Key Files**:
- `ChronoContracts.kt` (279 lines): Defines all domain types as Kotlin serializable classes/enums
- `TypeScriptContractGenerator.kt` (140 lines): JVM utility to generate TypeScript `.ts` from Kotlin serializers
- `ChronoContractsTest.kt` (33 lines): Tests for contract serialization

**Exports**: Enums (LogLevel, CaptureReason, SpanStatus, PurgeJobStatus, ExpressionOperator, RuleDeliveryStatus), Data Classes (ChronoInitConfig, ClientMetadata, CallStackItem, SerializationMetadata, LogRecord, SpanRecord, FrameSnapshot, RemoteRule, RuleDeliveryConfirmation, PurgeSelector, PurgeJob, IngestBatch, SearchLogsRequest, SearchLogsResponse, TraceView, SystemHealth, ToolDescriptor, ToolCallRequest, ToolCallResponse)

**Dependencies**: kotlinx-serialization

---

### chronotrace-server
**Role**: Ktor-based HTTP/WebSocket server that ingests trace data, stores it (file/memory/ClickHouse), serves queries, and exposes an MCP tool interface.

**Key Files**:
- `ChronoStore.kt` (1403 lines): Core store with ingest, search, key management, quota tracking, audit logging; contains InMemoryChronoStorage, FileChronoStorage, ClickHouseChronoStorage implementations
- `ServerModule.kt` (829 lines): Ktor routing with auth, quota, audit, and all HTTP endpoints
- `ChronoStoreOptions.kt` (56 lines): Configuration for storage mode, ClickHouse, Valkey, API keys
- `AuthTypes.kt` (220 lines): ApiKeyQuota, ApiKeyMetadata, AuditLogEntry, AuditLogQuery, AuditLogResponse, QuotaExceeded, QuotaTracker
- `McpTooling.kt` (559 lines): MCP tool handlers (search_logs, get_log, get_frame_snapshot, get_trace, step_frames, list_remote_rules, upsert_remote_rule, delete_remote_rule, create_purge_job, get_purge_job, get_system_health)
- `ChronoStoreBackend.kt` (29 lines): Interface for store operations
- `ChronoStorage.kt` (35 lines): Interface for storage implementations + StorageCounts, StorageHealth
- `ChronoPurgeState.kt` (12 lines): Interface for purge job state (Valkey-backed or in-memory)
- `ServerMetrics.kt` (111 lines): Prometheus-compatible metrics registry
- `McpModels.kt` (26 lines): McpRequest, McpResponse, McpError JSON-RPC types

**Dependencies**: Ktor (Netty), kotlinx-serialization, ClickHouse JDBC, Jedis (Valkey), kotlinx-coroutines

---

### sdk-kmp (Multiplatform SDK)
**Role**: Kotlin Multiplatform tracing SDK targeting JVM, JS, and Wasm/JS. Provides the ChronoTrace/ChronoLogger API for trace capture and emission.

**Key Files (common)**:
- `ChronoTrace.kt` (124 lines): Main entry point (object), SpanHandle, withTrace/withSpan suspend helpers
- `ChronoRuntime.kt` (215 lines): Runtime engine managing buffers (log/span/frame), flush logic, context stack
- `ChronoCapture.kt` (235 lines): Frame snapshot creation, field sanitization/serialization
- `ChronoModels.kt` (60 lines): OverflowStrategy, RuntimeState, CaptureConfig, BufferConfig, ChronoConfig, ChronoSpanContext, RuntimeHealth
- `ChronoBuffer.kt` (56 lines): Bounded ring buffer with DROP_OLDEST/DROP_NEWEST overflow
- `ChronoTransport.kt` (21 lines): ChronoTransport interface, NoopTransport, RecordingTransport
- `ChronoLogger.kt` (12 lines): trace/debug/info/warn/error/fatal suspend functions
- `ChronoContextStorage.kt` (14 lines, expect): Platform-specific context storage (ThreadLocal/coroutine-local)
- `ChronoPlatform.kt` (6 lines, expect): Platform-specific clock (System.currentTimeMillis / Date.now)
- `ChronoRuntimeHooks.kt` (5 lines, expect): Platform-specific runtime hooks (JVM shutdown hook)
- `ChronoIds.kt` (17 lines): ID generation (traceId, spanId, logId, frameId), sequence counter
- `ChronoRedaction.kt` (15 lines): Field redaction utilities

**Platform-specific (jvm)**:
- `ChronoRuntimeHooks.jvm.kt` (25 lines): JVM shutdown hook for flushFatal
- `ChronoContextStorage.jvm.kt` (31 lines): ThreadLocal-based context storage
- `ChronoPlatform.jvm.kt` (6 lines): System.currentTimeMillis
- `ChronoCapture.jvm.kt` (19 lines): Throwable stack trace capture

**Platform-specific (js)**:
- `ChronoContextStorage.js.kt` (24 lines): Module-level var context storage
- `ChronoPlatform.js.kt` (8 lines): Date.now
- `ChronoCapture.js.kt` (34 lines): V8 stack trace parsing

**Platform-specific (wasmJs)**:
- `ChronoContextStorage.wasmJs.kt` (24 lines): Same as JS
- `ChronoPlatform.wasmJs.kt` (7 lines): js("Date.now()")
- `ChronoCapture.wasmJs.kt` (39 lines): V8 stack trace parsing

**Dependencies**: kotlinx-coroutines, kotlinx-serialization, org.chronotrace.contract

---

### chronotrace-kotlin-plugin
**Role**: Kotlin compiler IR plugin that rewrites ChronoLogger calls and withTrace/withSpan to inject local variable capture automatically.

**Key Files**:
- `ChronoTraceCompilerPluginRegistrar.kt` (17 lines): Registers IrGenerationExtension for K2 compiler
- `ChronoTraceIrGenerationExtension.kt` (235 lines): IrElementTransformerVoid that rewrites logger calls to merge local scopes, and withTrace/withSpan calls to pass captureLocals

**Rewrites**:
- `ChronoLogger.trace/debug/info/warn/error/fatal(message, fields)` → injects local variables into fields
- `withTrace(name, block)` → `withTraceCaptured(name, localsMap, block)`
- `withSpan(name, block)` → `withSpanCaptured(name, localsMap, block)`

**Dependencies**: Kotlin compiler API (IrElementTransformerVoid, IrPluginContext)

---

### chronotrace-kotlin-plugin-gradle
**Role**: Gradle plugin that applies the Kotlin compiler plugin to all Kotlin compilation tasks in a project.

**Key Files**:
- `ChronoTraceKotlinPlugin.kt` (22 lines): Adds `-Xplugin=/path/to/chronotrace-kotlin-plugin.jar` to all KotlinCompile tasks

**Dependencies**: Gradle API, depends on :chronotrace-kotlin-plugin:jar task

---

### sdk-ts (TypeScript SDK)
**Role**: TypeScript SDK for browser and Node.js environments. Provides ChronoTrace, ChronoLogger, withTrace, withSpan, startSpan APIs with automatic local capture via source instrumentation.

**Key Files**:
- `index.ts` (108 lines): Public API exports
- `client.ts` (641 lines): ChronoTraceClient class - main runtime, buffering, transport management, rule evaluation
- `config.ts` (73 lines): ChronoTraceConfig, CaptureConfig, BufferConfig, AuthConfig, SpanOptions, default configs
- `capture.ts` (313 lines): Frame snapshot creation, field serialization with depth/allowlist/masking
- `context.ts` (74 lines): TraceContext, ContextManager, StackContextManager, AsyncLocalStorageContextManager
- `buffer.ts` (59 lines): MemoryQueue (byte-budgeted), RingBuffer (entry-count)
- `transport.ts` (50 lines): ChronoTransport interface, NoopTransport, RecordingTransport
- `remoteRules.ts` (306 lines): CEL-like rule expression parser and evaluator
- `redaction.ts` (7 lines): Re-exports from capture.ts
- `httpTransport.ts` (87 lines): HttpTransport with retry logic (503 backoff)
- `webSocketTransport.ts` (66 lines): WebSocketTransport with command handler for upsert_rule/delete_rule
- `node.ts` (24 lines): Node-specific AsyncLocalStorage context manager
- `vite.ts` (28 lines): Vite plugin wrapper for instrumentation transform
- `instrumentation.ts` (148 lines): TypeScript source transformer (TypeScript compiler API) that injects local variables
- `runtime.ts` (25 lines): RuntimeState, RuntimeHealth, NodeProcessLike interfaces
- `generated/contracts.ts` (180 lines): Generated TypeScript types from chronotrace-contract

**Dependencies**: TypeScript, node:async_hooks (Node only), WebSocket, fetch

---

## Key Classes/Functions by Module

### chronotrace-contract

**Enums** (ChronoContracts.kt:7-279):
- `LogLevel` (line 7): TRACE, DEBUG, INFO, WARN, ERROR, FATAL
- `CaptureReason` (line 17): MANUAL_TRACE, AUTO_CAPTURE_LEVEL, REMOTE_RULE, CRASH_FLUSH
- `SpanStatus` (line 32): OPEN, OK, ERROR, CANCELLED
- `PurgeJobStatus` (line 40): ACCEPTED, RUNNING, COMPLETED, FAILED
- `ExpressionOperator` (line 48): EQ, NEQ, GT, GTE, LT, LTE, CONTAINS, STARTS_WITH, ENDS_WITH, MATCHES, IN
- `RuleDeliveryStatus` (line 186): PENDING, CONFIRMED, FAILED

**Data Classes** (ChronoContracts.kt:63-279):
- `ChronoInitConfig` (line 63): serviceName, environment, authMode
- `ClientMetadata` (line 70): appId, environment, sdkInstanceId, serviceName
- `CallStackItem` (line 78): functionName, filePath, lineNumber, columnNumber
- `SerializationMetadata` (line 86): truncated, maxDepthReached, redactedFields, droppedFields
- `LogRecord` (line 94): logId, appId, environment, sdkInstanceId, serviceName, traceId, spanId, parentSpanId, timestampUtc, sequenceId, level, message, fields, captureReason, linkedFrameId, triggeredRuleId
- `SpanRecord` (line 115): spanId, traceId, appId, environment, serviceName, operationName, parentSpanId, startTimeUtc, endTimeUtc, status, attributes
- `FrameSnapshot` (line 130): frameId, traceId, spanId, appId, environment, sdkInstanceId, serviceName, timestampUtc, sequenceId, captureReason, callStack, localsJson, serializationMetadata, logId
- `RemoteRule` (line 148): ruleId, enabled, targetApps, ttlSeconds, priority, expression, captureMode, sampleLimit, createdBy, createdAtUtc, expiresAtUtc
- `RuleDeliveryConfirmation` (line 169): deliveryId, ruleId, appId, environment, triggeredAtUtc, status, confirmedAtUtc, errorMessage
- `PurgeSelector` (line 193): field, value
- `PurgeJob` (line 199): purgeJobId, requestedAtUtc, requestedBy, selector, status, clickhouseMutationId, completedAtUtc, stats
- `IngestBatch` (line 211): client, logs, spans, frameSnapshots
- `SearchLogsRequest` (line 219): startTimeUtc, endTimeUtc, appId, environment, level, traceId, spanId, textQuery, hasFrame, cursor, limit
- `SearchLogsResponse` (line 233): items, nextCursor
- `TraceView` (line 240): traceId, spans, logs, frameSnapshots
- `SystemHealth` (line 248): authMode, totalLogs, totalSpans, totalFrames, totalRules, totalPurgeJobs, storageMode, clickhouseHealthy, valkeyHealthy
- `ToolDescriptor` (line 261): name, description, inputSchema, outputSchema
- `ToolCallRequest` (line 269): name, arguments
- `ToolCallResponse` (line 275): structuredContent, text, isError

**Code Generator** (TypeScriptContractGenerator.kt):
- `TypeScriptContractGenerator` class (line 15): renders() generates TypeScript definitions
- `enumDefinition()` (line 66): renders TypeScript union type from serializer
- `interfaceDefinition()` (line 74): renders TypeScript interface from serializer
- `toTsType()` (line 90): maps Kotlin type descriptors to TypeScript types
- `main()` (line 120): CLI entry point with --check mode

---

### chronotrace-server

**ChronoStore** (ChronoStore.kt:42-426):
- Constructor (line 42): Initializes storage, purgeState, keyRegistry, quotaTracker, auditLog
- `ingest(batch: IngestBatch)` (line 89): Delegates to storage
- `tryOfferBatch(batch: IngestBatch)` (line 98): Bounded queue offer for ClickHouse storage
- `searchLogs(request: SearchLogsRequest): SearchLogsResponse` (line 107)
- `getLog(logId: String): LogRecord?` (line 109)
- `getFrame(frameId: String): FrameSnapshot?` (line 111)
- `getFrameByLog(logId: String): FrameSnapshot?` (line 113)
- `getTrace(traceId: String): TraceView` (line 115)
- `listRules(appId: String?): List<RemoteRule>` (line 117)
- `upsertRule(rule: RemoteRule): RemoteRule` (line 121)
- `deleteRule(ruleId: String): Boolean` (line 126)
- `createPurgeJob(requestedBy: String, field: String, value: String): PurgeJob` (line 128)
- `getPurgeJob(purgeJobId: String): PurgeJob?` (line 144)
- `health(): SystemHealth` (line 146)
- `stepFrame(frameId: String, direction: String, count: Int): List<FrameSnapshot>` (line 180)
- `queueSize(): Long` (line 183)
- **Quota management**: `checkQuota(keyId: String?): QuotaExceeded?` (line 198), `recordRequest(keyId: String?)` (line 211)
- **Key management**: `listKeys()` (line 219), `getKey(keyId: String)` (line 227), `createKey()` (line 233), `rotateKey()` (line 256), `revokeKey()` (line 281), `isKeyValid()` (line 289), `isKeyValueValid()` (line 298), `getKeyRole()` (line 317)
- **Audit logging**: `recordAuditEntry(entry: AuditLogEntry)` (line 333), `queryAuditLogs(query: AuditLogQuery): AuditLogResponse` (line 343)
- `close()` (line 369)

**Internal Storage Classes**:
- `InMemoryChronoStorage` (ChronoStore.kt:478): In-memory lists with retention enforcement
- `FileChronoStorage` (ChronoStore.kt:588): JSON file persistence wrapping InMemoryChronoStorage
- `ClickHouseChronoStorage` (ChronoStore.kt:697): JDBC-based ClickHouse storage with bounded queue, async insert, TTL
- `ChronoStoreFactory` (ChronoStore.kt:428): Creates appropriate storage + purgeState

**AuthTypes** (AuthTypes.kt):
- `ApiKeyQuota` (line 18): limit, windowSeconds
- `ApiKeyMetadata` (line 43): keyId, keyValue, createdAtUtc, rotatedAtUtc, revokedAtUtc, role, quota, appId; isRevoked, isAdmin
- `AuditLogEntry` (line 82): entryId, timestampUtc, apiKeyId, action, endpoint, method, outcome, statusCode, requestSizeBytes, responseSizeBytes, durationMs, appId, sdkInstanceId, traceId, ipAddress
- `AuditLogQuery` (line 113): apiKeyId, action, outcome, startTimeUtc, endTimeUtc, appId, limit, cursor
- `AuditLogResponse` (line 131): entries, nextCursor
- `QuotaExceeded` (line 148): retryAfterSeconds, limit, remaining, windowSeconds
- `QuotaTracker` (line 164): checkQuota(), recordRequest() with sliding window

**ServerModule** (ServerModule.kt:47-619):
- `chronoTraceModule(store: ChronoStore)` (line 47): Main Ktor routing
- Endpoints: GET /health, GET /metrics, POST /api/v1/ingest, WS /api/v1/ingest/ws, POST /api/v1/logs/search, GET /api/v1/logs/{logId}, GET /api/v1/frames/{frameId}, GET /api/v1/traces/{traceId}, POST /api/v1/remote-rules, GET /api/v1/remote-rules, POST /api/v1/purge, GET /api/v1/purge/{purgeJobId}, POST /mcp
- Admin endpoints: GET /api/v1/admin/keys, POST /api/v1/admin/keys, POST /api/v1/admin/keys/{keyId}/rotate, DELETE /api/v1/admin/keys/{keyId}, GET /api/v1/admin/audit/logs
- `chronoTraceModule()` (line 622): Config loading from application.conf
- Auth helpers: `authCheck()`, `authCheckWithKeyId()`, `checkApiKeyWithKeyId()`, `checkBearerWithKeyId()`
- `quotaCheck()` (line 751): Quota enforcement with 429 response
- `recordAudit()` (line 777): Audit log entry creation

**McpTooling** (McpTooling.kt:10-557):
- `McpTooling(store: ChronoStore, json: Json)` (line 10)
- `descriptors(): List<ToolDescriptor>` (line 14): 11 tool descriptors
- `call(request: ToolCallRequest): ToolCallResponse` (line 476): Tool dispatch

**ServerMetrics** (ServerMetrics.kt:19-111):
- `recordIngest()`, `recordIngestError()`, `recordDropped()`, `recordQueryLatency(latencyMs: Long)`
- `connectionOpened()`, `connectionClosed()`, `setQueueSize()`
- `toPrometheusFormat(): String` (line 68): Prometheus text format export

**ChronoStoreOptions** (ChronoStoreOptions.kt:37):
- `ChronoStoreOptions`: storageMode, dataDir, retentionDaysLogs/Spans/Frames, clickHouse, valkey, apiKeys, bearerTokens, keyMetadata

**ChronoTraceServer** (ChronoTraceServer.kt:7):
- `main()` (line 7): Environment-based startup of embedded Ktor Netty server

---

### sdk-kmp

**ChronoTrace** (ChronoTrace.kt:6-124):
- `init(config: ChronoConfig)` (line 9): Creates ChronoRuntime
- `currentContext(): ChronoSpanContext?` (line 13)
- `shutdown()` (line 15): Flushes and clears runtime
- `runtimeHealth(): RuntimeHealth` (line 21)
- `startSpan(name: String): SpanHandle` (line 23)
- `injectHeaders(carrier: MutableMap<String, String>, context: ChronoSpanContext?)` (line 28)
- `extractHeaders(carrier: Map<String, String>): ChronoSpanContext?` (line 37)
- `runtime(): ChronoRuntime` (line 53): Internal accessor
- `SpanHandle` class (line 60): end() method
- `withTrace<T>(name: String, block: suspend () -> T): T` (line 70)
- `withSpan<T>(name: String, block: suspend () -> T): T` (line 82)
- `withTraceCaptured / withSpanCaptured` (line 94, 110): Internal variants

**ChronoRuntime** (ChronoRuntime.kt:14-215):
- Constructor (line 14): Initializes buffers, sets state
- `log(level: LogLevel, message: String, fields: Map<String, Any?>)` (line 32): Main logging
- `startSpan(name: String, captureLocals: Map<String, Any?>): SpanHandle` (line 80)
- `endSpan(span: SpanRecord, previous: ChronoSpanContext?)` (line 118)
- `flush()` (line 130), `flushFatal()` (line 134)
- `healthSnapshot(): RuntimeHealth` (line 142)

**ChronoCapture** (ChronoCapture.kt:53-233):
- `sanitizeLogFields(config: CaptureConfig, fields: Map<String, Any?>): Map<String, String>` (line 54)
- `createFrameSnapshot(...)` (line 69): Creates FrameSnapshot with serialization
- `serializeValue()` (line 114): Recursive serializer with depth/allowlist/masking
- `captureCallStack(skipFrames: Int)` (line 235): expect declaration

**ChronoBuffer** (ChronoBuffer.kt:3-56):
- `offer(item: T): Int` (line 9): Returns dropped count
- `prependAll(entries: List<T>): Int` (line 25)
- `drain(): List<T>` (line 46)
- `size(): Int` (line 55)

**ChronoModels** (ChronoModels.kt):
- `OverflowStrategy` enum (line 5): DROP_OLDEST, DROP_NEWEST
- `RuntimeState` enum (line 10): CONNECTED, DEGRADED_BUFFERING, RECONNECT_BACKOFF, LOCAL_FALLBACK, FATAL_FLUSH
- `CaptureConfig` (line 18): autoCaptureLevels, maxCollectionEntries, maxStringLength, maxPayloadBytes, maxSerializationDepth, maskingKeys, maskingValues, allowFieldPatterns
- `BufferConfig` (line 29): maxEntries, overflowStrategy
- `ChronoConfig` (line 34): appId, serviceName, environment, sdkInstanceId, captureConfig, bufferConfig, transport
- `ChronoSpanContext` (line 44): traceId, spanId, parentSpanId
- `RuntimeHealth` (line 50): state, droppedLogs/Spans/Frames, bufferedLogs/Spans/Frames, fatalFlushes, lastFlushError

**ChronoTransport** (ChronoTransport.kt:5-21):
- `ChronoTransport` interface (line 5): send(batch: IngestBatch)
- `NoopTransport` (line 9)
- `RecordingTransport` (line 13): sentBatches()

**ChronoLogger** (ChronoLogger.kt:5-12):
- trace, debug, info, warn, error, fatal suspend functions

**ChronoIds** (ChronoIds.kt:5-17):
- `nextSequence(): Long` (line 8)
- `nextId(prefix: String): String` (line 13)

---

### chronotrace-kotlin-plugin

**ChronoTraceCompilerPluginRegistrar** (line 9):
- `registerExtensions()` (line 12): Registers ChronoTraceIrGenerationExtension

**ChronoTraceIrGenerationExtension** (line 45):
- `generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext)` (line 46)
- `rewriteLoggerCall()` (line 144): Merges local variables into fields argument
- `rewriteSpanCall()` (line 165): Replaces withTrace/withSpan with captured variants
- `buildLocalsMap()` (line 220): Constructs Map<String, IrValueDeclaration> for local scope

---

### chronotrace-kotlin-plugin-gradle

**ChronoTraceKotlinPlugin** (line 8):
- `apply(project: Project)` (line 9): Adds -Xplugin to all KotlinCompilationTask tasks

---

### sdk-ts

**ChronoTrace (class)** (client.ts:87-641):
- Constructor (line 111): Initializes config, transport, contextManager, buffers, compiledRules
- `init(): Promise<this>` (line 135)
- `shutdown(): Promise<void>` (line 148)
- `getCurrentContext(): TraceContext | undefined` (line 161)
- `getRuntimeHealth(): RuntimeHealth` (line 165)
- `withTrace / withSpan` (line 179, 187)
- `startSpan(name: string, options?: SpanOptions): SpanHandle` (line 205)
- `debug/info/warn/error/fatal` (line 240-258): Logging methods
- `injectHeaders / extractHeaders` (line 260, 271)
- `setRemoteRule(rule: RemoteRule)` (line 295)
- `flush(): Promise<void>` (line 302)
- `log()` (line 313): Core logging with frame snapshot creation
- `flushFatal()` (line 452)
- `flushInternal(fatal: boolean)` (line 462)
- `drainBatch()` (line 495): Creates IngestBatch from buffers

**ChronoTrace (static)** (index.ts:20-47):
- `init(config: ChronoTraceConfig)` (line 21)
- `currentContext()` (line 25)
- `injectHeaders / extractHeaders` (line 29, 33)
- `runtimeHealth()` (line 37)
- `shutdown()` (line 41)

**ChronoLogger** (index.ts:49-73):
- trace/debug/info/warn/error/fatal static methods

**capture.ts** (313 lines):
- `splitCaptureFields(fields)` (line 185): Separates log fields from capture locals
- `sanitizeFields / sanitizeLogFields` (line 200, 211): Serialization + redaction
- `serializeSnapshotLocals` (line 225): JSON serialization with byte budget
- `captureCallStack(skipFrames)` (line 243): Stack frame parsing
- `createFrameSnapshot(payload, config)` (line 291): FrameSnapshot construction

**remoteRules.ts** (306 lines):
- `parseRuleExpression(expression: string): RuleAstNode` (line 282)
- `evaluateRule(node: RuleAstNode, payload: Record<string, unknown>): boolean` (line 286)
- Tokenizer and recursive descent parser (line 45-235)

**instrumentation.ts** (148 lines):
- `instrumentSource(sourceText: string, fileName: string): string` (line 91): TypeScript transformer
- `isChronoLoggerCall()` (line 14), `isNamedCall()` (line 23)
- `buildMergedCaptureFields()` (line 47), `buildMergedSpanOptions()` (line 69)

**buffer.ts** (59 lines):
- `MemoryQueue<T>` class (line 4): Byte-budgeted queue with overflow strategies
- `RingBuffer<T>` class (line 45): Entry-count ring buffer

**context.ts** (74 lines):
- `TraceContext` interface (line 1)
- `ContextManager` interface (line 10)
- `StackContextManager` class (line 15)
- `AsyncLocalStorageContextManager` class (line 35)

**transport.ts** (50 lines):
- `ChronoTransport` interface (line 11): connect, send, close, isConnected, setCommandHandler
- `NoopTransport` (line 19), `RecordingTransport` (line 28)

**httpTransport.ts** (87 lines):
- `HttpTransport` class (line 15): Retries on 503 with exponential backoff

**webSocketTransport.ts** (66 lines):
- `WebSocketTransport` class (line 9): Command handler for rule updates

---

## Dependency Graph

```
chronotrace-contract
    └── (no internal dependencies — pure data model)
           └── Used by: chronotrace-server, sdk-kmp, sdk-ts

chronotrace-kotlin-plugin
    └── (no dependencies — operates on compiled SDK classes)
           └── Applied by: chronotrace-kotlin-plugin-gradle

chronotrace-kotlin-plugin-gradle
    └── depends on: chronotrace-kotlin-plugin (JAR)
           └── Applies to: Any Kotlin project using the plugin

sdk-kmp
    └── depends on: chronotrace-contract
           └── Provides: ChronoTrace SDK for JVM/JS/Wasm

sdk-ts
    └── depends on: chronotrace-contract (generated/contracts.ts)
           └── Provides: ChronoTrace SDK for TypeScript/JS environments

chronotrace-server
    └── depends on: chronotrace-contract
           └── Provides: Ingest API, Query API, MCP tools, Key management
```

**Data flow**:
```
sdk-kmp (JVM/JS/Wasm) ──HTTP/WebSocket──> chronotrace-server
sdk-ts (TypeScript)    ──HTTP/WebSocket──>        │
                                               v
                                    chronotrace-contract (types)
                                               ^
                                               │
                          TypeScriptContractGenerator generates
                                               │
                                    sdk-ts/src/generated/contracts.ts
```

**Key architectural decisions**:
- chronotrace-contract is the single source of truth for all wire format types
- TypeScript types are generated from Kotlin serializers (not hand-written)
- SDKs (kmp and ts) are separate implementations with matching APIs
- chronotrace-server has three storage backends: InMemory (dev), File (simple production), ClickHouse (scale)
- chronotrace-kotlin-plugin is a compiler IR plugin that auto-injects local variable capture
- chronotrace-kotlin-plugin-gradle is the Gradle integration point for the compiler plugin
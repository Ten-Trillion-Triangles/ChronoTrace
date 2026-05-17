# Scan: Project Overview

## Files Scanned

### chronotrace-contract
- `/home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-contract/build.gradle.kts` - Multiplatform plugin; registers `generateTypeScriptContracts` and `verifyTypeScriptContracts` tasks
- `/home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-contract/src/commonMain/kotlin/org/chronotrace/contract/ChronoContracts.kt` - All shared data contracts (LogRecord, SpanRecord, FrameSnapshot, RemoteRule, IngestBatch, etc.)
- `/home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-contract/src/jvmMain/kotlin/org/chronotrace/contract/TypeScriptContractGenerator.kt` - Code generator that produces sdk-ts/src/generated/contracts.ts from Kotlin serialization descriptors
- `/home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-contract/src/commonTest/kotlin/org/chronotrace/contract/ChronoContractsTest.kt` - Tests serialization round-trips

### chronotrace-server
- `/home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/build.gradle.kts` - Ktor/Netty server; deps: clickhouse-jdbc, ktor-*, kotlinx-serialization, jedis, logback, kotlin-logging
- `/home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoTraceServer.kt` - Main entry point; reads env vars for config (PORT, CHRONOTRACE_BIND_HOST, CHRONOTRACE_AUTH_MODE, CHRONOTRACE_STORAGE_MODE, CHRONOTRACE_BEARER_TOKENS, CHRONOTRACE_API_KEYS, CHRONOTRACE_DATA_DIR, retention days, ClickHouse/Valkey config)
- `/home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ServerModule.kt` - Ktor routing module with all HTTP endpoints; auth helpers (authCheck, authCheckWithKeyId, quotaCheck, recordAudit); two Application entry points (one with store param, one environment-based)
- `/home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoStore.kt` - Main state class; implements ChronoStoreBackend; key registry, quota tracker, audit log; three storage implementations: InMemoryChronoStorage, FileChronoStorage, ClickHouseChronoStorage; ValkeyChronoPurgeState and LazyValkeyChronoPurgeState for purge state; purge executor
- `/home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoStoreBackend.kt` - Interface: ingest, searchLogs, getLog, getFrame, getFrameByLog, getTrace, listRules, upsertRule, deleteRule, createPurgeJob, getPurgeJob, health, stepFrame, queueSize
- `/home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoStorage.kt` - Interface: ingest, searchLogs, getLog, getFrame, getFrameByLog, getTrace, stepFrame, counts, countsBySelector, health; StorageCounts and StorageHealth data classes
- `/home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoStoreOptions.kt` - StorageMode enum (FILE, CLICKHOUSE); ClickHouseConfig (jdbcUrl, database, username, password, connectTimeoutMs, ingestQueueCapacity, ingestQueueTimeoutMs, asyncInsert, bounceOnRejected); ValkeyConfig; ChronoStoreOptions data class
- `/home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoPurgeState.kt` - Interface: put, get, listAll, count, health, queueSize
- `/home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/AuthTypes.kt` - ApiKeyQuota, ApiKeyMetadata, AuditLogEntry, AuditLogQuery, AuditLogResponse, QuotaExceeded, QuotaTracker (sliding window)
- `/home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/McpModels.kt` - McpRequest, McpResponse, McpError (JSON-RPC 2.0)
- `/home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/McpTooling.kt` - MCP tool registry; 11 tools: search_logs, get_log, get_frame_snapshot, get_trace, step_frames, list_remote_rules, upsert_remote_rule, delete_remote_rule, create_purge_job, get_purge_job, get_system_health
- `/home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ServerMetrics.kt` - Prometheus-compatible metrics; counters for ingest_total, ingest_errors_total, dropped_events_total; gauge for active_connections, queue_size; histogram for query_latency_seconds

### sdk-kmp (Kotlin Multiplatform SDK)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/build.gradle.kts` - Multiplatform (JVM, JS/IR, wasmJs); depends on chronotrace-contract; compiles with IR plugin; auto-applies chronotrace-kotlin-plugin; maven-publish config
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoTrace.kt` - Main SDK entry point (object); init(), shutdown(), currentContext(), runtimeHealth(), startSpan(), injectHeaders(), extractHeaders(); SpanHandle class; withTrace() and withSpan() suspend functions; withTraceCaptured() and withSpanCaptured() internal variants
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoRuntime.kt` - Internal runtime class; log(), startSpan(), endSpan(), flush(), flushFatal(), healthSnapshot(); per-type buffers (logBuffer, spanBuffer, frameBuffer); state machine (CONNECTED, DEGRADED_BUFFERING, RECONNECT_BACKOFF, LOCAL_FALLBACK, FATAL_FLUSH)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoCapture.kt` - Internal capture logic; sanitizeLogFields(), createFrameSnapshot(); SerializationState tracking (truncated, maxDepthReached, redactedFields, droppedFields); serializeValue(), serializeMap(), serializeIterable(), matchesAllowList(); captureCallStack() expect function
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoModels.kt` - CaptureConfig, BufferConfig, ChronoConfig, ChronoSpanContext, RuntimeHealth, OverflowStrategy, RuntimeState enums
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoIds.kt` - ID generation (nextSequence(), nextId(prefix))
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoPlatform.kt` - Expect object for platform-specific nowMillis()
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoTransport.kt` - ChronoTransport interface; NoopTransport, RecordingTransport implementations
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoBuffer.kt` - Generic ring buffer; offer(), prependAll(), drain(), size(); OverflowStrategy handling
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoContextStorage.kt` - Expect object for platform-specific context storage; ChronoContextElement expect class
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoRuntimeHooks.kt` - Expect object for platform-specific hook installation
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoLogger.kt` - Static logger (trace, debug, info, warn, error, fatal)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoRedaction.kt` - sanitize() function for field redaction
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/jvmMain/kotlin/com/chronotrace/sdk/ChronoPlatform.jvm.kt` - JVM platform impl
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/jvmMain/kotlin/com/chronotrace/sdk/ChronoContextStorage.jvm.kt` - JVM context storage impl
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/jvmMain/kotlin/com/chronotrace/sdk/ChronoRuntimeHooks.jvm.kt` - JVM hooks impl
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/jvmMain/kotlin/com/chronotrace/sdk/ChronoCapture.jvm.kt` - JVM captureCallStack impl
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/jsMain/kotlin/com/chronotrace/sdk/ChronoPlatform.js.kt` - JS platform impl
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/jsMain/kotlin/com/chronotrace/sdk/ChronoContextStorage.js.kt` - JS context storage impl
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/jsMain/kotlin/com/chronotrace/sdk/ChronoRuntimeHooks.js.kt` - JS hooks impl
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/jsMain/kotlin/com/chronotrace/sdk/ChronoCapture.js.kt` - JS captureCallStack impl
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/wasmJsMain/kotlin/com/chronotrace/sdk/ChronoPlatform.wasmJs.kt` - Wasm platform impl
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/wasmJsMain/kotlin/com/chronotrace/sdk/ChronoContextStorage.wasmJs.kt` - Wasm context storage impl
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/wasmJsMain/kotlin/com/chronotrace/sdk/ChronoRuntimeHooks.wasmJs.kt` - Wasm hooks impl
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/wasmJsMain/kotlin/com/chronotrace/sdk/ChronoCapture.wasmJs.kt` - Wasm captureCallStack impl

### sdk-ts (TypeScript SDK)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/index.ts` - Main entry; exports ChronoTrace, ChronoLogger, withTrace, withSpan, startSpan, instrumentSource, createChronoTraceVitePlugin, evaluateRule, parseRuleExpression, transports
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/client.ts` - ChronoTraceClient class; buffering (pendingLogs, pendingSpans, pendingFrames); compiledRules Map; runtimeState machine; flush timer; lifecycle hooks; all logging/span/context methods; command handler for remote rule updates
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/capture.ts` - Frame snapshot creation; serialization logic (serializeValue, serializeMap, serializeIterable); allowlist/denylist; masking; call stack capture (parseCallStackLine, captureCallStack); splitCaptureFields, sanitizeFields, sanitizeLogFields, serializeSnapshotLocals, createFrameSnapshot
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/runtime.ts` - RuntimeState type union; RuntimeHealth interface; NodeProcessLike interface
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/buffer.ts` - MemoryQueue class (size-based overflow); RingBuffer class (entry-count based); overflow strategies: DROP_OLDEST, DROP_NEWEST, BLOCK_CALLER
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/types.ts` - Re-exports all contract types + config types + transport types + context types + runtime types
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/config.ts` - OverflowStrategy, AuthMode, RuntimeFlavor types; CaptureConfig, BufferConfig, AuthConfig, SpanOptions, ChronoTraceConfig interfaces; defaultCaptureConfig, defaultBufferConfig constants
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/context.ts` - TraceContext interface; ContextManager interface; StackContextManager class; AsyncLocalStorageContextManager class
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/remoteRules.ts` - RuleComparisonOperator type; RuleAstNode type union; tokenizer (tokenize), parser (RuleParser), resolver (resolveValue), comparator (compareValues); parseRuleExpression(), evaluateRule()
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/transport.ts` - ChronoTransport interface (connect, send, close, isConnected, setCommandHandler); NoopTransport, RecordingTransport
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/transports/httpTransport.ts` - HttpTransport class; retry logic with exponential backoff; maxRetries option; 503 circuit breaker
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/transports/webSocketTransport.ts` - (referenced but not fully read)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/instrumentation.ts` - TypeScript AST transformer (instrumentSource); rewrites ChronoLogger.* calls to inject captureLocals; rewrites withTrace/withSpan/startSpan to inject captureLocals; uses TypeScript compiler API
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/node.ts` - AsyncLocalStorageContextManager; createNodeChronoTrace() factory
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/vite.ts` - createChronoTraceVitePlugin() for Vite build integration
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/internal/id.ts` - randomHex(), newTraceId() (32 hex chars), newSpanId() (16 hex chars)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/internal/size.ts` - estimateBytes() using TextEncoder
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/redaction.ts` - Re-exports capture functions
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/generated/contracts.ts` - GENERATED FILE: all contract types mirrored from chronotrace-contract (LogLevel, CaptureReason, SpanStatus, PurgeJobStatus, ExpressionOperator, ChronoInitConfig, ClientMetadata, CallStackItem, SerializationMetadata, LogRecord, SpanRecord, FrameSnapshot, RemoteRule, PurgeSelector, PurgeJob, IngestBatch, SearchLogsRequest, SearchLogsResponse, TraceView, SystemHealth, ToolDescriptor, ToolCallRequest, ToolCallResponse)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/package.json` - (not fully read)
- `/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/vitest.config.ts` - (not fully read)

### chronotrace-kotlin-plugin
- `/home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-kotlin-plugin/build.gradle.kts` - JVM plugin; depends on kotlin-compiler-embeddable
- `/home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-kotlin-plugin/src/main/kotlin/org/chronotrace/plugin/ChronoTraceCompilerPluginRegistrar.kt` - CompilerPluginRegistrar for K2; registers IrGenerationExtension
- `/home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-kotlin-plugin/src/main/kotlin/org/chronotrace/plugin/ChronoTraceIrGenerationExtension.kt` - IR transformer; rewrites ChronoLogger.* calls and withTrace/withSpan calls to inject captured local variables; uses DeclarationIrBuilder; scope tracking via stack of LinkedHashMaps; HelperSymbols class resolves sdk symbols

### chronotrace-kotlin-plugin-gradle
- `/home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-kotlin-plugin-gradle/build.gradle.kts` - Gradle plugin; java-gradle-plugin applied; declares chronoTraceKotlinPlugin
- `/home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-kotlin-plugin-gradle/src/main/kotlin/org/chronotrace/gradle/ChronoTraceKotlinPlugin.kt` - Plugin<Project>; applies to all KotlinCompilationTask; adds -Xplugin=... compiler arg to load the IR plugin

### Build/Root
- `/home/cage/Desktop/Workspaces/ChronoTrace/build.gradle.kts` - Root build; declares Kotlin JVM 2.2.21, Kotlin serialization, Ktor plugin; allprojects group/version
- `/home/cage/Desktop/Workspaces/ChronoTrace/settings.gradle.kts` - Includes: chronotrace-contract, chronotrace-server, sdk-kmp, chronotrace-kotlin-plugin, chronotrace-kotlin-plugin-gradle
- `/home/cage/Desktop/Workspaces/ChronoTrace/docker-compose.yml` - (not read)
- `/home/cage/Desktop/Workspaces/ChronoTrace/README.md` - Quick start guide

---

## Key Findings

### Multi-module Project Architecture
- **chronotrace-contract**: Shared Kotlin serialization contracts defining all data types. Contains a TypeScript code generator that outputs to sdk-ts/src/generated/contracts.ts
- **chronotrace-server**: Ktor-based server with HTTP and WebSocket endpoints. Three storage backends: in-memory, file, and ClickHouse
- **sdk-kmp**: Kotlin Multiplatform SDK with JVM, JS, and Wasm targets. Auto-applies the IR compiler plugin at compile time
- **sdk-ts**: TypeScript SDK with Node.js and browser support; transpiled/transformed source via Vite plugin instrumentation
- **chronotrace-kotlin-plugin**: K2 Kotlin compiler plugin (IR transformation) that injects local variable capture into ChronoLogger calls and withTrace/withSpan calls
- **chronotrace-kotlin-plugin-gradle**: Gradle plugin that applies the K2 compiler plugin to all Kotlin compilation tasks

### Server Endpoints (all in ServerModule.kt)
- `GET /health` - Returns SystemHealth (no auth)
- `GET /metrics` - Returns Prometheus text format (no auth)
- `POST /api/v1/ingest` - Ingest IngestBatch (auth + quota + audit)
- `WebSocket /api/v1/ingest/ws` - WebSocket ingest (auth + quota + audit)
- `POST /api/v1/logs/search` - Search logs with filters (auth + quota + audit)
- `GET /api/v1/logs/{logId}` - Get single log (auth + quota + audit)
- `GET /api/v1/frames/{frameId}` - Get single frame (auth + quota + audit)
- `GET /api/v1/traces/{traceId}` - Get full trace (spans + logs + frames) (auth + quota + audit)
- `POST /api/v1/remote-rules` - Upsert RemoteRule (auth + quota + audit)
- `GET /api/v1/remote-rules` - List rules, optionally filtered by appId (auth + quota + audit)
- `POST /api/v1/purge` - Create async purge job (auth + quota + audit)
- `GET /api/v1/purge/{purgeJobId}` - Get purge job status (auth + quota + audit)
- `POST /mcp` - MCP JSON-RPC endpoint; handles initialize, tools/list, tools/call (auth + quota + audit)
- `GET /api/v1/admin/keys` - List all keys metadata (admin role required)
- `POST /api/v1/admin/keys` - Create new key (admin role required)
- `POST /api/v1/admin/keys/{keyId}/rotate` - Rotate a key (admin role required)
- `DELETE /api/v1/admin/keys/{keyId}` - Revoke a key (admin role required)
- `GET /api/v1/admin/audit/logs` - Query audit log entries (admin role required)

### MCP Tools (11 total, in McpTooling.kt)
- search_logs, get_log, get_frame_snapshot, get_trace, step_frames, list_remote_rules, upsert_remote_rule, delete_remote_rule, create_purge_job, get_purge_job, get_system_health

### Authentication Modes
- "none" - No auth required
- "apiKey" - X-Api-Key header; key validated against keyRegistry/originalApiKeys
- "bearer" - Authorization: Bearer <token> header; tokens from CHRONOTRACE_BEARER_TOKENS env

### Storage Modes
- FILE: FileChronoStorage with JSON persistence to CHRONOTRACE_DATA_DIR/chronotrace_store.json
- CLICKHOUSE: ClickHouseChronoStorage with JDBC; tables: logs, spans, frame_snapshots (MergeTree engine with TTL)

### Quota Enforcement
- Sliding window per-key rate limiting via QuotaTracker
- Returns 429 with Retry-After header when exceeded
- Admin keys have no automatic quota; can be assigned explicit quotas

### Key Management
- Keys can be created, rotated, and revoked via admin endpoints
- Key value only returned once at creation/rotation time
- KeyId = keyValue for apiKey mode; "bearer:<token>" for bearer mode

### Audit Logging
- All protected endpoint calls recorded to in-memory CopyOnWriteArrayList
- Entries include: timestamp, apiKeyId, action, endpoint, method, outcome, statusCode, durationMs, appId, sdkInstanceId, traceId, ipAddress
- Queryable via GET /api/v1/admin/audit/logs with filters

### SDK Features
- **Local variable capture**: Both SDKs can capture local variables at trace/span boundaries
- **Kotlin compiler plugin**: Automatically injects captureLocals into ChronoLogger calls via IR transformation
- **TypeScript Vite plugin**: Instruments source at build time to inject captureLocals
- **Remote rules**: Rules are CEL-like expressions evaluated client-side in TypeScript SDK, server-side in Kotlin via expression evaluation in remoteRules.ts
- **Runtime states**: CONNECTED, DEGRADED_BUFFERING, RECONNECT_BACKOFF, LOCAL_FALLBACK, FATAL_FLUSH

### Purge System
- Async purge jobs tracked via ChronoPurgeState
- In-memory (InMemoryChronoPurgeState) or Valkey-backed (ValkeyChronoPurgeState)
- LazyValkeyChronoPurgeState defers connection until first access
- ClickHouse async mutations via ALTER TABLE ... DELETE WHERE
- Purge selector fields for ClickHouse: appId, environment, traceId, spanId

### Metrics (Prometheus format)
- chronotrace_ingest_total (counter)
- chronotrace_ingest_errors_total (counter)
- chronotrace_dropped_events_total (counter)
- chronotrace_active_connections (gauge)
- chronotrace_queue_size (gauge)
- chronotrace_query_latency_seconds (histogram)

---

## Data Mapped

### Enums (chronotrace-contract)
- LogLevel: TRACE, DEBUG, INFO, WARN, ERROR, FATAL
- CaptureReason: MANUAL_TRACE, AUTO_CAPTURE_LEVEL, REMOTE_RULE, CRASH_FLUSH
- SpanStatus: OPEN, OK, ERROR, CANCELLED
- PurgeJobStatus: ACCEPTED, RUNNING, COMPLETED, FAILED
- ExpressionOperator: EQ, NEQ, GT, GTE, LT, LTE, CONTAINS, STARTS_WITH, ENDS_WITH, MATCHES, IN
- RuleDeliveryStatus: PENDING, CONFIRMED, FAILED (in contract)

### Data Classes (chronotrace-contract)
- ChronoInitConfig: serviceName, environment, authMode
- ClientMetadata: appId, environment, sdkInstanceId, serviceName
- CallStackItem: functionName, filePath, lineNumber, columnNumber?
- SerializationMetadata: truncated, maxDepthReached, redactedFields, droppedFields
- LogRecord: logId, appId, environment, sdkInstanceId, serviceName, traceId?, spanId?, parentSpanId?, timestampUtc, sequenceId, level, message, fields, captureReason?, linkedFrameId?, triggeredRuleId?
- SpanRecord: spanId, traceId, appId, environment, serviceName, operationName, parentSpanId?, startTimeUtc, endTimeUtc?, status, attributes
- FrameSnapshot: frameId, traceId, spanId, appId, environment, sdkInstanceId, serviceName, timestampUtc, sequenceId, captureReason, callStack, localsJson, serializationMetadata, logId?
- RemoteRule: ruleId, enabled, targetApps, ttlSeconds, priority, expression, captureMode, sampleLimit, createdBy, createdAtUtc?, expiresAtUtc?
- RuleDeliveryConfirmation: deliveryId, ruleId, appId, environment, triggeredAtUtc, status, confirmedAtUtc?, errorMessage?
- PurgeSelector: field, value
- PurgeJob: purgeJobId, requestedAtUtc, requestedBy, selector, status, clickhouseMutationId?, completedAtUtc?, stats
- IngestBatch: client, logs, spans, frameSnapshots
- SearchLogsRequest: startTimeUtc?, endTimeUtc?, appId?, environment?, level?, traceId?, spanId?, textQuery?, hasFrame?, cursor?, limit
- SearchLogsResponse: items, nextCursor?
- TraceView: traceId, spans, logs, frameSnapshots
- SystemHealth: authMode, totalLogs, totalSpans, totalFrames, totalRules, totalPurgeJobs, storageMode, clickhouseHealthy?, valkeyHealthy?
- ToolDescriptor: name, description, inputSchema, outputSchema
- ToolCallRequest: name, arguments
- ToolCallResponse: structuredContent, text, isError

### Server-side Types
- StorageMode: FILE, CLICKHOUSE
- ClickHouseConfig: jdbcUrl, database, username?, password?, connectTimeoutMs, ingestQueueCapacity, ingestQueueTimeoutMs, asyncInsert, bounceOnRejected
- ValkeyConfig: host, port, database, password?, keyPrefix
- ChronoStoreOptions: storageMode, dataDir?, retentionDaysLogs, retentionDaysSpans, retentionDaysFrames, clickHouse?, valkey?, apiKeys, bearerTokens, keyMetadata
- ApiKeyQuota: limit, windowSeconds
- ApiKeyMetadata: keyId, keyValue?, createdAtUtc, rotatedAtUtc?, revokedAtUtc?, role, quota?, appId?
- AuditLogEntry: entryId, timestampUtc, apiKeyId, action, endpoint, method, outcome, statusCode, requestSizeBytes, responseSizeBytes, durationMs, appId?, sdkInstanceId?, traceId?, ipAddress?
- AuditLogQuery: apiKeyId?, action?, outcome?, startTimeUtc?, endTimeUtc?, appId?, limit, cursor?
- AuditLogResponse: entries, nextCursor?
- QuotaExceeded: retryAfterSeconds, limit, remaining, windowSeconds
- McpRequest: jsonrpc, id?, method, params?
- McpResponse: jsonrpc, id?, result?, error?
- StorageCounts: logs, spans, frames
- StorageHealth: storageMode, clickhouseHealthy?, valkeyHealthy?

### SDK Types (KMP)
- CaptureConfig: autoCaptureLevels, maxCollectionEntries, maxStringLength, maxPayloadBytes, maxSerializationDepth, maskingKeys, maskingValues, allowFieldPatterns
- BufferConfig: maxEntries, overflowStrategy
- ChronoConfig: appId, serviceName, environment, sdkInstanceId, captureConfig, bufferConfig, transport
- ChronoSpanContext: traceId, spanId, parentSpanId?
- RuntimeHealth: state, droppedLogs, droppedSpans, droppedFrames, bufferedLogs, bufferedSpans, bufferedFrames, fatalFlushes, lastFlushError?
- OverflowStrategy: DROP_OLDEST, DROP_NEWEST
- RuntimeState: CONNECTED, DEGRADED_BUFFERING, RECONNECT_BACKOFF, LOCAL_FALLBACK, FATAL_FLUSH

### SDK Types (TypeScript)
- TraceContext: traceId, spanId, parentSpanId?, name?, startedAt?, attributes?
- ContextManager: interface with getCurrentContext(), runWithContext()
- StackContextManager, AsyncLocalStorageContextManager
- CaptureConfig: autoCaptureLevels, maxSerializationDepth, maxCollectionEntries, maxStringLength, maxPayloadBytes, maskingRules, denyFieldPatterns, allowFieldPatterns, manualCaptureReason
- BufferConfig: maxMemoryMB, flushIntervalMs, overflowStrategy
- ChronoTraceConfig: appId, environment?, serviceName?, serverUrl?, auth?, runtime?, captureConfig?, bufferConfig?, transport?, contextManager?, fetchImpl?, webSocketFactory?, nodeProcess?, rules?
- AuthConfig: none | apiKey | bearer | mTLS discriminated union
- SpanOptions: parent?, attributes?, captureLocals?
- RuntimeHealth, RuntimeState (same as KMP)
- ChronoTransport: interface with connect(), send(), close(), isConnected(), setCommandHandler?
- RemoteCommand: {type: "upsert_rule" | "delete_rule", rule?, ruleId?}
- RuleComparisonOperator: ==, !=, <, <=, >, >=, contains, startsWith, endsWith, matches, in
- RuleAstNode: identifier | literal | comparison | logical | not union type

---

## Integrations Found

### External Services
- **ClickHouse** (0.9.1 JDBC) - Primary storage backend for logs, spans, frame_snapshots; async insert support; bounded ingest queue with circuit breaker
- **Valkey** (Jedis 5.2.0) - Purge state persistence; lazy initialization; key prefix support

### Build/Codegen
- **TypeScript contract generation**: Kotlin JVM task generates TypeScript from Kotlin serialization descriptors
- **Kotlin K2 compiler plugin**: IR transformation for local variable capture; auto-loaded via -Xplugin= argument
- **TypeScript Vite plugin**: AST transformation at build time for source instrumentation

### Kotlin Stack
- Kotlin 2.2.21 (JVM, Multiplatform, Wasm/JS)
- kotlinx-serialization 1.8.0
- kotlinx-coroutines-core 1.10.2
- kotlinx-datetime 0.7.1
- Ktor 3.1.1 (server-core, server-netty, server-websockets, content-negotiation, serialization-json, call-logging, status-pages)
- Kotlin-logging 7.0.14 / logback 1.5.18

### Java Libraries
- clickhouse-jdbc 0.9.1
- jedis 5.2.0 (Valkey client)
- JUnit 5.12.2, testcontainers 1.21.4 (testing)

### TypeScript Dependencies
- TypeScript (via vitest/config)
- jose (JWT library, in node_modules)
- std-env (environment detection, in node_modules)
- zod-to-json-schema (in node_modules)

---

## Questions for Unclear Areas

1. **Remote rule expression language**: The contract defines `ExpressionOperator` enum (EQ, NEQ, GT, etc.) but the TypeScript SDK implements a custom expression parser in `remoteRules.ts` with operators like ==, !=, <, <=, >, >=, contains, startsWith, endsWith, matches, in. Is this parser supposed to match the contract's ExpressionOperator? Where is the server-side expression evaluation for remote rules? (Rules appear to be stored in server memory and returned to clients, but I don't see server-side CEL evaluation.)

2. **RuleDeliveryConfirmation flow**: The contract defines `RuleDeliveryConfirmation` and `RuleDeliveryStatus` (PENDING, CONFIRMED, FAILED), but I don't see where these are used in the server. There's `triggeredRuleId` on LogRecord - how does the server confirm rule delivery and update these statuses?

3. **Server-side remote rule evaluation**: In the TypeScript SDK, `resolveCaptureReason` evaluates rules client-side. In the Kotlin SDK, remote rules appear to be configured but not evaluated (capture reason only checks autoCaptureLevels). Does the server do any server-side rule evaluation, or is it purely client-side?

4. **mTLS auth mode**: The TypeScript SDK defines AuthMode with mTLS option, but the server only implements "none", "apiKey", and "bearer". Is mTLS support planned?

5. **ClickHouse async insert behavior**: The config has `asyncInsert` flag that appends ?async_insert=1&wait_for_async_insert=0 to the JDBC URL. What is the expected behavior when async inserts are rejected? The `bounceOnRejected` flag defaults to true (return 503), but what happens when false?

6. **Audit log persistence**: AuditLogEntry is stored in an in-memory CopyOnWriteArrayList. The code comments say "in production this would also write to ClickHouse". What is the plan for audit log durability?

7. **Retention behavior on ClickHouse**: The tables are created with TTL expressions (e.g., `TTL toDateTime(timestamp_utc / 1000) + INTERVAL X DAY`). Does ClickHouse automatically purge data when TTL expires, or is there a background process?

8. **WebSocket transport**: The server has a WebSocket endpoint `/api/v1/ingest/ws` but I didn't fully read `webSocketTransport.ts`. How does the WebSocket protocol work for ingest?

9. **Trace ID normalization**: `ChronoTrace.injectHeaders` normalizes traceId to 32 lowercase hex chars and spanId to 16. Where does this normalization happen on the server side when receiving data?

10. **Kotlin plugin compile dependency**: sdk-kmp build.gradle.kts depends on `dependsOn(chronoTraceCompilerPluginJar)` for all KotlinCompilationTask. This means every Kotlin compilation (including test, metadata generation, etc.) loads the plugin. Is this intentional for all tasks or should it be scoped?
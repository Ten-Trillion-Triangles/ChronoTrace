# Scan: Data Models & Types

## Files Scanned

### Kotlin - chronotrace-contract
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-contract/src/commonMain/kotlin/org/chronotrace/contract/ChronoContracts.kt

### Kotlin - sdk-kmp
- /home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoModels.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoCapture.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoTrace.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoRuntime.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoBuffer.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoTransport.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoIds.kt

### Kotlin - chronotrace-server
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/McpModels.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/AuthTypes.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoStorage.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoStoreBackend.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoPurgeState.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoStore.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ServerMetrics.kt

### TypeScript - sdk-ts
- /home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/generated/contracts.ts
- /home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/config.ts
- /home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/context.ts
- /home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/runtime.ts
- /home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/transport.ts
- /home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/types.ts

---

## Kotlin Data Models

### chronotrace-contract (shared contract definitions)

**LogLevel** (enum, line 7)
```
TRACE, DEBUG, INFO, WARN, ERROR, FATAL
```
- File: chronotrace-contract/src/commonMain/kotlin/org/chronotrace/contract/ChronoContracts.kt:7

**CaptureReason** (enum, line 17)
```
MANUAL_TRACE     @SerialName("manual_trace")
AUTO_CAPTURE_LEVEL @SerialName("auto_capture_level")
REMOTE_RULE      @SerialName("remote_rule")
CRASH_FLUSH      @SerialName("crash_flush")
```
- File: chronotrace-contract/src/commonMain/kotlin/org/chronotrace/contract/ChronoContracts.kt:17

**SpanStatus** (enum, line 32)
```
OPEN, OK, ERROR, CANCELLED
```
- File: chronotrace-contract/src/commonMain/kotlin/org/chronotrace/contract/ChronoContracts.kt:32

**PurgeJobStatus** (enum, line 40)
```
ACCEPTED, RUNNING, COMPLETED, FAILED
```
- File: chronotrace-contract/src/commonMain/kotlin/org/chronotrace/contract/ChronoContracts.kt:40

**ExpressionOperator** (enum, line 48)
```
EQ, NEQ, GT, GTE, LT, LTE, CONTAINS, STARTS_WITH, ENDS_WITH, MATCHES, IN
```
- File: chronotrace-contract/src/commonMain/kotlin/org/chronotrace/contract/ChronoContracts.kt:48

**RuleDeliveryStatus** (enum, line 186)
```
PENDING, CONFIRMED, FAILED
```
- File: chronotrace-contract/src/commonMain/kotlin/org/chronotrace/contract/ChronoContracts.kt:186

**ChronoInitConfig** (data class, line 63)
```
serviceName: String
environment: String = "local"
authMode: String = "none"
```
- File: chronotrace-contract/src/commonMain/kotlin/org/chronotrace/contract/ChronoContracts.kt:63

**ClientMetadata** (data class, line 70)
```
appId: String
environment: String
sdkInstanceId: String
serviceName: String
```
- File: chronotrace-contract/src/commonMain/kotlin/org/chronotrace/contract/ChronoContracts.kt:70

**CallStackItem** (data class, line 78)
```
functionName: String
filePath: String
lineNumber: Int
columnNumber: Int? = null
```
- File: chronotrace-contract/src/commonMain/kotlin/org/chronotrace/contract/ChronoContracts.kt:78

**SerializationMetadata** (data class, line 86)
```
truncated: Boolean = false
maxDepthReached: Boolean = false
redactedFields: List<String> = emptyList()
droppedFields: List<String> = emptyList()
```
- File: chronotrace-contract/src/commonMain/kotlin/org/chronotrace/contract/ChronoContracts.kt:86

**LogRecord** (data class, line 94)
```
logId: String
appId: String
environment: String
sdkInstanceId: String
serviceName: String
traceId: String? = null
spanId: String? = null
parentSpanId: String? = null
timestampUtc: Long
sequenceId: Long
level: LogLevel
message: String
fields: Map<String, String> = emptyMap()
captureReason: CaptureReason? = null
linkedFrameId: String? = null
triggeredRuleId: String? = null
```
- File: chronotrace-contract/src/commonMain/kotlin/org/chronotrace/contract/ChronoContracts.kt:94

**SpanRecord** (data class, line 115)
```
spanId: String
traceId: String
appId: String
environment: String
serviceName: String
operationName: String
parentSpanId: String? = null
startTimeUtc: Long
endTimeUtc: Long? = null
status: SpanStatus = SpanStatus.OPEN
attributes: Map<String, String> = emptyMap()
```
- File: chronotrace-contract/src/commonMain/kotlin/org/chronotrace/contract/ChronoContracts.kt:115

**FrameSnapshot** (data class, line 130)
```
frameId: String
traceId: String
spanId: String
appId: String
environment: String
sdkInstanceId: String
serviceName: String
timestampUtc: Long
sequenceId: Long
captureReason: CaptureReason
callStack: List<CallStackItem>
localsJson: String
serializationMetadata: SerializationMetadata = SerializationMetadata()
logId: String? = null
```
- File: chronotrace-contract/src/commonMain/kotlin/org/chronotrace/contract/ChronoContracts.kt:130

**RemoteRule** (data class, line 148)
```
ruleId: String
enabled: Boolean = true
targetApps: List<String> = emptyList()
ttlSeconds: Long
priority: Int = 0
expression: String
captureMode: CaptureReason = CaptureReason.REMOTE_RULE
sampleLimit: Int = 1
createdBy: String
createdAtUtc: Long? = null
expiresAtUtc: Long? = null
```
- File: chronotrace-contract/src/commonMain/kotlin/org/chronotrace/contract/ChronoContracts.kt:148

**RuleDeliveryConfirmation** (data class, line 170)
```
deliveryId: String
ruleId: String
appId: String
environment: String
triggeredAtUtc: Long
status: RuleDeliveryStatus
confirmedAtUtc: Long? = null
errorMessage: String? = null
```
- File: chronotrace-contract/src/commonMain/kotlin/org/chronotrace/contract/ChronoContracts.kt:170

**PurgeSelector** (data class, line 193)
```
field: String
value: String
```
- File: chronotrace-contract/src/commonMain/kotlin/org/chronotrace/contract/ChronoContracts.kt:193

**PurgeJob** (data class, line 199)
```
purgeJobId: String
requestedAtUtc: Long
requestedBy: String
selector: PurgeSelector
status: PurgeJobStatus = PurgeJobStatus.ACCEPTED
clickhouseMutationId: String? = null
completedAtUtc: Long? = null
stats: Map<String, String> = emptyMap()
```
- File: chronotrace-contract/src/commonMain/kotlin/org/chronotrace/contract/ChronoContracts.kt:199

**IngestBatch** (data class, line 211)
```
client: ClientMetadata
logs: List<LogRecord> = emptyList()
spans: List<SpanRecord> = emptyList()
frameSnapshots: List<FrameSnapshot> = emptyList()
```
- File: chronotrace-contract/src/commonMain/kotlin/org/chronotrace/contract/ChronoContracts.kt:211

**SearchLogsRequest** (data class, line 219)
```
startTimeUtc: Long? = null
endTimeUtc: Long? = null
appId: String? = null
environment: String? = null
level: LogLevel? = null
traceId: String? = null
spanId: String? = null
textQuery: String? = null
hasFrame: Boolean? = null
cursor: String? = null
limit: Int = 100
```
- File: chronotrace-contract/src/commonMain/kotlin/org/chronotrace/contract/ChronoContracts.kt:219

**SearchLogsResponse** (data class, line 234)
```
items: List<LogRecord>
nextCursor: String? = null
```
- File: chronotrace-contract/src/commonMain/kotlin/org/chronotrace/contract/ChronoContracts.kt:234

**TraceView** (data class, line 240)
```
traceId: String
spans: List<SpanRecord>
logs: List<LogRecord>
frameSnapshots: List<FrameSnapshot>
```
- File: chronotrace-contract/src/commonMain/kotlin/org/chronotrace/contract/ChronoContracts.kt:240

**SystemHealth** (data class, line 248)
```
authMode: String
totalLogs: Int
totalSpans: Int
totalFrames: Int
totalRules: Int
totalPurgeJobs: Int
storageMode: String = "file"
clickhouseHealthy: Boolean? = null
valkeyHealthy: Boolean? = null
```
- File: chronotrace-contract/src/commonMain/kotlin/org/chronotrace/contract/ChronoContracts.kt:248

**ToolDescriptor** (data class, line 261)
```
name: String
description: String
inputSchema: String
outputSchema: String
```
- File: chronotrace-contract/src/commonMain/kotlin/org/chronotrace/contract/ChronoContracts.kt:261

**ToolCallRequest** (data class, line 269)
```
name: String
arguments: Map<String, String> = emptyMap()
```
- File: chronotrace-contract/src/commonMain/kotlin/org/chronotrace/contract/ChronoContracts.kt:269

**ToolCallResponse** (data class, line 275)
```
structuredContent: String
text: String
isError: Boolean = false
```
- File: chronotrace-contract/src/commonMain/kotlin/org/chronotrace/contract/ChronoContracts.kt:275

---

### sdk-kmp (SDK-side models)

**OverflowStrategy** (enum, ChronoModels.kt:5)
```
DROP_OLDEST, DROP_NEWEST
```
- File: sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoModels.kt:5

**RuntimeState** (enum, ChronoModels.kt:10)
```
CONNECTED, DEGRADED_BUFFERING, RECONNECT_BACKOFF, LOCAL_FALLBACK, FATAL_FLUSH
```
- File: sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoModels.kt:10

**CaptureConfig** (data class, ChronoModels.kt:18)
```
autoCaptureLevels: Set<LogLevel> = setOf(LogLevel.ERROR, LogLevel.FATAL)
maxCollectionEntries: Int = 50
maxStringLength: Int = 4_096
maxPayloadBytes: Int = 256 * 1024
maxSerializationDepth: Int = 3
maskingKeys: List<Regex> = listOf(Regex("password"...), Regex("token"...))
maskingValues: List<Regex> = emptyList()
allowFieldPatterns: List<Regex> = emptyList()
```
- File: sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoModels.kt:18

**BufferConfig** (data class, ChronoModels.kt:29)
```
maxEntries: Int = 512
overflowStrategy: OverflowStrategy = OverflowStrategy.DROP_OLDEST
```
- File: sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoModels.kt:29

**ChronoConfig** (data class, ChronoModels.kt:34)
```
appId: String
serviceName: String
environment: String = "local"
sdkInstanceId: String = ChronoIds.nextId("sdk")
captureConfig: CaptureConfig = CaptureConfig()
bufferConfig: BufferConfig = BufferConfig()
transport: ChronoTransport = NoopTransport
```
- File: sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoModels.kt:34

**ChronoSpanContext** (data class, ChronoModels.kt:44)
```
traceId: String
spanId: String
parentSpanId: String? = null
```
- File: sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoModels.kt:44

**RuntimeHealth** (data class, ChronoModels.kt:50)
```
state: RuntimeState
droppedLogs: Int
droppedSpans: Int
droppedFrames: Int
bufferedLogs: Int
bufferedSpans: Int
bufferedFrames: Int
fatalFlushes: Int
lastFlushError: String? = null
```
- File: sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoModels.kt:50

**ChronoTransport** (interface, ChronoTransport.kt:5)
```
send(batch: IngestBatch): suspend fun
```
- File: sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoTransport.kt:5

**NoopTransport** (object, ChronoTransport.kt:9)
- File: sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoTransport.kt:9

**RecordingTransport** (class, ChronoTransport.kt:13)
```
sentBatches(): List<IngestBatch>
```
- File: sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoTransport.kt:13

**ChronoIds** (object, ChronoIds.kt:5)
```
nextSequence(): Long
nextId(prefix: String): String
```
- File: sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoIds.kt:5

**SpanHandle** (class, ChronoTrace.kt:60)
```
end(): suspend fun
```
- File: sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoTrace.kt:60

---

### chronotrace-server (server-side models)

**McpRequest** (data class, McpModels.kt:8)
```
jsonrpc: String = "2.0"
id: String? = null
method: String
params: JsonObject? = null
```
- File: chronotrace-server/src/main/kotlin/org/chronotrace/server/McpModels.kt:8

**McpResponse** (data class, McpModels.kt:16)
```
jsonrpc: String = "2.0"
id: String? = null
result: String? = null
error: McpError? = null
```
- File: chronotrace-server/src/main/kotlin/org/chronotrace/server/McpModels.kt:16

**McpError** (data class, McpModels.kt:24)
```
code: Int
message: String
```
- File: chronotrace-server/src/main/kotlin/org/chronotrace/server/McpModels.kt:24

**ApiKeyQuota** (data class, AuthTypes.kt:18)
```
limit: Int
windowSeconds: Int
```
- File: chronotrace-server/src/main/kotlin/org/chronotrace/server/AuthTypes.kt:18

**ApiKeyMetadata** (data class, AuthTypes.kt:43)
```
keyId: String
keyValue: String? = null
createdAtUtc: Long
rotatedAtUtc: Long? = null
revokedAtUtc: Long? = null
role: String = "client"
quota: ApiKeyQuota? = null
appId: String? = null
isRevoked: Boolean (computed)
isAdmin: Boolean (computed)
```
- File: chronotrace-server/src/main/kotlin/org/chronotrace/server/AuthTypes.kt:43

**AuditLogEntry** (data class, AuthTypes.kt:82)
```
entryId: String
timestampUtc: Long
apiKeyId: String
action: String
endpoint: String
method: String
outcome: String
statusCode: Int
requestSizeBytes: Long = 0
responseSizeBytes: Long = 0
durationMs: Long = 0
appId: String? = null
sdkInstanceId: String? = null
traceId: String? = null
ipAddress: String? = null
```
- File: chronotrace-server/src/main/kotlin/org/chronotrace/server/AuthTypes.kt:82

**AuditLogQuery** (data class, AuthTypes.kt:113)
```
apiKeyId: String? = null
action: String? = null
outcome: String? = null
startTimeUtc: Long? = null
endTimeUtc: Long? = null
appId: String? = null
limit: Int = 100
cursor: String? = null
```
- File: chronotrace-server/src/main/kotlin/org/chronotrace/server/AuthTypes.kt:113

**AuditLogResponse** (data class, AuthTypes.kt:131)
```
entries: List<AuditLogEntry>
nextCursor: String? = null
```
- File: chronotrace-server/src/main/kotlin/org/chronotrace/server/AuthTypes.kt:131

**QuotaExceeded** (data class, AuthTypes.kt:148)
```
retryAfterSeconds: Int
limit: Int
remaining: Int
windowSeconds: Int
```
- File: chronotrace-server/src/main/kotlin/org/chronotrace/server/AuthTypes.kt:148

**QuotaTracker** (class, AuthTypes.kt:164)
```
checkQuota(keyId: String): QuotaExceeded?
recordRequest(keyId: String)
```
- File: chronotrace-server/src/main/kotlin/org/chronotrace/server/AuthTypes.kt:164

**ChronoStorage** (interface, ChronoStorage.kt:12)
```
ingest(batch: IngestBatch)
searchLogs(request: SearchLogsRequest): SearchLogsResponse
getLog(logId: String): LogRecord?
getFrame(frameId: String): FrameSnapshot?
getFrameByLog(logId: String): FrameSnapshot?
getTrace(traceId: String): TraceView
stepFrame(frameId: String, direction: String, count: Int): List<FrameSnapshot>
counts(): StorageCounts
countsBySelector(selector: PurgeSelector): StorageCounts
health(): StorageHealth
```
- File: chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoStorage.kt:12

**StorageCounts** (data class, ChronoStorage.kt:25)
```
logs: Int
spans: Int
frames: Int
```
- File: chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoStorage.kt:25

**StorageHealth** (data class, ChronoStorage.kt:31)
```
storageMode: StorageMode
clickhouseHealthy: Boolean? = null
valkeyHealthy: Boolean? = null
```
- File: chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoStorage.kt:31

**ChronoStoreBackend** (interface, ChronoStoreBackend.kt:14)
```
ingest(batch: IngestBatch)
searchLogs(request: SearchLogsRequest): SearchLogsResponse
getLog(logId: String): LogRecord?
getFrame(frameId: String): FrameSnapshot?
getFrameByLog(logId: String): FrameSnapshot?
getTrace(traceId: String): TraceView
listRules(appId: String?): List<RemoteRule>
upsertRule(rule: RemoteRule): RemoteRule
deleteRule(ruleId: String): Boolean
createPurgeJob(requestedBy: String, field: String, value: String): PurgeJob
getPurgeJob(purgeJobId: String): PurgeJob?
health(): SystemHealth
stepFrame(frameId: String, direction: String, count: Int): List<FrameSnapshot>
queueSize(): Long
```
- File: chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoStoreBackend.kt:14

**ChronoPurgeState** (interface, ChronoPurgeState.kt:5)
```
put(job: PurgeJob)
get(purgeJobId: String): PurgeJob?
listAll(): List<PurgeJob>
count(): Int
health(): Boolean?
queueSize(): Long
```
- File: chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoPurgeState.kt:5

---

## TypeScript Data Models

### generated/contracts.ts (generated from chronotrace-contract)

**LogLevel** (type alias, line 5)
```
"TRACE" | "DEBUG" | "INFO" | "WARN" | "ERROR" | "FATAL"
```
- File: sdk-ts/src/generated/contracts.ts:5

**CaptureReason** (type alias, line 7)
```
"manual_trace" | "auto_capture_level" | "remote_rule" | "crash_flush"
```
- File: sdk-ts/src/generated/contracts.ts:7

**SpanStatus** (type alias, line 9)
```
"OPEN" | "OK" | "ERROR" | "CANCELLED"
```
- File: sdk-ts/src/generated/contracts.ts:9

**PurgeJobStatus** (type alias, line 11)
```
"ACCEPTED" | "RUNNING" | "COMPLETED" | "FAILED"
```
- File: sdk-ts/src/generated/contracts.ts:11

**ExpressionOperator** (type alias, line 13)
```
"EQ" | "NEQ" | "GT" | "GTE" | "LT" | "LTE" | "CONTAINS" | "STARTS_WITH" | "ENDS_WITH" | "MATCHES" | "IN"
```
- File: sdk-ts/src/generated/contracts.ts:13

**ChronoInitConfig** (interface, line 15)
```
serviceName: string
environment?: string
authMode?: string
```
- File: sdk-ts/src/generated/contracts.ts:15

**ClientMetadata** (interface, line 21)
```
appId: string
environment: string
sdkInstanceId: string
serviceName: string
```
- File: sdk-ts/src/generated/contracts.ts:21

**CallStackItem** (interface, line 28)
```
functionName: string
filePath: string
lineNumber: number
columnNumber?: number | null
```
- File: sdk-ts/src/generated/contracts.ts:28

**SerializationMetadata** (interface, line 35)
```
truncated?: boolean
maxDepthReached?: boolean
redactedFields?: string[]
droppedFields?: string[]
```
- File: sdk-ts/src/generated/contracts.ts:35

**LogRecord** (interface, line 42)
```
logId: string
appId: string
environment: string
sdkInstanceId: string
serviceName: string
traceId?: string | null
spanId?: string | null
parentSpanId?: string | null
timestampUtc: number
sequenceId: number
level: "TRACE" | "DEBUG" | "INFO" | "WARN" | "ERROR" | "FATAL"
message: string
fields?: Record<string, string>
captureReason?: "manual_trace" | "auto_capture_level" | "remote_rule" | "crash_flush" | null
linkedFrameId?: string | null
triggeredRuleId?: string | null
```
- File: sdk-ts/src/generated/contracts.ts:42

**SpanRecord** (interface, line 60)
```
spanId: string
traceId: string
appId: string
environment: string
serviceName: string
operationName: string
parentSpanId?: string | null
startTimeUtc: number
endTimeUtc?: number | null
status?: "OPEN" | "OK" | "ERROR" | "CANCELLED"
attributes?: Record<string, string>
```
- File: sdk-ts/src/generated/contracts.ts:60

**FrameSnapshot** (interface, line 74)
```
frameId: string
traceId: string
spanId: string
appId: string
environment: string
sdkInstanceId: string
serviceName: string
timestampUtc: number
sequenceId: number
captureReason: "manual_trace" | "auto_capture_level" | "remote_rule" | "crash_flush"
callStack: CallStackItem[]
localsJson: string
serializationMetadata?: SerializationMetadata
logId?: string | null
```
- File: sdk-ts/src/generated/contracts.ts:74

**RemoteRule** (interface, line 91)
```
ruleId: string
enabled?: boolean
targetApps?: string[]
ttlSeconds: number
priority?: number
expression: string
captureMode?: "manual_trace" | "auto_capture_level" | "remote_rule" | "crash_flush"
sampleLimit?: number
createdBy: string
createdAtUtc?: number
expiresAtUtc?: number
```
- File: sdk-ts/src/generated/contracts.ts:91

**PurgeSelector** (interface, line 103)
```
field: string
value: string
```
- File: sdk-ts/src/generated/contracts.ts:103

**PurgeJob** (interface, line 108)
```
purgeJobId: string
requestedAtUtc: number
requestedBy: string
selector: PurgeSelector
status?: "ACCEPTED" | "RUNNING" | "COMPLETED" | "FAILED"
clickhouseMutationId?: string | null
completedAtUtc?: number | null
stats?: Record<string, string>
```
- File: sdk-ts/src/generated/contracts.ts:108

**IngestBatch** (interface, line 119)
```
client: ClientMetadata
logs?: LogRecord[]
spans?: SpanRecord[]
frameSnapshots?: FrameSnapshot[]
```
- File: sdk-ts/src/generated/contracts.ts:119

**SearchLogsRequest** (interface, line 126)
```
startTimeUtc?: number | null
endTimeUtc?: number | null
appId?: string | null
environment?: string | null
level?: "TRACE" | "DEBUG" | "INFO" | "WARN" | "ERROR" | "FATAL" | null
traceId?: string | null
spanId?: string | null
textQuery?: string | null
hasFrame?: boolean | null
cursor?: string | null
limit?: number
```
- File: sdk-ts/src/generated/contracts.ts:126

**SearchLogsResponse** (interface, line 140)
```
items: LogRecord[]
nextCursor?: string | null
```
- File: sdk-ts/src/generated/contracts.ts:140

**TraceView** (interface, line 145)
```
traceId: string
spans: SpanRecord[]
logs: LogRecord[]
frameSnapshots: FrameSnapshot[]
```
- File: sdk-ts/src/generated/contracts.ts:145

**SystemHealth** (interface, line 152)
```
authMode: string
totalLogs: number
totalSpans: number
totalFrames: number
totalRules: number
totalPurgeJobs: number
storageMode?: string
clickhouseHealthy?: boolean | null
valkeyHealthy?: boolean | null
```
- File: sdk-ts/src/generated/contracts.ts:152

**ToolDescriptor** (interface, line 164)
```
name: string
description: string
inputSchema: string
outputSchema: string
```
- File: sdk-ts/src/generated/contracts.ts:164

**ToolCallRequest** (interface, line 171)
```
name: string
arguments?: Record<string, string>
```
- File: sdk-ts/src/generated/contracts.ts:171

**ToolCallResponse** (interface, line 176)
```
structuredContent: string
text: string
isError?: boolean
```
- File: sdk-ts/src/generated/contracts.ts:176

---

### config.ts (SDK configuration)

**OverflowStrategy** (type alias, line 6)
```
"DROP_OLDEST" | "DROP_NEWEST" | "BLOCK_CALLER"
```
- File: sdk-ts/src/config.ts:6

**AuthMode** (type alias, line 7)
```
"none" | "apiKey" | "bearer" | "mTLS"
```
- File: sdk-ts/src/config.ts:7

**RuntimeFlavor** (type alias, line 8)
```
"auto" | "node" | "browser"
```
- File: sdk-ts/src/config.ts:8

**CaptureConfig** (interface, line 10)
```
autoCaptureLevels: LogLevel[]
maxSerializationDepth: number
maxCollectionEntries: number
maxStringLength: number
maxPayloadBytes: number
maskingRules: RegExp[]
denyFieldPatterns: RegExp[]
allowFieldPatterns: RegExp[]
manualCaptureReason: CaptureReason
```
- File: sdk-ts/src/config.ts:10

**BufferConfig** (interface, line 22)
```
maxMemoryMB: number
flushIntervalMs: number
overflowStrategy: OverflowStrategy
```
- File: sdk-ts/src/config.ts:22

**AuthConfig** (type alias, line 28)
```
| { mode: "none" }
| { mode: "apiKey"; apiKey: string }
| { mode: "bearer"; token: string }
| { mode: "mTLS"; clientCertificateAlias: string }
```
- File: sdk-ts/src/config.ts:28

**SpanOptions** (interface, line 34)
```
parent?: TraceContext
attributes?: Record<string, unknown>
captureLocals?: Record<string, unknown>
```
- File: sdk-ts/src/config.ts:34

**ChronoTraceConfig** (interface, line 40)
```
appId: string
environment?: string
serviceName?: string
serverUrl?: string
auth?: AuthConfig
runtime?: RuntimeFlavor
captureConfig?: Partial<CaptureConfig>
bufferConfig?: Partial<BufferConfig>
transport?: ChronoTransport
contextManager?: ContextManager
fetchImpl?: typeof fetch
webSocketFactory?: (url: string) => WebSocket
nodeProcess?: NodeProcessLike
rules?: RemoteRule[]
```
- File: sdk-ts/src/config.ts:40

**defaultCaptureConfig** (const, line 57)
- File: sdk-ts/src/config.ts:57

**defaultBufferConfig** (const, line 69)
- File: sdk-ts/src/config.ts:69

---

### context.ts (trace context management)

**TraceContext** (interface, line 1)
```
traceId: string
spanId: string
parentSpanId?: string
name?: string
startedAt?: string
attributes?: Record<string, unknown>
```
- File: sdk-ts/src/context.ts:1

**ContextManager** (interface, line 10)
```
getCurrentContext(): TraceContext | undefined
runWithContext<T>(context: TraceContext, fn: () => Promise<T> | T): Promise<T> | T
```
- File: sdk-ts/src/context.ts:10

**StackContextManager** (class, line 15)
- File: sdk-ts/src/context.ts:15

**AsyncLocalStorageContextManager** (class, line 35)
- File: sdk-ts/src/context.ts:35

---

### runtime.ts (runtime health)

**RuntimeState** (type alias, line 1)
```
"CONNECTED" | "DEGRADED_BUFFERING" | "RECONNECT_BACKOFF" | "LOCAL_FALLBACK" | "FATAL_FLUSH"
```
- File: sdk-ts/src/runtime.ts:1

**RuntimeHealth** (interface, line 8)
```
state: RuntimeState
droppedLogs: number
droppedSpans: number
droppedFrames: number
bufferedLogs: number
bufferedSpans: number
bufferedFrames: number
fatalFlushes: number
lastFlushError?: string
```
- File: sdk-ts/src/runtime.ts:8

**NodeProcessLike** (interface, line 20)
```
on(event: "uncaughtException", listener: (error: Error) => void): void
on(event: "unhandledRejection", listener: (reason: unknown, promise: Promise<unknown>) => void): void
off(event: "uncaughtException", listener: (error: Error) => void): void
off(event: "unhandledRejection", listener: (reason: unknown, promise: Promise<unknown>) => void): void
```
- File: sdk-ts/src/runtime.ts:20

---

### transport.ts (transport interface)

**RemoteCommand** (interface, line 3)
```
type: "upsert_rule" | "delete_rule"
rule?: RemoteRule
ruleId?: string
```
- File: sdk-ts/src/transport.ts:3

**CommandHandler** (type alias, line 9)
```
(command: RemoteCommand) => void
```
- File: sdk-ts/src/transport.ts:9

**ChronoTransport** (interface, line 11)
```
connect(): Promise<void>
send(batch: IngestBatch): Promise<void>
close(): Promise<void>
isConnected(): boolean
setCommandHandler?(handler: CommandHandler): void
```
- File: sdk-ts/src/transport.ts:11

**NoopTransport** (class, line 19)
- File: sdk-ts/src/transport.ts:19

**RecordingTransport** (class, line 28)
```
batches(): IngestBatch[]
```
- File: sdk-ts/src/transport.ts:28

---

## Shared Contracts

The following models are defined in `chronotrace-contract` and shared between SDK and server:

### Core Data Models
| Model | File | Usage |
|-------|------|-------|
| LogRecord | ChronoContracts.kt:94 | SDK logs events, server stores/queries |
| SpanRecord | ChronoContracts.kt:115 | SDK creates spans, server stores |
| FrameSnapshot | ChronoContracts.kt:130 | SDK captures stack frames, server stores |
| IngestBatch | ChronoContracts.kt:211 | SDK sends, server receives |
| ClientMetadata | ChronoContracts.kt:70 | SDK embeds in batch, server uses for routing |
| SearchLogsRequest | ChronoContracts.kt:219 | Server accepts queries |
| SearchLogsResponse | ChronoContracts.kt:234 | Server returns results |
| TraceView | ChronoContracts.kt:240 | Server returns full trace |
| RemoteRule | ChronoContracts.kt:148 | Server manages, SDK evaluates |
| RuleDeliveryConfirmation | ChronoContracts.kt:170 | Server tracks rule delivery |
| PurgeJob | ChronoContracts.kt:199 | Server creates/manages |
| PurgeSelector | ChronoContracts.kt:193 | Server uses for filtering |
| SystemHealth | ChronoContracts.kt:248 | Server exposes |

### Enums
| Enum | File | Usage |
|------|------|-------|
| LogLevel | ChronoContracts.kt:7 | Shared enum for log levels |
| CaptureReason | ChronoContracts.kt:17 | Why something was captured |
| SpanStatus | ChronoContracts.kt:32 | Span lifecycle states |
| PurgeJobStatus | ChronoContracts.kt:40 | Purge job states |
| ExpressionOperator | ChronoContracts.kt:48 | Rule expression operators |
| RuleDeliveryStatus | ChronoContracts.kt:186 | Rule delivery states |

### Generated TypeScript
The TypeScript file `sdk-ts/src/generated/contracts.ts` is auto-generated from `ChronoContracts.kt` and provides all the same models in TypeScript form.

---

## Schema Relationships

```
IngestBatch (server ingestion)
├── client: ClientMetadata (identifies SDK app/environment)
├── logs: List<LogRecord>
│   └── linkedFrameId → FrameSnapshot.frameId (optional link)
├── spans: List<SpanRecord>
│   └── traceId, spanId (hierarchical)
└── frameSnapshots: List<FrameSnapshot>
    ├── frameId (unique)
    ├── traceId, spanId (parent trace/span)
    └── logId → LogRecord.logId (optional, for auto-capture frames)

LogRecord
├── logId (primary key)
├── traceId (optional, linked span's trace)
├── spanId (optional, linked span)
└── captureReason: CaptureReason (why captured)

SpanRecord
├── spanId (primary key)
├── traceId (parent trace)
├── parentSpanId (optional, parent span)
└── status: SpanStatus

FrameSnapshot
├── frameId (primary key)
├── traceId, spanId (trace context)
├── logId (optional, link to triggering log)
├── callStack: List<CallStackItem>
└── localsJson (serialized local variables)

RemoteRule
├── ruleId (unique)
├── targetApps: List<String> (app filtering)
├── expression (server-side evaluation)
└── captureMode: CaptureReason

TraceView (query result)
├── traceId
├── spans: List<SpanRecord>
├── logs: List<LogRecord>
└── frameSnapshots: List<FrameSnapshot>

SystemHealth (server status)
├── totalLogs, totalSpans, totalFrames
├── totalRules, totalPurgeJobs
└── storageMode, clickhouseHealthy, valkeyHealthy

AuditLogEntry (server auth tracking)
├── apiKeyId (key that made request)
├── action, endpoint, method
├── outcome: "success" | "unauthorized" | "quota_exceeded" | "forbidden" | "error"
└── appId, sdkInstanceId, traceId (from request context)

ChronoConfig (SDK configuration)
├── appId, serviceName, environment
├── captureConfig: CaptureConfig
├── bufferConfig: BufferConfig
└── transport: ChronoTransport

RuntimeHealth (SDK health)
├── state: RuntimeState
├── droppedLogs/Spans/Frames
├── bufferedLogs/Spans/Frames
└── fatalFlushes, lastFlushError
```
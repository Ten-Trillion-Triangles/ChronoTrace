package org.chronotrace.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class LogLevel {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
    FATAL,
}

@Serializable
enum class CaptureReason {
    @SerialName("manual_trace")
    MANUAL_TRACE,

    @SerialName("auto_capture_level")
    AUTO_CAPTURE_LEVEL,

    @SerialName("remote_rule")
    REMOTE_RULE,

    @SerialName("crash_flush")
    CRASH_FLUSH,
}

@Serializable
enum class SpanStatus {
    OPEN,
    OK,
    ERROR,
    CANCELLED,
}

@Serializable
enum class PurgeJobStatus {
    ACCEPTED,
    RUNNING,
    COMPLETED,
    FAILED,
}

@Serializable
enum class ExpressionOperator {
    EQ,
    NEQ,
    GT,
    GTE,
    LT,
    LTE,
    CONTAINS,
    STARTS_WITH,
    ENDS_WITH,
    MATCHES,
    IN,
}

@Serializable
data class ChronoInitConfig(
    val serviceName: String,
    val environment: String = "local",
    val authMode: String = "none",
)

@Serializable
data class ClientMetadata(
    val appId: String,
    val environment: String,
    val sdkInstanceId: String,
    val serviceName: String,
)

@Serializable
data class CallStackItem(
    val functionName: String,
    val filePath: String,
    val lineNumber: Int,
    val columnNumber: Int? = null,
)

@Serializable
data class SerializationMetadata(
    val truncated: Boolean = false,
    val maxDepthReached: Boolean = false,
    val redactedFields: List<String> = emptyList(),
    val droppedFields: List<String> = emptyList(),
)

@Serializable
data class LogRecord(
    val logId: String,
    val appId: String,
    val environment: String,
    val sdkInstanceId: String,
    val serviceName: String,
    val traceId: String? = null,
    val spanId: String? = null,
    val parentSpanId: String? = null,
    val timestampUtc: Long,
    val sequenceId: Long,
    val level: LogLevel,
    val message: String,
    val fields: Map<String, String> = emptyMap(),
    val captureReason: CaptureReason? = null,
    val linkedFrameId: String? = null,
)

@Serializable
data class SpanRecord(
    val spanId: String,
    val traceId: String,
    val appId: String,
    val environment: String,
    val serviceName: String,
    val operationName: String,
    val parentSpanId: String? = null,
    val startTimeUtc: Long,
    val endTimeUtc: Long? = null,
    val status: SpanStatus = SpanStatus.OPEN,
    val attributes: Map<String, String> = emptyMap(),
)

@Serializable
data class FrameSnapshot(
    val frameId: String,
    val traceId: String,
    val spanId: String,
    val appId: String,
    val environment: String,
    val sdkInstanceId: String,
    val serviceName: String,
    val timestampUtc: Long,
    val sequenceId: Long,
    val captureReason: CaptureReason,
    val callStack: List<CallStackItem>,
    val localsJson: String,
    val serializationMetadata: SerializationMetadata = SerializationMetadata(),
    val logId: String? = null,
)

@Serializable
data class RemoteRule(
    val ruleId: String,
    val enabled: Boolean = true,
    val targetApps: List<String> = emptyList(),
    val ttlSeconds: Long,
    val priority: Int = 0,
    val expression: String,
    val captureMode: CaptureReason = CaptureReason.REMOTE_RULE,
    val sampleLimit: Int = 1,
    val createdBy: String,
)

@Serializable
data class PurgeSelector(
    val field: String,
    val value: String,
)

@Serializable
data class PurgeJob(
    val purgeJobId: String,
    val requestedAtUtc: Long,
    val requestedBy: String,
    val selector: PurgeSelector,
    val status: PurgeJobStatus = PurgeJobStatus.ACCEPTED,
    val clickhouseMutationId: String? = null,
    val completedAtUtc: Long? = null,
    val stats: Map<String, String> = emptyMap(),
)

@Serializable
data class IngestBatch(
    val client: ClientMetadata,
    val logs: List<LogRecord> = emptyList(),
    val spans: List<SpanRecord> = emptyList(),
    val frameSnapshots: List<FrameSnapshot> = emptyList(),
)

@Serializable
data class SearchLogsRequest(
    val startTimeUtc: Long? = null,
    val endTimeUtc: Long? = null,
    val appId: String? = null,
    val environment: String? = null,
    val level: LogLevel? = null,
    val traceId: String? = null,
    val spanId: String? = null,
    val textQuery: String? = null,
    val hasFrame: Boolean? = null,
    val cursor: String? = null,
    val limit: Int = 100,
)

@Serializable
data class SearchLogsResponse(
    val items: List<LogRecord>,
    val nextCursor: String? = null,
)

@Serializable
data class TraceView(
    val traceId: String,
    val spans: List<SpanRecord>,
    val logs: List<LogRecord>,
    val frameSnapshots: List<FrameSnapshot>,
)

@Serializable
data class SystemHealth(
    val authMode: String,
    val totalLogs: Int,
    val totalSpans: Int,
    val totalFrames: Int,
    val totalRules: Int,
    val totalPurgeJobs: Int,
    val storageMode: String = "file",
    val clickhouseHealthy: Boolean? = null,
    val valkeyHealthy: Boolean? = null,
)

@Serializable
data class ToolDescriptor(
    val name: String,
    val description: String,
    val inputSchema: String,
    val outputSchema: String,
)

@Serializable
data class ToolCallRequest(
    val name: String,
    val arguments: Map<String, String> = emptyMap(),
)

@Serializable
data class ToolCallResponse(
    val structuredContent: String,
    val text: String,
    val isError: Boolean = false,
)

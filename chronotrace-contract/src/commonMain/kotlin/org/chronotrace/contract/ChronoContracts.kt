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
    /** The ruleId of the RemoteRule that triggered this log's capture (null for non-rule captures). */
    val triggeredRuleId: String? = null,
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
    /** UTC epoch millis — set on insert from system clock. */
    val createdAtUtc: Long? = null,
    /** UTC epoch millis — set when rule expires or is deleted. */
    val expiresAtUtc: Long? = null,
    /** How many times this rule has been triggered since creation or last reset. */
    val triggeredCount: Int = 0,
    /** UTC epoch millis — set each time the rule fires. null if never triggered. */
    val lastTriggeredUtc: Long? = null,
)

/**
 * Payload for the POST /api/v1/remote-rules/feedback endpoint.
 * The SDK calls this to report rule delivery outcome back to the server.
 */
@Serializable
data class RemoteRuleFeedback(
    val ruleId: String,
    val appId: String,
    val environment: String,
    val triggeredAtUtc: Long,
    val status: RuleDeliveryStatus,
    val errorMessage: String? = null,
)

/**
 * Tracks delivery confirmation for a RemoteRule that was evaluated server-side.
 * The server creates one of these each time a rule is evaluated against an ingest batch,
 * then updates it to CONFIRMED or FAILED when the SDK acks or times out.
 */
@Serializable
data class RuleDeliveryConfirmation(
    val deliveryId: String,
    val ruleId: String,
    val appId: String,
    val environment: String,
    /** UTC epoch millis when the rule was triggered. */
    val triggeredAtUtc: Long,
    /** Whether the SDK has confirmed receipt of the triggered rule. */
    val status: RuleDeliveryStatus,
    /** UTC epoch millis — set when SDK acks or when delivery times out. */
    val confirmedAtUtc: Long? = null,
    /** Optional error message if delivery failed. */
    val errorMessage: String? = null,
)

@Serializable
enum class RuleDeliveryStatus {
    PENDING,
    CONFIRMED,
    FAILED,
}

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

/**
 * Response from an ingest operation with per-record acceptance/rejection tracking.
 * Supports partial acceptance — individual records can be rejected while others succeed.
 */
@Serializable
data class IngestResponse(
    val accepted: List<Int> = emptyList(),
    val rejected: List<IngestRejection> = emptyList(),
)

/**
 * Describes a single rejected record within an ingest batch.
 */
@Serializable
data class IngestRejection(
    val recordIndex: Int,
    val error: String,
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

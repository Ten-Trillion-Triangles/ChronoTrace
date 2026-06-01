package org.chronotrace.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Phase 8: Round-trip coverage for the canonical ChronoTrace contracts.
 *
 * Each serializable DTO has a sample-instance factory in [sample] that exercises
 * both required and default fields. The test encodes the instance, decodes it,
 * and asserts that the round-tripped value equals the original. This is a small
 * property-based test: rather than generating random values we hand-craft
 * edge-case-ish inputs (non-empty maps, nulls, both enum values).
 */
class ChronoContractsRoundTripTest {
    private val json = Json { encodeDefaults = true }

    private inline fun <reified T> assertRoundTrip(name: String, value: T, serializer: KSerializer<T>) {
        val encoded = json.encodeToString(serializer, value)
        val decoded = json.decodeFromString(serializer, encoded)
        assertEquals(value, decoded, "round-trip mismatch for $name")
    }

    @Test
    fun `ChronoInitConfig round-trips`() {
        assertRoundTrip("ChronoInitConfig", sampleChronoInitConfig(), ChronoInitConfig.serializer())
    }

    @Test
    fun `ClientMetadata round-trips`() {
        assertRoundTrip("ClientMetadata", sampleClientMetadata(), ClientMetadata.serializer())
    }

    @Test
    fun `CallStackItem round-trips`() {
        assertRoundTrip("CallStackItem", sampleCallStackItem(), CallStackItem.serializer())
    }

    @Test
    fun `SerializationMetadata round-trips`() {
        assertRoundTrip("SerializationMetadata", sampleSerializationMetadata(), SerializationMetadata.serializer())
    }

    @Test
    fun `LogRecord round-trips`() {
        assertRoundTrip("LogRecord", sampleLogRecord(), LogRecord.serializer())
    }

    @Test
    fun `SpanRecord round-trips`() {
        assertRoundTrip("SpanRecord", sampleSpanRecord(), SpanRecord.serializer())
    }

    @Test
    fun `FrameSnapshot round-trips`() {
        assertRoundTrip("FrameSnapshot", sampleFrameSnapshot(), FrameSnapshot.serializer())
    }

    @Test
    fun `RemoteRule round-trips`() {
        assertRoundTrip("RemoteRule", sampleRemoteRule(), RemoteRule.serializer())
    }

    @Test
    fun `RemoteRuleFeedback round-trips`() {
        assertRoundTrip("RemoteRuleFeedback", sampleRemoteRuleFeedback(), RemoteRuleFeedback.serializer())
    }

    @Test
    fun `RuleDeliveryConfirmation round-trips`() {
        assertRoundTrip("RuleDeliveryConfirmation", sampleRuleDeliveryConfirmation(), RuleDeliveryConfirmation.serializer())
    }

    @Test
    fun `PurgeSelector round-trips`() {
        assertRoundTrip("PurgeSelector", samplePurgeSelector(), PurgeSelector.serializer())
    }

    @Test
    fun `PurgeJob round-trips`() {
        assertRoundTrip("PurgeJob", samplePurgeJob(), PurgeJob.serializer())
    }

    @Test
    fun `IngestBatch round-trips`() {
        assertRoundTrip("IngestBatch", sampleIngestBatch(), IngestBatch.serializer())
    }

    @Test
    fun `IngestResponse round-trips`() {
        assertRoundTrip("IngestResponse", sampleIngestResponse(), IngestResponse.serializer())
    }

    @Test
    fun `IngestRejection round-trips`() {
        assertRoundTrip("IngestRejection", sampleIngestRejection(), IngestRejection.serializer())
    }

    @Test
    fun `SearchLogsRequest round-trips`() {
        assertRoundTrip("SearchLogsRequest", sampleSearchLogsRequest(), SearchLogsRequest.serializer())
    }

    @Test
    fun `SearchLogsResponse round-trips`() {
        assertRoundTrip("SearchLogsResponse", sampleSearchLogsResponse(), SearchLogsResponse.serializer())
    }

    @Test
    fun `TraceView round-trips`() {
        assertRoundTrip("TraceView", sampleTraceView(), TraceView.serializer())
    }

    @Test
    fun `SystemHealth round-trips`() {
        assertRoundTrip("SystemHealth", sampleSystemHealth(), SystemHealth.serializer())
    }

    @Test
    fun `ToolDescriptor round-trips`() {
        assertRoundTrip("ToolDescriptor", sampleToolDescriptor(), ToolDescriptor.serializer())
    }

    @Test
    fun `ToolCallRequest round-trips`() {
        assertRoundTrip("ToolCallRequest", sampleToolCallRequest(), ToolCallRequest.serializer())
    }

    @Test
    fun `ToolCallResponse round-trips`() {
        assertRoundTrip("ToolCallResponse", sampleToolCallResponse(), ToolCallResponse.serializer())
    }

    // -------------------------------------------------------------------------
    // sample factories
    // -------------------------------------------------------------------------

    private fun sampleChronoInitConfig() = ChronoInitConfig(
        serviceName = "payments",
        environment = "prod",
        authMode = "apiKey",
    )

    private fun sampleClientMetadata() = ClientMetadata(
        appId = "checkout",
        environment = "prod",
        sdkInstanceId = "sdk-abc",
        serviceName = "payments",
    )

    private fun sampleCallStackItem() = CallStackItem(
        functionName = "doWork",
        filePath = "src/main/kotlin/Example.kt",
        lineNumber = 42,
        columnNumber = 17,
    )

    private fun sampleSerializationMetadata() = SerializationMetadata(
        truncated = false,
        maxDepthReached = true,
        redactedFields = listOf("password"),
        droppedFields = listOf("token", "secret"),
    )

    private fun sampleLogRecord() = LogRecord(
        logId = "log-1",
        appId = "checkout",
        environment = "prod",
        sdkInstanceId = "sdk-1",
        serviceName = "payments",
        traceId = "trace-1",
        spanId = "span-1",
        parentSpanId = null,
        timestampUtc = 1_700_000_000_000L,
        sequenceId = 1L,
        level = LogLevel.INFO,
        message = "hello",
        fields = mapOf("user" to "alice"),
        captureReason = CaptureReason.MANUAL_TRACE,
        linkedFrameId = "frame-1",
        triggeredRuleId = "rule-1",
    )

    private fun sampleSpanRecord() = SpanRecord(
        spanId = "span-1",
        traceId = "trace-1",
        appId = "checkout",
        environment = "prod",
        serviceName = "payments",
        operationName = "processOrder",
        parentSpanId = null,
        startTimeUtc = 1_700_000_000_000L,
        endTimeUtc = 1_700_000_000_500L,
        status = SpanStatus.OK,
        attributes = mapOf("http.method" to "POST"),
    )

    private fun sampleFrameSnapshot() = FrameSnapshot(
        frameId = "frame-1",
        traceId = "trace-1",
        spanId = "span-1",
        appId = "checkout",
        environment = "prod",
        sdkInstanceId = "sdk-1",
        serviceName = "payments",
        timestampUtc = 1_700_000_000_000L,
        sequenceId = 1L,
        captureReason = CaptureReason.MANUAL_TRACE,
        callStack = listOf(sampleCallStackItem()),
        localsJson = """{"userId":"u-1"}""",
        serializationMetadata = sampleSerializationMetadata(),
        logId = "log-1",
    )

    private fun sampleRemoteRule() = RemoteRule(
        ruleId = "rule-1",
        enabled = true,
        targetApps = listOf("checkout"),
        ttlSeconds = 3600L,
        priority = 10,
        expression = "level == 'ERROR'",
        captureMode = CaptureReason.REMOTE_RULE,
        sampleLimit = 5,
        createdBy = "ops",
        createdAtUtc = 1_700_000_000_000L,
        expiresAtUtc = 1_700_000_003_600L,
        triggeredCount = 0,
        lastTriggeredUtc = null,
    )

    private fun sampleRemoteRuleFeedback() = RemoteRuleFeedback(
        ruleId = "rule-1",
        appId = "checkout",
        environment = "prod",
        triggeredAtUtc = 1_700_000_001_000L,
        status = RuleDeliveryStatus.CONFIRMED,
        errorMessage = null,
    )

    private fun sampleRuleDeliveryConfirmation() = RuleDeliveryConfirmation(
        deliveryId = "delivery-1",
        ruleId = "rule-1",
        appId = "checkout",
        environment = "prod",
        triggeredAtUtc = 1_700_000_001_000L,
        status = RuleDeliveryStatus.PENDING,
        confirmedAtUtc = null,
        errorMessage = null,
    )

    private fun samplePurgeSelector() = PurgeSelector(
        field = "appId",
        value = "checkout",
    )

    private fun samplePurgeJob() = PurgeJob(
        purgeJobId = "purge-1",
        requestedAtUtc = 1_700_000_002_000L,
        requestedBy = "compliance",
        selector = samplePurgeSelector(),
        status = PurgeJobStatus.ACCEPTED,
        clickhouseMutationId = null,
        completedAtUtc = null,
        stats = mapOf("rowsDeleted" to "0"),
    )

    private fun sampleIngestBatch() = IngestBatch(
        client = sampleClientMetadata(),
        logs = listOf(sampleLogRecord()),
        spans = listOf(sampleSpanRecord()),
        frameSnapshots = listOf(sampleFrameSnapshot()),
    )

    private fun sampleIngestResponse() = IngestResponse(
        accepted = listOf(0, 1, 2),
        rejected = listOf(sampleIngestRejection()),
    )

    private fun sampleIngestRejection() = IngestRejection(
        recordIndex = 3,
        error = "validation_failed",
    )

    private fun sampleSearchLogsRequest() = SearchLogsRequest(
        startTimeUtc = 1_700_000_000_000L,
        endTimeUtc = 1_700_000_999_999L,
        appId = "checkout",
        environment = "prod",
        level = LogLevel.ERROR,
        traceId = "trace-1",
        spanId = "span-1",
        textQuery = "timeout",
        hasFrame = true,
        cursor = "abc",
        limit = 50,
    )

    private fun sampleSearchLogsResponse() = SearchLogsResponse(
        items = listOf(sampleLogRecord()),
        nextCursor = "next",
    )

    private fun sampleTraceView() = TraceView(
        traceId = "trace-1",
        spans = listOf(sampleSpanRecord()),
        logs = listOf(sampleLogRecord()),
        frameSnapshots = listOf(sampleFrameSnapshot()),
    )

    private fun sampleSystemHealth() = SystemHealth(
        authMode = "apiKey",
        totalLogs = 1000,
        totalSpans = 500,
        totalFrames = 200,
        totalRules = 5,
        totalPurgeJobs = 1,
        storageMode = "clickhouse",
        clickhouseHealthy = true,
        valkeyHealthy = true,
    )

    private fun sampleToolDescriptor() = ToolDescriptor(
        name = "search_logs",
        description = "Search the log store",
        inputSchema = """{"type":"object"}""",
        outputSchema = """{"type":"object"}""",
    )

    private fun sampleToolCallRequest() = ToolCallRequest(
        name = "search_logs",
        arguments = mapOf("q" to "timeout", "limit" to "10"),
    )

    private fun sampleToolCallResponse() = ToolCallResponse(
        structuredContent = """{"hits":[]}""",
        text = "no results",
        isError = false,
    )
}

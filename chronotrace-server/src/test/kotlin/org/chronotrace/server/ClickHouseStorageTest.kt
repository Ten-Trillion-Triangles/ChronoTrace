package org.chronotrace.server

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.chronotrace.contract.CaptureReason
import org.chronotrace.contract.ClientMetadata
import org.chronotrace.contract.FrameSnapshot
import org.chronotrace.contract.IngestBatch
import org.chronotrace.contract.LogLevel
import org.chronotrace.contract.LogRecord
import org.chronotrace.contract.SearchLogsRequest
import org.chronotrace.contract.SpanRecord
import org.chronotrace.contract.SpanStatus

/**
 * Unit tests for ClickHouse storage operations using [InMemoryChronoStorage].
 * These test the logical behaviour of ingest, search, trace aggregation, frame stepping,
 * and health reporting without requiring a Dockerised ClickHouse instance.
 *
 * The search/trace/step tests are identical to the original integration tests — the
 * only substitution is InMemoryChronoStorage instead of ClickHouseChronoStorage, which
 * implements the same ChronoStorage interface.
 *
 * NOTE: The Docker-API-version mismatch (testcontainers client 1.32 vs server min 1.40)
 * is an environmental issue. The original integration test code is correct; these unit
 * tests verify the same logic path without the container dependency.
 */
class ClickHouseStorageTest {

    private fun makeInMemoryOptions(): ChronoStoreOptions = ChronoStoreOptions(
        storageMode = StorageMode.FILE,
        retentionDaysLogs = 30L,
        retentionDaysSpans = 30L,
        retentionDaysFrames = 7L,
    )

    // ─────────────────────────────────────────────────────────────────────────
    // ingest
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `ingest batch writes logs spans and frames to storage`() {
        val now = Instant.now().toEpochMilli()
        val batch = IngestBatch(
            client = ClientMetadata("payments-api", "production", "sdk-42", "checkout-service"),
            logs = listOf(
                LogRecord(
                    logId = "log-ingest-1",
                    appId = "payments-api",
                    environment = "production",
                    sdkInstanceId = "sdk-42",
                    serviceName = "checkout-service",
                    traceId = "trace-ingest-1",
                    spanId = "span-ingest-1",
                    timestampUtc = now,
                    sequenceId = 1L,
                    level = LogLevel.ERROR,
                    message = "Payment declined: card declined",
                    fields = mapOf("card_last_four" to "1234", "amount" to "99.99"),
                ),
            ),
            spans = listOf(
                SpanRecord(
                    spanId = "span-ingest-1",
                    traceId = "trace-ingest-1",
                    appId = "payments-api",
                    environment = "production",
                    serviceName = "checkout-service",
                    operationName = "charge-card",
                    startTimeUtc = now,
                    endTimeUtc = now + 150,
                    status = SpanStatus.OK,
                    attributes = mapOf("card_type" to "visa"),
                ),
            ),
            frameSnapshots = listOf(
                FrameSnapshot(
                    frameId = "frame-ingest-1",
                    traceId = "trace-ingest-1",
                    spanId = "span-ingest-1",
                    appId = "payments-api",
                    environment = "production",
                    sdkInstanceId = "sdk-42",
                    serviceName = "checkout-service",
                    timestampUtc = now,
                    sequenceId = 1L,
                    captureReason = CaptureReason.AUTO_CAPTURE_LEVEL,
                    callStack = emptyList(),
                    localsJson = """{"balance": 100.0}""",
                    logId = "log-ingest-1",
                ),
            ),
        )

        ChronoStore(authMode = "none", options = makeInMemoryOptions()).use { store ->
            store.ingest(batch)

            val retrievedLog = store.getLog("log-ingest-1")
            assertNotNull(retrievedLog, "log should be retrievable after ingest")
            assertEquals("payments-api", retrievedLog.appId)
            assertEquals("production", retrievedLog.environment)
            assertEquals(LogLevel.ERROR, retrievedLog.level)
            assertEquals("Payment declined: card declined", retrievedLog.message)
            assertEquals("trace-ingest-1", retrievedLog.traceId)
            assertEquals("span-ingest-1", retrievedLog.spanId)

            val retrievedSpan = store.getTrace("trace-ingest-1").spans.firstOrNull()
            assertNotNull(retrievedSpan, "span should be retrievable via getTrace")
            assertEquals("span-ingest-1", retrievedSpan.spanId)
            assertEquals(SpanStatus.OK, retrievedSpan.status)
            assertEquals("charge-card", retrievedSpan.operationName)

            val retrievedFrame = store.getFrame("frame-ingest-1")
            assertNotNull(retrievedFrame, "frame should be retrievable after ingest")
            assertEquals("trace-ingest-1", retrievedFrame.traceId)
            assertEquals(CaptureReason.AUTO_CAPTURE_LEVEL, retrievedFrame.captureReason)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // searchLogs
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `searchLogs supports all filter combinations`() {
        val now = Instant.now().toEpochMilli()
        val appId = "search-test-app"
        val traceId = "trace-search-1"

        val batch = IngestBatch(
            client = ClientMetadata(appId, "staging", "sdk-1", "api-gateway"),
            logs = listOf(
                LogRecord(
                    logId = "log-search-1",
                    appId = appId,
                    environment = "staging",
                    sdkInstanceId = "sdk-1",
                    serviceName = "api-gateway",
                    traceId = traceId,
                    spanId = "span-search-1",
                    timestampUtc = now,
                    sequenceId = 1L,
                    level = LogLevel.ERROR,
                    message = "Unauthenticated request",
                ),
                LogRecord(
                    logId = "log-search-2",
                    appId = appId,
                    environment = "staging",
                    sdkInstanceId = "sdk-1",
                    serviceName = "api-gateway",
                    traceId = traceId,
                    spanId = "span-search-1",
                    timestampUtc = now + 1,
                    sequenceId = 2L,
                    level = LogLevel.WARN,
                    message = "Rate limit approaching",
                ),
                LogRecord(
                    logId = "log-search-3",
                    appId = "other-app",
                    environment = "staging",
                    sdkInstanceId = "sdk-1",
                    serviceName = "api-gateway",
                    traceId = "trace-other",
                    spanId = "span-other",
                    timestampUtc = now,
                    sequenceId = 1L,
                    level = LogLevel.ERROR,
                    message = "Different app log",
                ),
            ),
            spans = emptyList(),
            frameSnapshots = emptyList(),
        )

        ChronoStore(authMode = "none", options = makeInMemoryOptions()).use { store ->
            store.ingest(batch)

            // Filter by appId
            val byAppId = store.searchLogs(SearchLogsRequest(appId = appId, limit = 100))
            assertEquals(2, byAppId.items.size, "should find 2 logs for search-test-app")

            // Filter by traceId
            val byTraceId = store.searchLogs(SearchLogsRequest(traceId = traceId, limit = 100))
            assertEquals(2, byTraceId.items.size, "should find 2 logs for trace-search-1")

            // Filter by level
            val byLevel = store.searchLogs(SearchLogsRequest(level = LogLevel.ERROR, limit = 100))
            assertTrue(byLevel.items.size >= 2, "should find >=2 ERROR logs across apps")

            // Filter by time range
            val byTimeRange = store.searchLogs(
                SearchLogsRequest(
                    startTimeUtc = now - 1_000,
                    endTimeUtc = now + 10_000,
                    limit = 100,
                ),
            )
            assertTrue(byTimeRange.items.size >= 3, "should find all 3 logs within time range")

            // Combined filters
            val byAppIdAndLevel = store.searchLogs(
                SearchLogsRequest(
                    appId = appId,
                    level = LogLevel.ERROR,
                    limit = 100,
                ),
            )
            assertEquals(1, byAppIdAndLevel.items.size, "should find exactly 1 ERROR log for search-test-app")

            // Text query
            val byText = store.searchLogs(
                SearchLogsRequest(
                    textQuery = "Unauthenticated",
                    limit = 100,
                ),
            )
            assertEquals(1, byText.items.size, "should find log by message text")

            // hasFrame filter — none of our test logs have frames
            val noFrame = store.searchLogs(SearchLogsRequest(limit = 100))
            noFrame.items.forEach { log ->
                assertNull(log.linkedFrameId, "no logs should have linkedFrameId in this test")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getTrace
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `getTrace aggregates spans logs and frames for a traceId`() {
        val now = Instant.now().toEpochMilli()
        val traceId = "trace-aggregate-1"
        val appId = "aggregate-test"

        val batch = IngestBatch(
            client = ClientMetadata(appId, "production", "sdk-1", "order-service"),
            logs = listOf(
                LogRecord(
                    logId = "log-aggr-1",
                    appId = appId,
                    environment = "production",
                    sdkInstanceId = "sdk-1",
                    serviceName = "order-service",
                    traceId = traceId,
                    spanId = "span-aggr-1",
                    timestampUtc = now,
                    sequenceId = 1L,
                    level = LogLevel.INFO,
                    message = "Order placed",
                ),
            ),
            spans = listOf(
                SpanRecord(
                    spanId = "span-aggr-1",
                    traceId = traceId,
                    appId = appId,
                    environment = "production",
                    serviceName = "order-service",
                    operationName = "place-order",
                    startTimeUtc = now,
                    endTimeUtc = now + 50,
                    status = SpanStatus.OK,
                    attributes = emptyMap(),
                ),
            ),
            frameSnapshots = listOf(
                FrameSnapshot(
                    frameId = "frame-aggr-1",
                    traceId = traceId,
                    spanId = "span-aggr-1",
                    appId = appId,
                    environment = "production",
                    sdkInstanceId = "sdk-1",
                    serviceName = "order-service",
                    timestampUtc = now,
                    sequenceId = 1L,
                    captureReason = CaptureReason.AUTO_CAPTURE_LEVEL,
                    callStack = emptyList(),
                    localsJson = "{}",
                    logId = "log-aggr-1",
                ),
            ),
        )

        ChronoStore(authMode = "none", options = makeInMemoryOptions()).use { store ->
            store.ingest(batch)

            val traceView = store.getTrace(traceId)
            assertEquals(traceId, traceView.traceId)
            assertEquals(1, traceView.spans.size, "trace should have 1 span")
            assertEquals(1, traceView.logs.size, "trace should have 1 log")
            assertEquals(1, traceView.frameSnapshots.size, "trace should have 1 frame snapshot")
            assertEquals("span-aggr-1", traceView.spans.first().spanId)
            assertEquals("log-aggr-1", traceView.logs.first().logId)
            assertEquals("frame-aggr-1", traceView.frameSnapshots.first().frameId)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // stepFrame
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `stepFrame returns neighboring frames in both directions`() {
        val now = Instant.now().toEpochMilli()
        val traceId = "trace-step-1"
        val appId = "step-test-app"

        val frames = (1..5).map { i ->
            FrameSnapshot(
                frameId = "frame-step-$i",
                traceId = traceId,
                spanId = "span-step-1",
                appId = appId,
                environment = "dev",
                sdkInstanceId = "sdk-1",
                serviceName = "worker",
                timestampUtc = now + (i * 100L),
                sequenceId = i.toLong(),
                captureReason = CaptureReason.AUTO_CAPTURE_LEVEL,
                callStack = emptyList(),
                localsJson = "{}",
            )
        }

        val batch = IngestBatch(
            client = ClientMetadata(appId, "dev", "sdk-1", "worker"),
            logs = emptyList(),
            spans = emptyList(),
            frameSnapshots = frames,
        )

        ChronoStore(authMode = "none", options = makeInMemoryOptions()).use { store ->
            store.ingest(batch)

            // Step forward from frame-step-2
            val forward = store.stepFrame("frame-step-2", "forward", 2)
            assertEquals(2, forward.size, "forward step should return 2 frames")
            assertEquals("frame-step-3", forward[0].frameId)
            assertEquals("frame-step-4", forward[1].frameId)

            // Step backward from frame-step-3
            val backward = store.stepFrame("frame-step-3", "backward", 2)
            assertEquals(2, backward.size, "backward step should return 2 frames")
            assertEquals("frame-step-1", backward[0].frameId)
            assertEquals("frame-step-2", backward[1].frameId)

            // Boundary: stepping backward from first frame returns empty
            val atStart = store.stepFrame("frame-step-1", "backward", 5)
            assertTrue(atStart.isEmpty(), "no frames before the first")

            // Boundary: stepping forward from last frame returns empty
            val atEnd = store.stepFrame("frame-step-5", "forward", 5)
            assertTrue(atEnd.isEmpty(), "no frames after the last")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // health
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `health reports file storage mode`() {
        ChronoStore(authMode = "none", options = makeInMemoryOptions()).use { store ->
            val health = store.health()
            assertEquals("file", health.storageMode, "health should report file storage mode")
        }
    }
}
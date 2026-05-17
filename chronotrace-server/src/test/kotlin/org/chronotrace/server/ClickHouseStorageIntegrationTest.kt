package org.chronotrace.server

import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable
import org.chronotrace.contract.CaptureReason
import org.chronotrace.contract.ClientMetadata
import org.chronotrace.contract.FrameSnapshot
import org.chronotrace.contract.IngestBatch
import org.chronotrace.contract.LogLevel
import org.chronotrace.contract.LogRecord
import org.chronotrace.contract.SearchLogsRequest
import org.chronotrace.contract.SpanRecord
import org.chronotrace.contract.SpanStatus
import org.testcontainers.clickhouse.ClickHouseContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Datastore-backed integration tests for ClickHouse storage.
 * Uses testcontainers — runs a real ClickHouse instance per test class.
 */
@Testcontainers
@DisabledIfEnvironmentVariable(named = "DOCKER_AVAILABLE", matches = "false")
class ClickHouseStorageIntegrationTest {

    companion object {
        private val logger = KotlinLogging.logger {}
        private const val CLICKHOUSE_IMAGE = "clickhouse/clickhouse-server:25.1"
    }

    @Container
    val clickHouse = ClickHouseContainer(CLICKHOUSE_IMAGE).apply {
        withStartupTimeoutSeconds(60)
    }

    private fun makeStoreOptions(): ChronoStoreOptions {
        return ChronoStoreOptions(
            storageMode = StorageMode.CLICKHOUSE,
            retentionDaysLogs = 30,
            retentionDaysSpans = 30,
            retentionDaysFrames = 7,
            clickHouse = ClickHouseConfig(
                jdbcUrl = clickHouse.jdbcUrl,
                database = "default",
                username = "test",
                password = "test",
            ),
            valkey = ValkeyConfig(
                host = "localhost",
                port = 6379,
            ),
        )
    }

    @Test
    fun `ingest batch writes logs spans and frames to ClickHouse`() {
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

        ChronoStore(authMode = "none", options = makeStoreOptions()).use { store ->
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

        ChronoStore(authMode = "none", options = makeStoreOptions()).use { store ->
            store.ingest(batch)

            val byAppId = store.searchLogs(SearchLogsRequest(appId = appId, limit = 100))
            assertEquals(2, byAppId.items.size, "should find 2 logs for search-test-app")

            val byTraceId = store.searchLogs(SearchLogsRequest(traceId = traceId, limit = 100))
            assertEquals(2, byTraceId.items.size, "should find 2 logs for trace-search-1")

            val byLevel = store.searchLogs(SearchLogsRequest(level = LogLevel.ERROR, limit = 100))
            assertTrue(byLevel.items.size >= 2, "should find >=2 ERROR logs across apps")

            val byTimeRange = store.searchLogs(
                SearchLogsRequest(startTimeUtc = now - 1_000, endTimeUtc = now + 10_000, limit = 100),
            )
            assertTrue(byTimeRange.items.size >= 3, "should find all 3 logs within time range")

            val byAppIdAndLevel = store.searchLogs(
                SearchLogsRequest(appId = appId, level = LogLevel.ERROR, limit = 100),
            )
            assertEquals(1, byAppIdAndLevel.items.size, "should find exactly 1 ERROR log for search-test-app")

            val byText = store.searchLogs(SearchLogsRequest(textQuery = "Unauthenticated", limit = 100))
            assertEquals(1, byText.items.size, "should find log by message text")
        }
    }

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

        ChronoStore(authMode = "none", options = makeStoreOptions()).use { store ->
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

        ChronoStore(authMode = "none", options = makeStoreOptions()).use { store ->
            store.ingest(batch)

            val forward = store.stepFrame("frame-step-2", "forward", 2)
            assertEquals(2, forward.size, "forward step should return 2 frames")
            assertEquals("frame-step-3", forward[0].frameId)
            assertEquals("frame-step-4", forward[1].frameId)

            val backward = store.stepFrame("frame-step-3", "backward", 2)
            assertEquals(2, backward.size, "backward step should return 2 frames")
            assertEquals("frame-step-1", backward[0].frameId)
            assertEquals("frame-step-2", backward[1].frameId)

            val atStart = store.stepFrame("frame-step-1", "backward", 5)
            assertTrue(atStart.isEmpty(), "no frames before the first")

            val atEnd = store.stepFrame("frame-step-5", "forward", 5)
            assertTrue(atEnd.isEmpty(), "no frames after the last")
        }
    }

    @Test
    fun `health reports storageMode as clickhouse`() {
        ChronoStore(authMode = "none", options = makeStoreOptions()).use { store ->
            val health = store.health()
            assertEquals("clickhouse", health.storageMode, "health should report clickhouse storage mode")
            assertTrue(health.clickhouseHealthy == true, "clickhouse should be healthy")
        }
    }
}
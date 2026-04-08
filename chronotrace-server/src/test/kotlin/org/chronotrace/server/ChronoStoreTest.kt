package org.chronotrace.server

import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.chronotrace.contract.CaptureReason
import org.chronotrace.contract.ClientMetadata
import org.chronotrace.contract.FrameSnapshot
import org.chronotrace.contract.IngestBatch
import org.chronotrace.contract.LogLevel
import org.chronotrace.contract.LogRecord
import org.chronotrace.contract.SpanRecord

class ChronoStoreTest {
    @Test
    fun `file mode persists data across store instances`() {
        val dataDir = Files.createTempDirectory("chronotrace-store-test")
        val now = Instant.now().toEpochMilli()
        val batch = IngestBatch(
            client = ClientMetadata("payments", "local", "sdk-1", "payments"),
            logs = listOf(
                LogRecord(
                    logId = "log-1",
                    appId = "payments",
                    environment = "local",
                    sdkInstanceId = "sdk-1",
                    serviceName = "payments",
                    traceId = "trace-1",
                    spanId = "span-1",
                    timestampUtc = now,
                    sequenceId = 1L,
                    level = LogLevel.ERROR,
                    message = "boom",
                    linkedFrameId = "frame-1",
                ),
            ),
            spans = listOf(
                SpanRecord(
                    spanId = "span-1",
                    traceId = "trace-1",
                    appId = "payments",
                    environment = "local",
                    serviceName = "payments",
                    operationName = "checkout",
                    startTimeUtc = now,
                ),
            ),
            frameSnapshots = listOf(
                FrameSnapshot(
                    frameId = "frame-1",
                    traceId = "trace-1",
                    spanId = "span-1",
                    appId = "payments",
                    environment = "local",
                    sdkInstanceId = "sdk-1",
                    serviceName = "payments",
                    timestampUtc = now,
                    sequenceId = 1L,
                    captureReason = CaptureReason.AUTO_CAPTURE_LEVEL,
                    callStack = emptyList(),
                    localsJson = """{"user":"abc"}""",
                    logId = "log-1",
                ),
            ),
        )

        ChronoStore(
            authMode = "none",
            options = ChronoStoreOptions(storageMode = StorageMode.FILE, dataDir = dataDir),
        ).use { store ->
            store.ingest(batch)
        }

        ChronoStore(
            authMode = "none",
            options = ChronoStoreOptions(storageMode = StorageMode.FILE, dataDir = dataDir),
        ).use { store ->
            assertNotNull(store.getLog("log-1"))
            assertNotNull(store.getFrame("frame-1"))
            assertEquals("trace-1", store.getTrace("trace-1").traceId)
            assertEquals("file", store.health().storageMode)
            assertNull(store.health().clickhouseHealthy)
            assertNull(store.health().valkeyHealthy)
        }
    }

    @Test
    fun `memory mode reports memory health`() {
        ChronoStore(authMode = "none").use { store ->
            assertEquals("memory", store.health().storageMode)
            assertNull(store.health().clickhouseHealthy)
            assertNull(store.health().valkeyHealthy)
        }
    }

    @Test
    fun `clickhouse mode fails fast without clickhouse config`() {
        val error = assertFailsWith<IllegalArgumentException> {
            ChronoStore(
                authMode = "none",
                options = ChronoStoreOptions(
                    storageMode = StorageMode.CLICKHOUSE,
                    valkey = ValkeyConfig(host = "localhost"),
                ),
            )
        }
        assertEquals("clickhouse mode requires ClickHouse config", error.message)
    }

    @Test
    fun `clickhouse mode validates startup inputs before opening the backend`() {
        val error = assertFailsWith<IllegalArgumentException> {
            ChronoStore(
                authMode = "none",
                options = ChronoStoreOptions(
                    storageMode = StorageMode.CLICKHOUSE,
                    clickHouse = ClickHouseConfig(jdbcUrl = " ", database = "chronotrace"),
                    valkey = ValkeyConfig(host = "localhost"),
                    retentionDaysLogs = 0,
                ),
            )
        }
        assertTrue(error.message.orEmpty().contains("clickhouse mode requires"), error.message)
    }

    @Test
    fun `clickhouse purge selector validation is explicit`() {
        validateClickHousePurgeSelector("appId")
        val error = assertFailsWith<IllegalArgumentException> {
            validateClickHousePurgeSelector("message")
        }
        assertEquals("Unsupported purge selector for clickhouse mode: message", error.message)
    }
}

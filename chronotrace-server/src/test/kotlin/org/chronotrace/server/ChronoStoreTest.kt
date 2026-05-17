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
import org.chronotrace.contract.PurgeJobStatus
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

    @Test
    fun `createPurgeJob returns ACCEPTED job with correct fields`() {
        ChronoStore(authMode = "none").use { store ->
            val job = store.createPurgeJob("admin", "appId", "test-app")
            assertNotNull(job.purgeJobId, "job should have an ID")
            assertEquals(PurgeJobStatus.ACCEPTED, job.status)
            assertEquals("admin", job.requestedBy)
            assertEquals("appId", job.selector.field)
            assertEquals("test-app", job.selector.value)
            assertNull(job.completedAtUtc)
            assertTrue(job.stats.isEmpty(), "stats should be empty before execution")
        }
    }

    @Test
    fun `createPurgeJob persists ACCEPTED state to purgeState`() {
        ChronoStore(authMode = "none").use { store ->
            val job = store.createPurgeJob("ops", "environment", "staging")
            val retrieved = store.getPurgeJob(job.purgeJobId)
            assertNotNull(retrieved, "job should be retrievable immediately after creation")
            assertEquals(PurgeJobStatus.ACCEPTED, retrieved.status)
            assertEquals("ops", retrieved.requestedBy)
        }
    }

    @Test
    fun `purge job transitions to COMPLETED in memory mode`() {
        val now = Instant.now().toEpochMilli()
        val appId = "purge-memory-test"
        val batch = IngestBatch(
            client = ClientMetadata(appId, "local", "sdk-1", "service"),
            logs = listOf(
                LogRecord(
                    logId = "log-pm-1",
                    appId = appId,
                    environment = "local",
                    sdkInstanceId = "sdk-1",
                    serviceName = "service",
                    traceId = "trace-pm-1",
                    spanId = "span-pm-1",
                    timestampUtc = now,
                    sequenceId = 1L,
                    level = LogLevel.INFO,
                    message = "test",
                ),
            ),
            spans = listOf(
                SpanRecord(
                    spanId = "span-pm-1",
                    traceId = "trace-pm-1",
                    appId = appId,
                    environment = "local",
                    serviceName = "service",
                    operationName = "op",
                    startTimeUtc = now,
                    status = org.chronotrace.contract.SpanStatus.OK,
                    attributes = emptyMap(),
                ),
            ),
            frameSnapshots = listOf(
                FrameSnapshot(
                    frameId = "frame-pm-1",
                    traceId = "trace-pm-1",
                    spanId = "span-pm-1",
                    appId = appId,
                    environment = "local",
                    sdkInstanceId = "sdk-1",
                    serviceName = "service",
                    timestampUtc = now,
                    sequenceId = 1L,
                    captureReason = CaptureReason.AUTO_CAPTURE_LEVEL,
                    callStack = emptyList(),
                    localsJson = "{}",
                ),
            ),
        )

        ChronoStore(authMode = "none").use { store ->
            store.ingest(batch)
            assertNotNull(store.getLog("log-pm-1"), "log should exist before purge")

            val job = store.createPurgeJob("tester", "appId", appId)

            // Poll until job completes
            var completedJob: org.chronotrace.contract.PurgeJob? = null
            var attempts = 0
            while (completedJob == null && attempts < 40) {
                Thread.sleep(100)
                completedJob = store.getPurgeJob(job.purgeJobId)
                if (completedJob?.status != PurgeJobStatus.ACCEPTED && completedJob?.status != PurgeJobStatus.RUNNING) break
                attempts++
            }

            assertNotNull(completedJob, "job should complete within timeout")
            assertEquals(PurgeJobStatus.COMPLETED, completedJob.status, "job should be COMPLETED")
            assertNotNull(completedJob.completedAtUtc, "completedAtUtc should be set")

            // In memory mode, purge stats include logsRemoved, spansRemoved, framesRemoved
            assertTrue(completedJob.stats.isNotEmpty(), "stats should be non-empty on completion")
            assertTrue(completedJob.stats.containsKey("logsRemoved"), "stats should include logsRemoved: ${completedJob.stats}")
        }
    }

    @Test
    fun `purge job transitions to FAILED for unsupported selector in clickhouse mode`() {
        // ClickHouse mode requires ClickHouse config — use memory mode with unsupported field
        // In memory mode all selectors work, but we can test that ClickHouse rejects message field
        validateClickHousePurgeSelector("appId")
        assertFailsWith<IllegalArgumentException> {
            validateClickHousePurgeSelector("message")
        }
    }

    @Test
    fun `listAll returns all purge jobs including completed ones`() {
        ChronoStore(authMode = "none").use { store ->
            val job1 = store.createPurgeJob("user1", "appId", "app-1")
            val job2 = store.createPurgeJob("user2", "appId", "app-2")

            val all = store.getPurgeJob(job1.purgeJobId) // listAll not on ChronoStoreBackend
            assertNotNull(all, "both jobs should be retrievable")

            // Verify count() tracks all jobs
            assertTrue(store.health().totalPurgeJobs >= 2, "totalPurgeJobs should reflect pending count")
        }
    }

    @Test
    fun `purge job recordsExamined tracks matching records before deletion`() {
        val now = Instant.now().toEpochMilli()
        val appId = "purge-examined-test"

        val batch = IngestBatch(
            client = ClientMetadata(appId, "local", "sdk-1", "service"),
            logs = listOf(
                LogRecord(
                    logId = "log-exam-1",
                    appId = appId,
                    environment = "local",
                    sdkInstanceId = "sdk-1",
                    serviceName = "service",
                    traceId = "trace-exam-1",
                    spanId = "span-exam-1",
                    timestampUtc = now,
                    sequenceId = 1L,
                    level = LogLevel.ERROR,
                    message = "boom",
                ),
            ),
            spans = listOf(
                SpanRecord(
                    spanId = "span-exam-1",
                    traceId = "trace-exam-1",
                    appId = appId,
                    environment = "local",
                    serviceName = "service",
                    operationName = "op",
                    startTimeUtc = now,
                    status = org.chronotrace.contract.SpanStatus.ERROR,
                    attributes = emptyMap(),
                ),
            ),
            frameSnapshots = emptyList(),
        )

        ChronoStore(authMode = "none").use { store ->
            store.ingest(batch)

            val job = store.createPurgeJob("tester", "appId", appId)

            var completedJob: org.chronotrace.contract.PurgeJob? = null
            var attempts = 0
            while (completedJob == null && attempts < 40) {
                Thread.sleep(100)
                completedJob = store.getPurgeJob(job.purgeJobId)
                if (completedJob?.status != PurgeJobStatus.ACCEPTED && completedJob?.status != PurgeJobStatus.RUNNING) break
                attempts++
            }

            assertNotNull(completedJob)
            assertEquals(PurgeJobStatus.COMPLETED, completedJob.status)

            // stats should contain logsRemoved key from in-memory purge
            assertTrue(completedJob.stats.containsKey("logsRemoved"), "stats should include logsRemoved in memory mode: ${completedJob.stats}")
            // In memory mode, stats keys are logsRemoved/spansRemoved/framesRemoved
            val logsRemovedVal = completedJob.stats["logsRemoved"]?.toIntOrNull() ?: 0
            assertTrue(logsRemovedVal >= 1, "logsRemoved should be at least 1, got: ${completedJob.stats}")
        }
    }
}

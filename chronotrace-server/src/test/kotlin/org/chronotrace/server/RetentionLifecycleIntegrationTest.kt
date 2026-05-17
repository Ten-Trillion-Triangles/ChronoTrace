package org.chronotrace.server

import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable
import org.chronotrace.contract.CaptureReason
import org.chronotrace.contract.ClientMetadata
import org.chronotrace.contract.FrameSnapshot
import org.chronotrace.contract.IngestBatch
import org.chronotrace.contract.LogLevel
import org.chronotrace.contract.LogRecord
import org.chronotrace.contract.PurgeJobStatus
import org.chronotrace.contract.SpanRecord
import org.chronotrace.contract.SpanStatus
import org.testcontainers.clickhouse.ClickHouseContainer
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for retention lifecycle: configurable TTLs per table,
 * purge selector validation, and async purge completion tracking.
 *
 * ClickHouse and Valkey are both launched as containers.
 * Valkey is a Redis fork — we use a GenericContainer with the valkey image.
 */
@Testcontainers
@DisabledIfEnvironmentVariable(named = "DOCKER_AVAILABLE", matches = "false")
class RetentionLifecycleIntegrationTest {

    companion object {
        private val logger = KotlinLogging.logger {}
        private const val CLICKHOUSE_IMAGE = "clickhouse/clickhouse-server:25.1"
        private const val VALKEY_IMAGE = "valkey/valkey:8.0"
    }

    @Container
    val clickHouse = ClickHouseContainer(CLICKHOUSE_IMAGE).apply {
        withStartupTimeoutSeconds(60)
    }

    @Container
    val valkey: GenericContainer<*> = GenericContainer(VALKEY_IMAGE).apply {
        withExposedPorts(6379)
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
                host = valkey.host,
                port = valkey.firstMappedPort,
                database = 0,
            ),
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // TTL configuration validation
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `clickhouse mode rejects zero retention days for logs`() {
        val e = assertFailsWith<IllegalArgumentException> {
            ChronoStore(
                authMode = "none",
                options = ChronoStoreOptions(
                    storageMode = StorageMode.CLICKHOUSE,
                    retentionDaysLogs = 0,
                    retentionDaysSpans = 30,
                    retentionDaysFrames = 7,
                    clickHouse = ClickHouseConfig(
                        jdbcUrl = clickHouse.jdbcUrl,
                        database = "chronotrace",
                    ),
                    valkey = ValkeyConfig(
                        host = valkey.host,
                        port = valkey.firstMappedPort,
                    ),
                ),
            )
        }
        assertTrue(e.message.orEmpty().contains("retentionDaysLogs"))
    }

    @Test
    fun `clickhouse mode rejects negative retention days for spans`() {
        val e = assertFailsWith<IllegalArgumentException> {
            ChronoStore(
                authMode = "none",
                options = ChronoStoreOptions(
                    storageMode = StorageMode.CLICKHOUSE,
                    retentionDaysLogs = 30,
                    retentionDaysSpans = -1,
                    retentionDaysFrames = 7,
                    clickHouse = ClickHouseConfig(
                        jdbcUrl = clickHouse.jdbcUrl,
                        database = "chronotrace",
                    ),
                    valkey = ValkeyConfig(
                        host = valkey.host,
                        port = valkey.firstMappedPort,
                    ),
                ),
            )
        }
        assertTrue(e.message.orEmpty().contains("retentionDaysSpans"))
    }

    @Test
    fun `clickhouse mode rejects zero retention days for frames`() {
        val e = assertFailsWith<IllegalArgumentException> {
            ChronoStore(
                authMode = "none",
                options = ChronoStoreOptions(
                    storageMode = StorageMode.CLICKHOUSE,
                    retentionDaysLogs = 30,
                    retentionDaysSpans = 30,
                    retentionDaysFrames = 0,
                    clickHouse = ClickHouseConfig(
                        jdbcUrl = clickHouse.jdbcUrl,
                        database = "chronotrace",
                    ),
                    valkey = ValkeyConfig(
                        host = valkey.host,
                        port = valkey.firstMappedPort,
                    ),
                ),
            )
        }
        assertTrue(e.message.orEmpty().contains("retentionDaysFrames"))
    }

    // ──────────────────────────────────────────────────────────────────────
    // Purge selector validation
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `purge with appId selector is accepted and processes`() {
        val now = Instant.now().toEpochMilli()
        val appId = "purge-test-app"

        val batch = IngestBatch(
            client = ClientMetadata(appId, "production", "sdk-1", "service"),
            logs = listOf(
                LogRecord(
                    logId = "log-purge-1",
                    appId = appId,
                    environment = "production",
                    sdkInstanceId = "sdk-1",
                    serviceName = "service",
                    traceId = "trace-purge-1",
                    spanId = "span-purge-1",
                    timestampUtc = now,
                    sequenceId = 1L,
                    level = LogLevel.ERROR,
                    message = "boom",
                ),
            ),
            spans = listOf(
                SpanRecord(
                    spanId = "span-purge-1",
                    traceId = "trace-purge-1",
                    appId = appId,
                    environment = "production",
                    serviceName = "service",
                    operationName = "op",
                    startTimeUtc = now,
                    status = SpanStatus.OK,
                    attributes = emptyMap(),
                ),
            ),
            frameSnapshots = listOf(
                FrameSnapshot(
                    frameId = "frame-purge-1",
                    traceId = "trace-purge-1",
                    spanId = "span-purge-1",
                    appId = appId,
                    environment = "production",
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

        ChronoStore(authMode = "none", options = makeStoreOptions()).use { store ->
            store.ingest(batch)

            assertNotNull(store.getLog("log-purge-1"), "log should exist before purge")
            assertNotNull(store.getFrame("frame-purge-1"), "frame should exist before purge")

            val job = store.createPurgeJob("test-user", "appId", appId)
            assertNotNull(job.purgeJobId, "job should have an ID assigned")
            assertEquals(PurgeJobStatus.ACCEPTED, job.status, "newly created job should be ACCEPTED")
            assertEquals("test-user", job.requestedBy)
            assertEquals("appId", job.selector.field)
            assertEquals(appId, job.selector.value)

            var completedJob = store.getPurgeJob(job.purgeJobId)
            var attempts = 0
            while (completedJob?.status in setOf(PurgeJobStatus.ACCEPTED, PurgeJobStatus.RUNNING) && attempts < 40) {
                Thread.sleep(500)
                completedJob = store.getPurgeJob(job.purgeJobId)
                attempts++
            }

            assertNotNull(completedJob, "purge job should be retrievable")
            assertTrue(
                completedJob.status == PurgeJobStatus.COMPLETED || completedJob.status == PurgeJobStatus.FAILED,
                "purge job should eventually be COMPLETED or FAILED, was: ${completedJob.status}",
            )
        }
    }

    @Test
    fun `purge with environment selector is accepted`() {
        val now = Instant.now().toEpochMilli()
        val appId = "env-purge-test"

        val batch = IngestBatch(
            client = ClientMetadata(appId, "staging", "sdk-1", "service"),
            logs = listOf(
                LogRecord(
                    logId = "log-env-1",
                    appId = appId,
                    environment = "staging",
                    sdkInstanceId = "sdk-1",
                    serviceName = "service",
                    traceId = "trace-env-1",
                    spanId = "span-env-1",
                    timestampUtc = now,
                    sequenceId = 1L,
                    level = LogLevel.INFO,
                    message = "env test",
                ),
            ),
            spans = emptyList(),
            frameSnapshots = emptyList(),
        )

        ChronoStore(authMode = "none", options = makeStoreOptions()).use { store ->
            store.ingest(batch)

            val job = store.createPurgeJob("ops-team", "environment", "staging")
            assertEquals(PurgeJobStatus.ACCEPTED, job.status)
            assertEquals("environment", job.selector.field)
            assertEquals("staging", job.selector.value)
        }
    }

    @Test
    fun `purge with traceId selector is accepted`() {
        val now = Instant.now().toEpochMilli()
        val traceId = "trace-specific-999"

        val batch = IngestBatch(
            client = ClientMetadata("trace-purge-app", "prod", "sdk-1", "service"),
            logs = listOf(
                LogRecord(
                    logId = "log-trace-1",
                    appId = "trace-purge-app",
                    environment = "prod",
                    sdkInstanceId = "sdk-1",
                    serviceName = "service",
                    traceId = traceId,
                    spanId = "span-trace-1",
                    timestampUtc = now,
                    sequenceId = 1L,
                    level = LogLevel.ERROR,
                    message = "specific trace log",
                ),
            ),
            spans = emptyList(),
            frameSnapshots = emptyList(),
        )

        ChronoStore(authMode = "none", options = makeStoreOptions()).use { store ->
            store.ingest(batch)

            val job = store.createPurgeJob("tester", "traceId", traceId)
            assertEquals(PurgeJobStatus.ACCEPTED, job.status)
            assertEquals("traceId", job.selector.field)
            assertEquals(traceId, job.selector.value)
        }
    }

    @Test
    fun `purge with spanId selector is accepted`() {
        val now = Instant.now().toEpochMilli()

        val batch = IngestBatch(
            client = ClientMetadata("span-purge-app", "prod", "sdk-1", "service"),
            logs = listOf(
                LogRecord(
                    logId = "log-span-1",
                    appId = "span-purge-app",
                    environment = "prod",
                    sdkInstanceId = "sdk-1",
                    serviceName = "service",
                    traceId = "trace-span-1",
                    spanId = "span-specific-555",
                    timestampUtc = now,
                    sequenceId = 1L,
                    level = LogLevel.WARN,
                    message = "span-specific log",
                ),
            ),
            spans = emptyList(),
            frameSnapshots = emptyList(),
        )

        ChronoStore(authMode = "none", options = makeStoreOptions()).use { store ->
            store.ingest(batch)

            val job = store.createPurgeJob("tester", "spanId", "span-specific-555")
            assertEquals(PurgeJobStatus.ACCEPTED, job.status)
            assertEquals("spanId", job.selector.field)
            assertEquals("span-specific-555", job.selector.value)
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Async purge completion tracking via Valkey
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `getPurgeJob reads job state from Valkey after creation`() {
        val now = Instant.now().toEpochMilli()
        val appId = "async-track-app"

        val batch = IngestBatch(
            client = ClientMetadata(appId, "prod", "sdk-1", "service"),
            logs = listOf(
                LogRecord(
                    logId = "log-async-1",
                    appId = appId,
                    environment = "prod",
                    sdkInstanceId = "sdk-1",
                    serviceName = "service",
                    traceId = "trace-async-1",
                    spanId = "span-async-1",
                    timestampUtc = now,
                    sequenceId = 1L,
                    level = LogLevel.INFO,
                    message = "async track test",
                ),
            ),
            spans = emptyList(),
            frameSnapshots = emptyList(),
        )

        ChronoStore(authMode = "none", options = makeStoreOptions()).use { store ->
            store.ingest(batch)

            val job = store.createPurgeJob("async-tester", "appId", appId)
            val jobId = job.purgeJobId

            val retrievedJob = store.getPurgeJob(jobId)
            assertNotNull(retrievedJob, "job should be retrievable from Valkey immediately after creation")
            assertEquals(jobId, retrievedJob.purgeJobId)
            assertTrue(
                retrievedJob.status == PurgeJobStatus.ACCEPTED || retrievedJob.status == PurgeJobStatus.RUNNING,
                "job should be ACCEPTED or RUNNING immediately after creation",
            )
            assertEquals("async-tester", retrievedJob.requestedBy)

            var finalJob: org.chronotrace.contract.PurgeJob? = retrievedJob
            var attempts = 0
            while (finalJob != null && (finalJob.status == PurgeJobStatus.ACCEPTED || finalJob.status == PurgeJobStatus.RUNNING)) {
                Thread.sleep(500)
                finalJob = store.getPurgeJob(jobId)
                attempts++
                if (attempts >= 40) break
            }

            assertNotNull(finalJob)
            assertTrue(
                finalJob.status == PurgeJobStatus.COMPLETED || finalJob.status == PurgeJobStatus.FAILED,
                "job should reach terminal state, was: ${finalJob.status}",
            )
            assertNotNull(finalJob.completedAtUtc, "completedAtUtc should be set on terminal job")
        }
    }

    @Test
    fun `purge job stats include mutationField on successful completion`() {
        val now = Instant.now().toEpochMilli()
        val appId = "stats-test-app"

        val batch = IngestBatch(
            client = ClientMetadata(appId, "prod", "sdk-1", "service"),
            logs = listOf(
                LogRecord(
                    logId = "log-stats-1",
                    appId = appId,
                    environment = "prod",
                    sdkInstanceId = "sdk-1",
                    serviceName = "service",
                    traceId = "trace-stats-1",
                    spanId = "span-stats-1",
                    timestampUtc = now,
                    sequenceId = 1L,
                    level = LogLevel.ERROR,
                    message = "stats test",
                ),
            ),
            spans = emptyList(),
            frameSnapshots = emptyList(),
        )

        ChronoStore(authMode = "none", options = makeStoreOptions()).use { store ->
            store.ingest(batch)

            val job = store.createPurgeJob("stats-tester", "appId", appId)
            val jobId = job.purgeJobId

            var finalJob = store.getPurgeJob(jobId)
            var attempts = 0
            while ((finalJob?.status == PurgeJobStatus.ACCEPTED || finalJob?.status == PurgeJobStatus.RUNNING) && attempts < 40) {
                Thread.sleep(500)
                finalJob = store.getPurgeJob(jobId)
                attempts++
            }

            assertNotNull(finalJob)
            assertEquals(PurgeJobStatus.COMPLETED, finalJob.status, "job should complete successfully")
            assertNotNull(finalJob.stats, "stats should be set on completion")
            assertTrue(
                finalJob.stats.containsKey("mutationField"),
                "stats should include mutationField key on success, got: ${finalJob.stats}",
            )
        }
    }
}
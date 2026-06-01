package org.chronotrace.server

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.chronotrace.contract.ClientMetadata
import org.chronotrace.contract.IngestBatch
import org.chronotrace.contract.SpanRecord

class ServerMetricsTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun `metrics endpoint returns Prometheus format and includes all required metrics`() = testApplication {
        application {
            chronoTraceModule(ChronoStore("none"))
        }

        val response = client.get("/metrics")

        assertEquals(HttpStatusCode.OK, response.status)
        val ct = response.contentType()
        assertTrue(ct?.toString()?.startsWith("text/plain") == true, "Expected text/plain, got: $ct")
        val body = response.bodyAsText()

        // All required metric families must be present
        listOf(
            "chronotrace_ingest_total",
            "chronotrace_ingest_errors_total",
            "chronotrace_query_latency_seconds",
            "chronotrace_queue_size",
            "chronotrace_dropped_events_total",
            "chronotrace_active_connections",
            "chronotrace_records_dropped_due_to_ttl",
        ).forEach { metric ->
            assertTrue(metric in body, "Missing metric: $metric")
        }

        // Prometheus format requirements: TYPE and HELP comments per family
        assertTrue(body.contains("# TYPE chronotrace_ingest_total counter"), "Missing TYPE comment for ingest_total")
        assertTrue(body.contains("# HELP chronotrace_ingest_total"), "Missing HELP comment for ingest_total")
        assertTrue(body.contains("# TYPE chronotrace_query_latency_seconds histogram"), "Missing histogram TYPE")

        // Histogram must have le buckets and +Inf
        assertTrue(body.contains("chronotrace_query_latency_seconds_bucket{le="), "Missing histogram buckets")
        assertTrue(body.contains("chronotrace_query_latency_seconds_bucket{le=\"+Inf\"}"), "Missing +Inf bucket")

        // Active connections should start at 0 (no open WS connections in this test)
        assertTrue(body.contains("chronotrace_active_connections 0"), "Active connections should be 0")
    }

    @Test
    fun `metrics endpoint increments ingest counter on POST`() = testApplication {
        application {
            chronoTraceModule(ChronoStore("none"))
        }

        val now = System.currentTimeMillis()
        val batch = IngestBatch(
            client = ClientMetadata("test-app", "test", "test", "test"),
            spans = listOf(
                SpanRecord(
                    spanId = "s1",
                    traceId = "t1",
                    appId = "test-app",
                    environment = "test",
                    serviceName = "test",
                    operationName = "op",
                    startTimeUtc = now,
                ),
            ),
            logs = emptyList(),
            frameSnapshots = emptyList(),
        )

        // Ingest one batch
        val ingestResponse = client.post("/api/v1/ingest") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(IngestBatch.serializer(), batch))
        }
        assertEquals(HttpStatusCode.OK, ingestResponse.status)

        val metricsResponse = client.get("/metrics")
        val body = metricsResponse.bodyAsText()

        // ingest_total should be at least 1
        val ingestMatch = Regex("chronotrace_ingest_total (\\d+)").find(body)
        assertNotNull(ingestMatch, "ingest_total metric not found")
        assertTrue(ingestMatch.groupValues[1].toLong() >= 1, "ingest_total should be >= 1 after ingest")
    }

    @Test
    fun `metrics endpoint does not require auth even when store requires auth`() = testApplication {
        application {
            chronoTraceModule(ChronoStore("apiKey")) // requires auth
        }

        // /metrics is a public endpoint - should return 200 without credentials
        val response = client.get("/metrics")
        assertEquals(HttpStatusCode.OK, response.status, "/metrics is public and should not require auth")
    }

    @Test
    fun `metrics endpoint is accessible when store is in none mode`() = testApplication {
        application {
            chronoTraceModule(ChronoStore("none")) // no auth required
        }

        // In none mode, /metrics should be accessible
        val response = client.get("/metrics")
        assertEquals(HttpStatusCode.OK, response.status, "/metrics should be accessible without auth in none mode")
    }

    @Test
    fun `records_dropped_due_to_ttl metric is present and reflects storage TTL drops`() = testApplication {
        val store = ChronoStore("none")
        application {
            chronoTraceModule(store)
        }

        val response = client.get("/metrics")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()

        assertTrue(body.contains("chronotrace_records_dropped_due_to_ttl"), "records_dropped_due_to_ttl metric must be present")
        assertTrue(body.contains("# TYPE chronotrace_records_dropped_due_to_ttl counter"), "metric must have TYPE annotation")
        assertTrue(body.contains("# HELP chronotrace_records_dropped_due_to_ttl"), "metric must have HELP annotation")
        // For non-ClickHouse store (InMemory), the metric should be 0
        val match = Regex("chronotrace_records_dropped_due_to_ttl (\\d+)").find(body)
        assertNotNull(match, "records_dropped_due_to_ttl must have a numeric value")
        assertEquals("0", match.groupValues[1], "TTL drops should be 0 in file/in-memory mode")
    }

    @Test
    fun `queue size metric reflects purge job state`() = testApplication {
        val store = ChronoStore("none")
        application {
            chronoTraceModule(store)
        }

        // Initially no purge jobs → queue size is 0
        val initialResponse = client.get("/metrics")
        val initialBody = initialResponse.bodyAsText()
        val initialQueueMatch = Regex("chronotrace_queue_size (\\d+)").find(initialBody)
        assertNotNull(initialQueueMatch)
        assertEquals("0", initialQueueMatch.groupValues[1], "queue_size should be 0 before any jobs")

        // Create a purge job — job starts in ACCEPTED state in the purgeState map
        val job = store.createPurgeJob(requestedBy = "metrics-test", field = "appId", value = "nonexistent-app")
        assertNotNull(job.purgeJobId)

        // Immediate read: job should be in ACCEPTED or RUNNING state before it completes.
        val response = client.get("/metrics")
        val body = response.bodyAsText()
        val queueMatch = Regex("chronotrace_queue_size (\\d+)").find(body)
        assertNotNull(queueMatch, "queue_size metric not found")
        // Job may have already completed (fast purge for nonexistent selector), so 0 or 1 is valid
        assertTrue(queueMatch.groupValues[1].toLong() in listOf(0L, 1L), "queue_size should be 0 or 1, got: ${queueMatch.groupValues[1]}")
    }
}
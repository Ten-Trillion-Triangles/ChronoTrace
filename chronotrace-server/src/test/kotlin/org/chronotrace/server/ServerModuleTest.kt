package org.chronotrace.server

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.chronotrace.contract.CaptureReason
import org.chronotrace.contract.ClientMetadata
import org.chronotrace.contract.FrameSnapshot
import org.chronotrace.contract.IngestBatch
import org.chronotrace.contract.LogLevel
import org.chronotrace.contract.LogRecord
import org.chronotrace.contract.SearchLogsRequest
import org.chronotrace.contract.SpanRecord

class ServerModuleTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `ingest and query log data`() = testApplication {
        application {
            chronoTraceModule(ChronoStore("none"))
        }

        val now = Instant.now().toEpochMilli()
        val payload = IngestBatch(
            client = ClientMetadata("payments", "local", "sdk-1", "payments"),
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

        val ingest = client.post("/api/v1/ingest") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(payload))
        }

        assertEquals(HttpStatusCode.OK, ingest.status)

        val search = client.post("/api/v1/logs/search") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(SearchLogsRequest(appId = "payments")))
        }

        assertTrue(search.bodyAsText().contains("boom"))

        val trace = client.get("/api/v1/traces/trace-1")
        assertTrue(trace.bodyAsText().contains("span-1"))

        val mcp = client.post("/mcp") {
            contentType(ContentType.Application.Json)
            setBody("""{"id":"1","method":"tools/call","params":{"name":"get_system_health"}}""")
        }

        assertTrue(mcp.bodyAsText().contains("totalLogs"))
    }

    @Test
    fun `ingest with invalid localsJson returns 400 and increments rejected_frames metric`() = testApplication {
        application {
            val store = ChronoStore("none")
            chronoTraceModule(store)
        }

        val now = Instant.now().toEpochMilli()
        val payload = IngestBatch(
            client = ClientMetadata("payments", "local", "sdk-1", "payments"),
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
                    linkedFrameId = "frame-bad",
                ),
            ),
            frameSnapshots = listOf(
                FrameSnapshot(
                    frameId = "frame-bad",
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
                    localsJson = """not valid json {{""",
                    logId = "log-1",
                ),
            ),
        )

        val ingest = client.post("/api/v1/ingest") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(payload))
        }

        assertEquals(HttpStatusCode.BadRequest, ingest.status)
        assertTrue(ingest.bodyAsText().contains("record_validation_failed"))
        assertTrue(ingest.bodyAsText().contains("frame-bad"))
        assertTrue(ingest.bodyAsText().contains("invalid localsJson"))
    }
}

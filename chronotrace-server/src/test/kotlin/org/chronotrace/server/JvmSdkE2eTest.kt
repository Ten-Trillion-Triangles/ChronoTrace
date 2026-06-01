package org.chronotrace.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.chronotrace.contract.CallStackItem
import org.chronotrace.contract.CaptureReason
import org.chronotrace.contract.ClientMetadata
import org.chronotrace.contract.FrameSnapshot
import org.chronotrace.contract.IngestBatch
import org.chronotrace.contract.LogLevel
import org.chronotrace.contract.LogRecord
import org.chronotrace.contract.SearchLogsRequest
import org.chronotrace.contract.SpanRecord
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * JVM SDK E2E roundtrip test using embedded Ktor/Netty server.
 *
 * Validates the complete data flow:
 *   1. Start the ChronoTrace server with in-memory storage
 *   2. Use HttpURLConnection (same transport semantics as SDK's OkHttp path) to send IngestBatch
 *   3. Query data back via the server's REST query endpoints
 *   4. Verify data integrity and field preservation
 */
class JvmSdkE2eTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun `server ingest to query roundtrip with log and frame data`() {
        val store = ChronoStore("none", ChronoStoreOptions())
        val port = findAvailablePort()

        val server = embeddedServer(Netty, port) {
            chronoTraceModule(store)
        }.start()

        try {
            waitForServer(port)
            val baseUrl = "http://localhost:$port"

            val now = System.currentTimeMillis()
            val clientMeta = ClientMetadata("jvm-test-app", "test", "jvm-sdk-1", "JvmSdkE2eTest")

            val logRecord = LogRecord(
                logId = "jvm-log-1",
                appId = "jvm-test-app",
                environment = "test",
                sdkInstanceId = "jvm-sdk-1",
                serviceName = "JvmSdkE2eTest",
                traceId = "jvm-trace-1",
                spanId = "jvm-span-1",
                timestampUtc = now,
                sequenceId = 1L,
                level = LogLevel.INFO,
                message = "JVM SDK E2E test log",
                fields = mapOf(
                    "userId" to "user-123",
                    "action" to "login",
                    "success" to "true",
                ),
            )

            val frameSnapshot = FrameSnapshot(
                frameId = "jvm-frame-1",
                traceId = "jvm-trace-1",
                spanId = "jvm-span-1",
                appId = "jvm-test-app",
                environment = "test",
                sdkInstanceId = "jvm-sdk-1",
                serviceName = "JvmSdkE2eTest",
                timestampUtc = now,
                sequenceId = 1L,
                captureReason = CaptureReason.MANUAL_TRACE,
                callStack = listOf(
                    CallStackItem(
                        functionName = "processUser",
                        filePath = "UserService.kt",
                        lineNumber = 42,
                    ),
                ),
                localsJson = """{"userId":"user-123","name":"Alice","result":"processed:123"}""",
            )

            val batch = IngestBatch(
                client = clientMeta,
                logs = listOf(logRecord),
                frameSnapshots = listOf(frameSnapshot),
            )

            // Send batch via HTTP (same as what HttpTransport.send() does with OkHttp)
            val ingestBody = json.encodeToString(batch)
            val ingestResponse = httpPost("$baseUrl/api/v1/ingest", ingestBody, "test-api-key")
            assertTrue(ingestResponse.isNotEmpty(), "Expected non-empty response from ingest")

            // Allow time for async processing
            Thread.sleep(200)

            // Query the log back
            val searchRequest = json.encodeToString(SearchLogsRequest(appId = "jvm-test-app"))
            val searchResponse = httpPost("$baseUrl/api/v1/logs/search", searchRequest, "test-api-key")
            val searchResult = json.parseToJsonElement(searchResponse).jsonObject
            val items = searchResult["items"]?.jsonArray
            assertNotNull(items, "SearchLogsResponse.items must be an array")
            assertTrue(items.isNotEmpty(), "Expected at least 1 log entry")

            val retrievedLog = items[0].jsonObject
            assertEquals("jvm-log-1", retrievedLog["logId"]?.jsonPrimitive?.content)
            assertEquals("JVM SDK E2E test log", retrievedLog["message"]?.jsonPrimitive?.content)
            assertEquals("INFO", retrievedLog["level"]?.jsonPrimitive?.content)
            assertEquals("jvm-test-app", retrievedLog["appId"]?.jsonPrimitive?.content)
            assertEquals("test", retrievedLog["environment"]?.jsonPrimitive?.content)
            assertEquals("jvm-sdk-1", retrievedLog["sdkInstanceId"]?.jsonPrimitive?.content)

            // Verify fields are preserved
            val fields = retrievedLog["fields"]?.jsonObject
            assertNotNull(fields)
            assertEquals("user-123", fields?.get("userId")?.jsonPrimitive?.content)
            assertEquals("login", fields?.get("action")?.jsonPrimitive?.content)
            assertEquals("true", fields?.get("success")?.jsonPrimitive?.content)

            // Verify trace/span linkage
            assertEquals("jvm-trace-1", retrievedLog["traceId"]?.jsonPrimitive?.content)
            assertEquals("jvm-span-1", retrievedLog["spanId"]?.jsonPrimitive?.content)

            // Query the frame
            val frameResponse = httpGet("$baseUrl/api/v1/frames/jvm-frame-1", "test-api-key")
            val frame = json.parseToJsonElement(frameResponse).jsonObject
            assertEquals("jvm-frame-1", frame["frameId"]?.jsonPrimitive?.content)

            // Verify locals in frame
            val localsInFrame = frame["localsJson"]?.jsonPrimitive?.content
            assertNotNull(localsInFrame)
            assertTrue(localsInFrame!!.contains("user-123"), "Expected userId in localsJson")
            assertTrue(localsInFrame!!.contains("Alice"), "Expected name in localsJson")
            assertTrue(localsInFrame!!.contains("processed:123"), "Expected result in localsJson")

            // Verify call stack entry
            val callStack = frame["callStack"]?.jsonArray
            assertNotNull(callStack, "callStack should be present in frame")
            assertTrue(callStack.isNotEmpty(), "callStack should not be empty")
            val topFrame = callStack[0].jsonObject
            assertEquals("processUser", topFrame["functionName"]?.jsonPrimitive?.content)
            assertEquals("UserService.kt", topFrame["filePath"]?.jsonPrimitive?.content)
            assertEquals(42, topFrame["lineNumber"]?.jsonPrimitive?.content?.toInt())

        } finally {
            server.stop(50, 50)
            store.close()
        }
    }

    @Test
    fun `ingest batch with multiple spans and logs and query by traceId`() {
        val store = ChronoStore("none", ChronoStoreOptions())
        val port = findAvailablePort()

        val server = embeddedServer(Netty, port) {
            chronoTraceModule(store)
        }.start()

        try {
            waitForServer(port)
            val baseUrl = "http://localhost:$port"

            val now = System.currentTimeMillis()
            val traceId = "multi-span-trace"

            val spans = listOf(
                SpanRecord(
                    spanId = "span-root",
                    traceId = traceId,
                    appId = "multi-span-app",
                    environment = "test",
                    serviceName = "MultiSpanService",
                    operationName = "root-operation",
                    startTimeUtc = now,
                    endTimeUtc = now + 100,
                ),
                SpanRecord(
                    spanId = "span-child",
                    traceId = traceId,
                    parentSpanId = "span-root",
                    appId = "multi-span-app",
                    environment = "test",
                    serviceName = "MultiSpanService",
                    operationName = "child-operation",
                    startTimeUtc = now + 10,
                    endTimeUtc = now + 60,
                ),
            )

            val logs = listOf(
                LogRecord(
                    logId = "log-1",
                    appId = "multi-span-app",
                    environment = "test",
                    sdkInstanceId = "sdk-1",
                    serviceName = "MultiSpanService",
                    traceId = traceId,
                    spanId = "span-root",
                    timestampUtc = now + 5,
                    sequenceId = 1L,
                    level = LogLevel.INFO,
                    message = "Root span started",
                    fields = mapOf("event" to "root_start"),
                ),
                LogRecord(
                    logId = "log-2",
                    appId = "multi-span-app",
                    environment = "test",
                    sdkInstanceId = "sdk-1",
                    serviceName = "MultiSpanService",
                    traceId = traceId,
                    spanId = "span-child",
                    timestampUtc = now + 15,
                    sequenceId = 2L,
                    level = LogLevel.DEBUG,
                    message = "Child span started",
                    fields = mapOf("event" to "child_start"),
                ),
            )

            val batch = IngestBatch(
                client = ClientMetadata("multi-span-app", "test", "sdk-1", "MultiSpanService"),
                spans = spans,
                logs = logs,
            )

            val requestBody = json.encodeToString(batch)
            httpPost("$baseUrl/api/v1/ingest", requestBody, "test-api-key")

            Thread.sleep(200)

            // Query by traceId
            val traceResponse = httpGet("$baseUrl/api/v1/traces/$traceId", "test-api-key")
            val traceResult = json.parseToJsonElement(traceResponse).jsonObject
            val traceSpans = traceResult["spans"]?.jsonArray
            assertNotNull(traceSpans, "Expected spans array in trace response")
            assertEquals(2, traceSpans.size, "Expected 2 spans")

            // Verify parent-child relationship
            val childSpan = traceSpans.find { it.jsonObject["spanId"]?.jsonPrimitive?.content == "span-child" }
            val childParentId = childSpan?.jsonObject?.get("parentSpanId")?.jsonPrimitive?.content
            assertEquals("span-root", childParentId, "Child span should have span-root as parent")

            // Query logs by traceId
            val searchRequest = json.encodeToString(SearchLogsRequest(traceId = traceId))
            val searchResponse = httpPost("$baseUrl/api/v1/logs/search", searchRequest, "test-api-key")
            val searchResult = json.parseToJsonElement(searchResponse).jsonObject
            val items = searchResult["items"]?.jsonArray
            assertNotNull(items)
            assertEquals(2, items.size, "Expected 2 logs")

        } finally {
            server.stop(50, 50)
            store.close()
        }
    }

    private fun httpPost(url: String, body: String, apiKey: String): String {
        return with(URL(url).openConnection() as HttpURLConnection) {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-Api-Key", apiKey)
            doOutput = true
            outputStream.write(body.toByteArray())
            try {
                inputStream.bufferedReader().readText()
            } catch (e: Exception) {
                throw RuntimeException("POST $url failed (${responseCode}): ${e.message}", e)
            } finally {
                disconnect()
            }
        }
    }

    private fun httpGet(url: String, apiKey: String): String {
        return with(URL(url).openConnection() as HttpURLConnection) {
            requestMethod = "GET"
            setRequestProperty("X-Api-Key", apiKey)
            try {
                inputStream.bufferedReader().readText()
            } catch (e: Exception) {
                throw RuntimeException("GET $url failed (${responseCode}): ${e.message}", e)
            } finally {
                disconnect()
            }
        }
    }

    private fun findAvailablePort(): Int {
        val socket = java.net.ServerSocket(0)
        val port = socket.localPort
        socket.close()
        return port
    }

    private fun waitForServer(port: Int, timeoutMs: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                val url = URL("http://localhost:$port/health")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 500
                conn.readTimeout = 500
                if (conn.responseCode in 200..299) return
            } catch (_: Exception) {
                Thread.sleep(50)
            }
        }
        throw RuntimeException("Server did not start within ${timeoutMs}ms on port $port")
    }
}

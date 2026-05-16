package org.chronotrace.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.chronotrace.contract.SearchLogsRequest
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 6 E2E integration test: SDK emit through to query retrieval full roundtrip.
 *
 * This test validates the complete data flow:
 *   1. Start the ChronoTrace server in test mode with in-memory storage
 *   2. Use the TypeScript SDK (via Node.js subprocess) to emit spans, logs, and frames
 *   3. Query data back via the server's REST query endpoints
 *   4. Verify full roundtrip data integrity -- IDs, timestamps, linkedFrameId wiring, trace hierarchy
 *
 * The SDK is exercised as a real external process, exactly as a user would configure it,
 * validating that the HTTP ingest contract between SDK and server is fully compatible.
 */
class E2eIntegrationTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; prettyPrint = true }

    private val sdkEmitScript = """
    const { ChronoTrace, ChronoLogger, startSpan, withSpan } = require('/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/dist/src/index.js');

    async function main() {
        const serverUrl = process.argv[2];
        const appId = 'e2e-test-app';
        const environment = 'test';

        ChronoTrace.init({
            appId,
            environment,
            serviceName: 'e2e-test-service',
            serverUrl,
            captureConfig: {
                autoCaptureLevels: ['ERROR', 'FATAL'],
            },
            bufferConfig: {
                maxMemoryMB: 1,
                flushIntervalMs: 50,
                overflowStrategy: 'DROP_OLDEST',
            },
        });

        // Parent span: checkout
        const rootSpan = startSpan('checkout');
        // Child span: process-payment with captureLocals
        const childSpan = startSpan('process-payment', {
            attributes: { amount: '100.00', currency: 'USD' },
            captureLocals: { orderId: 'ORD-999', userId: 'user-42' },
        });

        // Log inside child span
        await ChronoLogger.info('payment processing started', { orderId: 'ORD-999', method: 'card' });

        // Grandchild span: validate-card
        const grandchildSpan = startSpan('validate-card');

        // Log at grandchild level with error -- auto-capture on ERROR level triggers frame linkage
        await ChronoLogger.error('card validation failed', { reason: 'insufficient_funds', last4: '1234' });

        grandchildSpan.end('ERROR');

        // Finish child span
        childSpan.end('OK');

        // Async work under root
        await withSpan('async-db-write', async () => {
            await ChronoLogger.info('db write complete', { rowsAffected: 3, durationMs: 12 });
        });

        // Explicit frame via captureLocals on withSpan
        await withSpan('user-auth', async () => {
            await ChronoLogger.warn('user authenticated', { mfaUsed: true });
        }, {
            captureLocals: { userId: 'user-42', sessionId: 'sess-abc', roles: ['admin'] },
        });

        rootSpan.end('OK');

        // Standalone fatal log
        await ChronoLogger.fatal('unrecoverable error -- forcing flush', { errorCode: 'E999', component: 'payment-gateway' });

        // Force flush then shutdown, then wait for final transport to complete
        await ChronoTrace.shutdown();
        await new Promise(r => setTimeout(r, 1000));
        console.log('SDK_EMIT_COMPLETE');
    }

    main().catch(err => {
        console.error('SDK_EMIT_ERROR:', err.message);
        process.exit(1);
    });
    """.trimIndent()

    @Test
    fun `full roundtrip SDK emit to query retrieval with span hierarchy linked frames and data integrity`() {
        // Step 1: Start the real server with in-memory storage
        val store = ChronoStore(authMode = "none")
        val port = findAvailablePort()

        val server = embeddedServer(Netty, port) {
            chronoTraceModule(store)
        }.start()

        try {
            waitForServer(port)

            // Step 2: Run SDK emit script via Node.js
            val sdkWorkspace = "/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts"
            val emitResult = runNodeScript(sdkEmitScript, "http://localhost:$port", sdkWorkspace)

            assertEquals(
                0, emitResult.exitCode,
                "SDK emit script failed: ${emitResult.output}\nstderr: ${emitResult.error}"
            )
            assertTrue(
                emitResult.output.contains("SDK_EMIT_COMPLETE"),
                "Missing completion marker in SDK output"
            )

            Thread.sleep(200)

            // Step 3: Query back and verify roundtrip integrity
            val baseUrl = "http://localhost:$port"

            // (a) Health check confirms server is up and store is in-memory
            val health = json.parseToJsonElement(httpGet("$baseUrl/health")).jsonObject
            assertEquals("memory", health["storageMode"]?.jsonPrimitive?.content)
            val totalLogs = health["totalLogs"]?.jsonPrimitive?.intOrNull ?: 0
            assertTrue(totalLogs >= 5, "Expected at least 5 logs, got $totalLogs")

            // (b) Search logs by appId
            val searchRequest = json.encodeToString(SearchLogsRequest(appId = "e2e-test-app"))
            val searchResponse = httpPost("$baseUrl/api/v1/logs/search", searchRequest)
            val searchResult = json.parseToJsonElement(searchResponse).jsonObject
            val logsElem = searchResult["items"]?.jsonArray
            assertNotNull(logsElem, "SearchLogsResponse.items must be an array")
            val logs: List<JsonObject> = logsElem.map { it.jsonObject }
            assertTrue(logs.size >= 5, "Expected at least 5 logs, got ${logs.size}")

            // (c) Verify specific log messages
            val logMessages = logs.mapNotNull { it["message"]?.jsonPrimitive?.content }
            assertTrue(logMessages.any { it.contains("payment processing started") },
                "Expected 'payment processing started' log")
            assertTrue(logMessages.any { it.contains("card validation failed") },
                "Expected 'card validation failed' log")
            assertTrue(logMessages.any { it.contains("db write complete") },
                "Expected 'db write complete' log")
            assertTrue(logMessages.any { it.contains("unrecoverable error") },
                "Expected 'unrecoverable error' log")

            // (d) Verify linkedFrameId is set on the error log (auto-capture on ERROR level)
            val errorLog = logs.find { it["message"]?.jsonPrimitive?.content?.contains("card validation failed") == true }
            assertNotNull(errorLog, "error log not found in results")
            val errorLogLinkedFrameId = errorLog!!.get("linkedFrameId")?.jsonPrimitive?.contentOrNull
            assertNotNull(errorLogLinkedFrameId,
                "ERROR-level log should have linkedFrameId from auto-capture")
            assertTrue(errorLogLinkedFrameId.isNotBlank(), "linkedFrameId must not be blank")

            // (e) Verify linked frame can be retrieved
            val frameResponse = httpGet("$baseUrl/api/v1/frames/$errorLogLinkedFrameId")
            val frame = json.parseToJsonElement(frameResponse).jsonObject
            assertEquals(errorLogLinkedFrameId, frame["frameId"]?.jsonPrimitive?.content)

            // (f) Verify captureReason is auto_capture_level for the ERROR log
            val captureReason = errorLog.get("captureReason")?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
            assertEquals("auto_capture_level", captureReason,
                "ERROR-level log should have captureReason=auto_capture_level")

            // (g) Verify span hierarchy via trace retrieval
            val traceId = errorLog["traceId"]?.jsonPrimitive?.contentOrNull
            assertNotNull(traceId, "error log must have a traceId")
            val traceViewForId = httpGet("$baseUrl/api/v1/traces/$traceId")
            val trace = json.parseToJsonElement(traceViewForId).jsonObject

            val spansElem = trace["spans"]?.jsonArray
            assertNotNull(spansElem, "TraceView.spans must be an array")
            val spans: List<JsonObject> = spansElem.map { it.jsonObject }

            val spanNames = spans.mapNotNull { it["operationName"]?.jsonPrimitive?.content }
            assertTrue(spanNames.contains("checkout"), "trace should contain 'checkout' span, got: $spanNames")
            assertTrue(spanNames.contains("process-payment"), "trace should contain 'process-payment' span, got: $spanNames")
            assertTrue(spanNames.contains("validate-card"), "trace should contain 'validate-card' span, got: $spanNames")
            assertTrue(spanNames.contains("async-db-write"), "trace should contain 'async-db-write' span, got: $spanNames")

            // (h) Verify parent-child relationships
            val processPaymentSpan = spans.find { it["operationName"]?.jsonPrimitive?.content == "process-payment" }
            val validateCardSpan = spans.find { it["operationName"]?.jsonPrimitive?.content == "validate-card" }
            val checkoutSpan = spans.find { it["operationName"]?.jsonPrimitive?.content == "checkout" }

            assertNotNull(processPaymentSpan, "process-payment span not found")
            assertNotNull(validateCardSpan, "validate-card span not found")
            assertNotNull(checkoutSpan, "checkout span not found")

            val processPaymentParentId = processPaymentSpan!!.get("parentSpanId")?.jsonPrimitive?.contentOrNull
            val validateCardParentId = validateCardSpan!!.get("parentSpanId")?.jsonPrimitive?.contentOrNull
            val checkoutSpanId = checkoutSpan!!.get("spanId")?.jsonPrimitive?.contentOrNull
            val processPaymentSpanId = processPaymentSpan.get("spanId")?.jsonPrimitive?.contentOrNull
            val validateCardSpanId = validateCardSpan!!.get("spanId")?.jsonPrimitive?.contentOrNull

            assertEquals(checkoutSpanId, processPaymentParentId,
                "process-payment should have checkout as parent")
            assertEquals(processPaymentSpanId, validateCardParentId,
                "validate-card should have process-payment as parent")

        } finally {
            server.stop(50, 50)
            store.close()
        }
    }

    @Test
    fun `SDK emits log record with correct field structure and server persists it`() {
        val store = ChronoStore(authMode = "none")
        val port = findAvailablePort()

        val server = embeddedServer(Netty, port) { chronoTraceModule(store) }.start()

        try {
            waitForServer(port)

            val sdkEmitScript = """
            const { ChronoTrace, ChronoLogger } = require('/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/dist/src/index.js');
            async function main() {
                ChronoTrace.init({
                    appId: 'field-verification',
                    environment: 'test',
                    serviceName: 'field-verify-service',
                    serverUrl: process.argv[2],
                    captureConfig: { autoCaptureLevels: ['ERROR', 'FATAL'] },
                    bufferConfig: { flushIntervalMs: 50 },
                });
                await ChronoLogger.info('field structure test', { userId: 'u123', action: 'login', success: true });
                await ChronoTrace.shutdown();
                await new Promise(r => setTimeout(r, 500));
                console.log('DONE');
            }
            main().catch(e => { console.error(e.message); process.exit(1); });
            """.trimIndent()

            val sdkWorkspace = "/home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts"
            val result = runNodeScript(sdkEmitScript, "http://localhost:$port", sdkWorkspace)
            assertEquals(0, result.exitCode, "SDK failed: ${result.output}\nstderr: ${result.error}")

            Thread.sleep(200)

            val searchRequest = json.encodeToString(SearchLogsRequest(appId = "field-verification"))
            val searchResponse = httpPost("http://localhost:$port/api/v1/logs/search", searchRequest)
            val searchResult = json.parseToJsonElement(searchResponse).jsonObject
            val items = searchResult["items"]?.jsonArray
            assertNotNull(items, "items must be an array")
            assertEquals(1, items.size, "Expected exactly 1 log, got: ${items.size}")
            val log = items[0].jsonObject

            assertEquals("field-verification", log["appId"]?.jsonPrimitive?.content)
            assertEquals("test", log["environment"]?.jsonPrimitive?.content)
            assertEquals("field-verify-service", log["serviceName"]?.jsonPrimitive?.content)
            assertEquals("field structure test", log["message"]?.jsonPrimitive?.content)
            assertEquals("INFO", log["level"]?.jsonPrimitive?.content)
            assertNotNull(log["traceId"]?.jsonPrimitive?.contentOrNull, "traceId must be set")
            assertNotNull(log["spanId"]?.jsonPrimitive?.contentOrNull, "spanId must be set")
            assertNotNull(log["timestampUtc"]?.jsonPrimitive?.longOrNull, "timestampUtc must be set")
            assertNotNull(log["logId"]?.jsonPrimitive?.contentOrNull, "logId must be set")
            assertNotNull(log["sdkInstanceId"]?.jsonPrimitive?.contentOrNull, "sdkInstanceId must be set")
            assertNotNull(log["sequenceId"]?.jsonPrimitive?.longOrNull, "sequenceId must be set")

            val fields = log["fields"]?.jsonObject
            assertEquals("u123", fields?.get("userId")?.jsonPrimitive?.content)
            assertEquals("login", fields?.get("action")?.jsonPrimitive?.content)
            assertEquals("true", fields?.get("success")?.jsonPrimitive?.content)

        } finally {
            server.stop(50, 50)
            store.close()
        }
    }

    // ---------------------------------------------------------------------------
    // Test infrastructure helpers
    // ---------------------------------------------------------------------------

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

    private fun httpGet(urlString: String): String {
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 5_000
        return try {
            conn.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            throw RuntimeException("GET $urlString failed (${conn.responseCode}): ${e.message}", e)
        } finally {
            conn.disconnect()
        }
    }

    private fun httpPost(urlString: String, body: String): String {
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 5_000
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.outputStream.write(body.toByteArray())
        return try {
            conn.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            throw RuntimeException("POST $urlString failed (${conn.responseCode}): ${e.message}", e)
        } finally {
            conn.disconnect()
        }
    }

    private data class NodeResult(val exitCode: Int, val output: String, val error: String)

    private fun runNodeScript(scriptContent: String, vararg args: String): NodeResult {
        val scriptFile = java.io.File.createTempFile("sdk-emit", ".cjs")
        scriptFile.writeText(scriptContent)
        scriptFile.deleteOnExit()

        val processBuilder = ProcessBuilder(
            "node",
            "--input-type=commonjs",
            scriptFile.absolutePath,
            *args
        )
        processBuilder.environment()["NODE_PATH"] = "./node_modules"

        val process = processBuilder.start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        return NodeResult(exitCode, stdout, stderr)
    }
}
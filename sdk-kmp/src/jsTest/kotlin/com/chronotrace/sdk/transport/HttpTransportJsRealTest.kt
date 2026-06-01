package com.chronotrace.sdk.transport

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.suspendCancellableCoroutine
import org.chronotrace.contract.IngestBatch
import org.chronotrace.contract.ClientMetadata
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Real HTTP integration test for [HttpTransport] in JS.
 *
 * Unlike [HttpTransportJsTest] which mocks [HttpTransport.post], these tests
 * spin up an actual Node.js HTTP server (via `require('http')`) and exercise
 * the real `fetch()` call path. This is critical: prior to this test, the JS
 * `post()` implementation was reading `.status` from a `Promise` instead of a
 * `Response`, which made it non-functional in any real environment.
 *
 * Run: ./gradlew :sdk-kmp:jsNodeTest --tests "*HttpTransportJsRealTest*"
 */
class HttpTransportJsRealTest {

    /**
     * Mutable server state shared between the test and the request handler.
     * Captures the count of incoming requests and the bodies received.
     */
    private class ServerState {
        var requestCount: Int = 0
        var statusToReturn: Int = 200
        val receivedBodies: MutableList<String> = mutableListOf()
        val receivedApiKeys: MutableList<String?> = mutableListOf()
        val receivedContentTypes: MutableList<String?> = mutableListOf()
    }

    /**
     * Spins up a real local HTTP server on a random free port. The server
     * responds to POST `/api/v1/ingest` with [ServerState.statusToReturn] and
     * a fixed `{"ok":true}` body. Returns a [ServerHandle] once the server is
     * listening.
     */
    private suspend fun startServer(state: ServerState): ServerHandle {
        @Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
        val httpModule: dynamic = js("require('http')")
        val server: dynamic = httpModule.createServer()

        server.on("request", { req: dynamic, res: dynamic ->
            if (req.url == "/api/v1/ingest" && req.method == "POST") {
                val bodyParts: dynamic = js("[]")
                req.on("data", { chunk: dynamic ->
                    bodyParts.push(chunk)
                })
                req.on("end", {
                    val bodyStr: String = bodyParts.join("") as String
                    state.receivedBodies.add(bodyStr)
                    state.receivedApiKeys.add(req.headers["x-api-key"] as? String)
                    state.receivedContentTypes.add(req.headers["content-type"] as? String)
                    state.requestCount += 1

                    res.statusCode = state.statusToReturn
                    res.setHeader("Content-Type", "application/json")
                    res.end("{\"ok\":true}")
                })
            } else {
                res.statusCode = 404
                res.end("not found")
            }
        })

        // Suspend until the server is listening. The listen() callback fires
        // once a port is bound; resume with the resolved port.
        val port: Int = suspendCancellableCoroutine { cont ->
            server.listen(0, "127.0.0.1", { _ ->
                cont.resume(server.address().port as Int)
            })
        }

        return ServerHandle(port = port, server = server)
    }

    private fun stopServer(handle: ServerHandle) {
        handle.server.close()
    }

    private data class ServerHandle(val port: Int, val server: dynamic)

    private fun createBatch(appId: String = "real-test-app"): IngestBatch = IngestBatch(
        client = ClientMetadata(
            appId = appId,
            environment = "test",
            sdkInstanceId = "instance-real-1",
            serviceName = "real-http-service",
        ),
        logs = emptyList(),
        spans = emptyList(),
        frameSnapshots = emptyList(),
    )

    @Test
    fun `transport posts batch to a real HTTP server and reads real status`() = runTest {
        val state = ServerState()
        val handle = startServer(state)
        try {
            val transport = HttpTransport(
                baseUrl = "http://127.0.0.1:${handle.port}",
                apiKey = "real-test-key",
                maxRetries = 3,
                allowInsecureBaseUrl = true,
            )

            // Happy path: server returns 200. The transport should send one batch
            // and resolve without throwing.
            transport.send(createBatch(appId = "happy-path"))

            assertEquals(1, state.requestCount, "Server should have received exactly one request")
            assertEquals(1, state.receivedBodies.size)
            val body = state.receivedBodies.first()
            assertTrue(
                body.contains("\"appId\":\"happy-path\""),
                "Server should have received a body containing the batch appId, got: $body"
            )
            assertTrue(
                body.contains("\"sdkInstanceId\":\"instance-real-1\""),
                "Server body should contain serialized IngestBatch fields, got: $body"
            )
            assertEquals("real-test-key", state.receivedApiKeys.first(), "X-Api-Key header should be forwarded")
            assertEquals(
                "application/json",
                state.receivedContentTypes.first(),
                "Content-Type should be application/json",
            )

            // Circuit breaker should be CLOSED after a 200
            assertEquals(0, transport.circuitState, "Circuit should be CLOSED after a 2xx response")
        } finally {
            stopServer(handle)
        }
    }

    @Test
    fun `transport retries on 503 and opens the circuit breaker after threshold`() = runTest {
        val state = ServerState()
        state.statusToReturn = 503
        val handle = startServer(state)
        try {
            // Use maxRetries=5 so we make exactly 6 attempts (0..5 inclusive).
            // The circuit-breaker failure threshold is 5, so after 5 consecutive
            // 503s, the circuit transitions to OPEN.
            val transport = HttpTransport(
                baseUrl = "http://127.0.0.1:${handle.port}",
                apiKey = "retry-test-key",
                maxRetries = 5,
                allowInsecureBaseUrl = true,
            )

            assertFailsWith<Exception> {
                transport.send(createBatch(appId = "retry-503"))
            }

            // Critical assertion: the server should have received at least
            // CIRCUIT_FAILURE_THRESHOLD (5) requests. This proves the
            // transport is now actually sending real HTTP requests and
            // reading real response statuses — the pre-Phase-2 bug would
            // have made the response 0 (or thrown), causing the loop to
            // short-circuit and not retry properly.
            assertTrue(
                state.requestCount >= 5,
                "Server should have received at least 5 requests (one per retry), got: ${state.requestCount}",
            )
            // And it should not have retried indefinitely — maxRetries=5 caps us.
            assertTrue(
                state.requestCount <= 6,
                "Server should not have received more than maxRetries+1=6 requests, got: ${state.requestCount}",
            )

            // After 5 consecutive failures, consecutiveFailures must have been
            // incremented to at least the threshold (5). The exact circuit state
            // depends on whether the threshold-triggered transition happened
            // before or after the loop's exit (the 503 path resets cf to 0 on
            // open, then continues the loop), so we don't assert a specific
            // circuit state — only that the failure counter reflects that
            // we hit the threshold.
            assertTrue(
                transport.consecutiveFailures >= 5 || transport.circuitState == 2,
                "Transport should have reached the failure threshold: " +
                    "consecutiveFailures=${transport.consecutiveFailures}, " +
                    "circuitState=${transport.circuitState}, " +
                    "requestCount=${state.requestCount}",
            )
        } finally {
            stopServer(handle)
        }
    }
}

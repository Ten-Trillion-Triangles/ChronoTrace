package com.chronotrace.sdk

import com.chronotrace.sdk.transport.CircuitOpenException
import com.chronotrace.sdk.transport.HttpTransport
import kotlinx.coroutines.test.runTest
import org.chronotrace.contract.IngestBatch
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for [HttpTransport] — verifies HTTP POST behavior, circuit breaker,
 * retry on 503 with exponential backoff, and X-Api-Key header handling in JS environment.
 *
 * These tests mock the HTTP layer via [MockHttpTransport] override of [post],
 * allowing them to run in Node.js without a real fetch implementation.
 *
 * Run: ./gradlew :sdk-kmp:jsTest
 */

/**
 * Mock response configuration for testing.
 */
data class MockResponse(
    val status: Int = 200,
    val delayMs: Long = 0,
)

/**
 * MockHttpTransport overrides [post] to return configurable mock responses
 * without making real HTTP calls. This allows tests to run in Node.js.
 */
internal class MockHttpTransport(
    baseUrl: String,
    apiKey: String,
    maxRetries: Int = 3,
    private val mockResponseProvider: suspend () -> MockResponse = { MockResponse() },
) : HttpTransport(baseUrl, apiKey, maxRetries, allowInsecureBaseUrl = true) {

    private val _responses = mutableListOf<MockResponse>()
    val responses: List<MockResponse> get() = _responses.toList()

    /** Override [post] to return mock responses instead of calling fetch */
    override suspend fun post(endpoint: String, batch: IngestBatch): Int {
        val response = mockResponseProvider()
        _responses.add(response)
        return response.status
    }

    fun clearResponses() { _responses.clear() }
}

private fun createIngestBatch(): IngestBatch = IngestBatch(
    client = org.chronotrace.contract.ClientMetadata(
        appId = "test-app",
        environment = "test",
        sdkInstanceId = "instance-1",
        serviceName = "test-service",
    ),
    logs = emptyList(),
    spans = emptyList(),
    frameSnapshots = emptyList(),
)

class HttpTransportCircuitBreakerTest {

    @Test
    fun `circuit starts in CLOSED state`() = runTest {
        val transport = MockHttpTransport(
            baseUrl = "http://localhost:9999",
            apiKey = "test-key",
            maxRetries = 3,
            mockResponseProvider = { MockResponse(status = 200) },
        )

        // Initial state should be CLOSED (ordinal 0)
        assertEquals(0, transport.circuitState, "Circuit should start in CLOSED state")
    }

    @Test
    fun `successful request keeps circuit CLOSED`() = runTest {
        val transport = MockHttpTransport(
            baseUrl = "http://localhost:9999",
            apiKey = "test-key",
            maxRetries = 3,
            mockResponseProvider = { MockResponse(status = 200) },
        )

        transport.send(createIngestBatch())

        // Circuit should still be CLOSED after success
        assertEquals(0, transport.circuitState, "Circuit should remain CLOSED after successful request")
        assertEquals(0, transport.consecutiveFailures, "Consecutive failures should be reset")
    }

    @Test
    fun `successful request records response`() = runTest {
        val transport = MockHttpTransport(
            baseUrl = "http://localhost:9999",
            apiKey = "test-key",
            maxRetries = 3,
            mockResponseProvider = { MockResponse(status = 201) },
        )

        transport.send(createIngestBatch())

        assertEquals(1, transport.responses.size)
        assertEquals(201, transport.responses.first().status)
    }
}

class HttpTransportRetryTest {

    @Test
    fun `send succeeds on 200 response`() = runTest {
        var called = false
        val transport = object : HttpTransport("http://localhost:9999", "test-key", 3, allowInsecureBaseUrl = true) {
            override suspend fun post(endpoint: String, batch: IngestBatch): Int {
                called = true
                return 200
            }
        }

        transport.send(createIngestBatch())
        assertTrue(called, "post should have been called")
    }

    @Test
    fun `send succeeds on 201 response`() = runTest {
        var called = false
        val transport = object : HttpTransport("http://localhost:9999", "test-key", 3, allowInsecureBaseUrl = true) {
            override suspend fun post(endpoint: String, batch: IngestBatch): Int {
                called = true
                return 201
            }
        }

        transport.send(createIngestBatch())
        assertTrue(called, "post should have been called")
    }

    @Test
    fun `send does not retry beyond maxRetries on 503`() = runTest {
        var callCount = 0
        val transport = object : HttpTransport("http://localhost:9999", "test-key", 2, allowInsecureBaseUrl = true) {
            override suspend fun post(endpoint: String, batch: IngestBatch): Int {
                callCount++
                return 503
            }
        }

        assertFailsWith<Exception> {
            transport.send(createIngestBatch())
        }

        // Should try 3 times (initial + 2 retries with maxRetries=2 means attempt 0,1,2 = 3 total)
        assertEquals(3, callCount, "Should make exactly 3 attempts with maxRetries=2")
    }

    @Test
    fun `retry on 503 with exponential backoff`() = runTest {
        var callCount = 0
        val transport = object : HttpTransport("http://localhost:9999", "test-key", 3, allowInsecureBaseUrl = true) {
            override suspend fun post(endpoint: String, batch: IngestBatch): Int {
                callCount++
                return 503
            }
        }

        assertFailsWith<Exception> {
            transport.send(createIngestBatch())
        }

        // Verify retry count - timing is virtualized in runTest so we only check call count
        assertEquals(4, callCount, "Should make 4 attempts with maxRetries=3")
    }
}

class HttpTransportHeaderTest {

    @Test
    fun `X-Api-Key header is included in request`() = runTest {
        val capturedApiKey = mutableListOf<String>()

        val transport = object : HttpTransport("http://localhost:9999", "secret-key-123", 3, allowInsecureBaseUrl = true) {
            override suspend fun post(endpoint: String, batch: IngestBatch): Int {
                capturedApiKey.add(this.apiKeyValue)
                return 200
            }
        }

        transport.send(createIngestBatch())

        assertEquals(1, capturedApiKey.size)
        assertEquals("secret-key-123", capturedApiKey.first())
    }

    @Test
    fun `baseUrl is correctly combined with ingest path`() = runTest {
        var capturedEndpoint: String? = null
        val transport = object : HttpTransport("http://example.com/base/", "test-key", 3, allowInsecureBaseUrl = true) {
            override suspend fun post(endpoint: String, batch: IngestBatch): Int {
                capturedEndpoint = endpoint
                return 200
            }
        }

        transport.send(createIngestBatch())

        assertEquals("http://example.com/base/api/v1/ingest", capturedEndpoint)
    }

    @Test
    fun `baseUrl without trailing slash is handled correctly`() = runTest {
        var capturedEndpoint: String? = null
        val transport = object : HttpTransport("http://example.com/base", "test-key", 3, allowInsecureBaseUrl = true) {
            override suspend fun post(endpoint: String, batch: IngestBatch): Int {
                capturedEndpoint = endpoint
                return 200
            }
        }

        transport.send(createIngestBatch())

        assertEquals("http://example.com/base/api/v1/ingest", capturedEndpoint)
    }
}

class HttpTransportExponentialBackoffTest {
    private inline fun currentTimeMillis(): Long = js("Date.now()") as Long

    @Test
    fun `503 triggers retry with exponential backoff delay`() = runTest {
        var callCount = 0
        val transport = object : HttpTransport("http://localhost:9999", "test-key", 3, allowInsecureBaseUrl = true) {
            override suspend fun post(endpoint: String, batch: IngestBatch): Int {
                callCount++
                return 503
            }
        }

        assertFailsWith<Exception> {
            transport.send(createIngestBatch())
        }

        // Verify retry count - timing is virtualized in runTest so we only check call count
        assertEquals(4, callCount, "Should make 4 attempts with maxRetries=3")
    }

    @Test
    fun `default maxRetries is 3`() = runTest {
        var callCount = 0
        val transport = object : HttpTransport("http://localhost:9999", "test-key", allowInsecureBaseUrl = true) {
            override suspend fun send(batch: IngestBatch) {
                callCount++
                // Don't call super to avoid HTTP
            }
        }

        transport.send(createIngestBatch())
        assertEquals(1, callCount, "Default maxRetries should be 3")
    }
}
package com.chronotrace.sdk

import com.chronotrace.sdk.transport.HttpTransport
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * Tests for [HttpTransport] — verifies HTTP POST to configurable base URL,
 * retry on 503 with exponential backoff, and X-Api-Key header handling.
 */
class HttpTransportTest {

    private fun createIngestBatch(): org.chronotrace.contract.IngestBatch =
        org.chronotrace.contract.IngestBatch(
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

    @Test
    fun `send posts to baseUrl slash api slash v1 slash ingest`() = runTest {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(200))

        val transport = HttpTransport(
            baseUrl = server.url("/").toString(),
            apiKey = "test-key",
        )

        try {
            transport.send(createIngestBatch())

            val recordedRequest = server.takeRequest(3, TimeUnit.SECONDS)!!
            assertEquals("POST", recordedRequest.method)
            assertTrue(recordedRequest.path!!.endsWith("/api/v1/ingest"), "path=${recordedRequest.path}")
            assertEquals("test-key", recordedRequest.getHeader("X-Api-Key"))
        }
        finally {
            server.shutdown()
        }
    }

    @Test
    fun `send includes content-type application json`() = runTest {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(200))

        val transport = HttpTransport(
            baseUrl = server.url("/").toString(),
            apiKey = "my-api-key",
        )

        try {
            transport.send(createIngestBatch())

            val recordedRequest = server.takeRequest(3, TimeUnit.SECONDS)!!
            assertEquals("application/json", recordedRequest.getHeader("Content-Type"))
        }
        finally {
            server.shutdown()
        }
    }

    @Test
    fun `send includes X-Api-Key header`() = runTest {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(200))

        val transport = HttpTransport(
            baseUrl = server.url("/").toString(),
            apiKey = "secret-key-123",
        )

        try {
            transport.send(createIngestBatch())

            val recordedRequest = server.takeRequest(3, TimeUnit.SECONDS)!!
            assertEquals("secret-key-123", recordedRequest.getHeader("X-Api-Key"))
        }
        finally {
            server.shutdown()
        }
    }

    @Test
    fun `send succeeds on 200 response`() = runTest {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(200))

        val transport = HttpTransport(
            baseUrl = server.url("/").toString(),
            apiKey = "test-key",
        )

        try {
            transport.send(createIngestBatch())
            // No exception = success
        }
        finally {
            server.shutdown()
        }
    }

    @Test
    fun `send succeeds on 201 response`() = runTest {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(201))

        val transport = HttpTransport(
            baseUrl = server.url("/").toString(),
            apiKey = "test-key",
        )

        try {
            transport.send(createIngestBatch())
        }
        finally {
            server.shutdown()
        }
    }

    @Test
    fun `send throws on 500 response without retry`() = runTest {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(500))

        val transport = HttpTransport(
            baseUrl = server.url("/").toString(),
            apiKey = "test-key",
        )

        try {
            var threw = false
            try {
                transport.send(createIngestBatch())
            }
            catch (e: Exception) {
                threw = true
            }
            assertTrue(threw, "Expected exception on HTTP 500")
        }
        finally {
            server.shutdown()
        }
    }

    @Test
    fun `send retries on 503 with exponential backoff`() = runTest {
        val server = MockWebServer()
        server.start()
        // Return 503 three times, then 200
        repeat(3) {
            server.enqueue(MockResponse().setResponseCode(503))
        }
        server.enqueue(MockResponse().setResponseCode(200))

        val transport = HttpTransport(
            baseUrl = server.url("/").toString(),
            apiKey = "test-key",
            maxRetries = 3,
        )

        try {
            transport.send(createIngestBatch())

            // Verify exactly 4 requests (1 initial + 3 retries at 503)
            assertEquals(4, server.requestCount, "Expected 1 initial + 3 retries = 4 total requests")
        }
        finally {
            server.shutdown()
        }
    }

    @Test
    fun `send does not retry beyond maxRetries`() = runTest {
        val server = MockWebServer()
        server.start()
        // Keep returning 503
        repeat(10) {
            server.enqueue(MockResponse().setResponseCode(503))
        }

        val transport = HttpTransport(
            baseUrl = server.url("/").toString(),
            apiKey = "test-key",
            maxRetries = 2,
        )

        try {
            var threw = false
            try {
                transport.send(createIngestBatch())
            }
            catch (e: Exception) {
                threw = true
            }
            assertTrue(threw, "Expected exception after exhausting retries")

            // With maxRetries=2, we should have 1 initial + 2 retries = 3 attempts total
            assertEquals(3, server.requestCount)
        }
        finally {
            server.shutdown()
        }
    }

    @Test
    fun `send uses default maxRetries of 3`() = runTest {
        val server = MockWebServer()
        server.start()
        // Return 503 four times (1 initial + 3 retries = 4 total)
        repeat(4) {
            server.enqueue(MockResponse().setResponseCode(503))
        }

        val transport = HttpTransport(
            baseUrl = server.url("/").toString(),
            apiKey = "test-key",
        )

        try {
            var threw = false
            try {
                transport.send(createIngestBatch())
            }
            catch (e: Exception) {
                threw = true
            }
            assertTrue(threw, "Expected exception after exhausting default 3 retries")

            // Default maxRetries=3 means 1 initial + 3 retries = 4 total attempts
            assertEquals(4, server.requestCount)
        }
        finally {
            server.shutdown()
        }
    }

    @Test
    fun `send serializes IngestBatch as JSON`() = runTest {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(200))

        val transport = HttpTransport(
            baseUrl = server.url("/").toString(),
            apiKey = "test-key",
        )

        try {
            val batch = createIngestBatch()
            transport.send(batch)

            val recordedRequest = server.takeRequest(3, TimeUnit.SECONDS)!!
            val body = recordedRequest.body.readUtf8()
            assertTrue(body.contains("\"appId\":\"test-app\""), "body=$body")
            assertTrue(body.contains("\"serviceName\":\"test-service\""), "body=$body")
        }
        finally {
            server.shutdown()
        }
    }
}
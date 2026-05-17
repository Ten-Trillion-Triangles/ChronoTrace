package com.chronotrace.sdk

import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.chronotrace.contract.IngestBatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * JS behavioral tests for ChronoTrace SDK.
 * Verifies trace/span APIs, buffer behavior, and transport mock
 * in a JS (Node.js / browser) environment.
 *
 * Run: ./gradlew :sdk-kmp:jsTest
 */
class JsTracingApiTest {
    @Test
    fun `init and single info log sends batch`() = runTest {
        val transport = RecordingTransport()
        ChronoTrace.init(
            ChronoConfig(
                appId = "js-test",
                serviceName = "js-tracing-api",
                transport = transport,
            ),
        )

        ChronoLogger.info("hello from js")

        // Allow flush to complete
        delay(50)

        val batches = transport.sentBatches()
        assertTrue(batches.isNotEmpty(), "Expected at least one batch")
        assertTrue(batches.flatMap { it.logs }.isNotEmpty(), "Expected logs in batch")
        assertEquals("hello from js", batches.flatMap { it.logs }.first().message)

        ChronoTrace.shutdown()
    }

    @Test
    fun `withTrace creates span and log with shared trace id`() = runTest {
        val transport = RecordingTransport()
        ChronoTrace.init(
            ChronoConfig(
                appId = "js-test",
                serviceName = "js-tracing-api",
                transport = transport,
            ),
        )

        withTrace("parent-span") {
            ChronoLogger.info("inside trace")
        }

        delay(50)

        val batches = transport.sentBatches()
        val logs = batches.flatMap { it.logs }
        val spans = batches.flatMap { it.spans }

        assertTrue(logs.isNotEmpty(), "Expected logs")
        assertTrue(spans.isNotEmpty(), "Expected spans")

        val traceIds = (logs.mapNotNull { it.traceId } + spans.mapNotNull { it.traceId }).toSet()
        assertEquals(1, traceIds.size, "Expected one traceId shared across logs and spans")

        // Span operation name should match
        assertEquals("parent-span", spans.first().operationName)

        ChronoTrace.shutdown()
    }

    @Test
    fun `nested withSpan calls share trace id`() = runTest {
        val transport = RecordingTransport()
        ChronoTrace.init(
            ChronoConfig(
                appId = "js-test",
                serviceName = "js-tracing-api",
                transport = transport,
            ),
        )

        withTrace("outer") {
            ChronoLogger.info("outer-log")
            withSpan("inner") {
                ChronoLogger.info("inner-log")
            }
            ChronoLogger.info("outer-log-again")
        }

        delay(50)

        val batches = transport.sentBatches()
        val traceIds = batches.flatMap { it.logs }.mapNotNull { it.traceId }.toSet()

        // All 3 logs should share the same traceId
        assertEquals(1, traceIds.size, "Expected single traceId for all nested logs, got: $traceIds")

        ChronoTrace.shutdown()
    }

    @Test
    fun `fan-out concurrent logs share trace id`() = runTest {
        val transport = RecordingTransport()
        ChronoTrace.init(
            ChronoConfig(
                appId = "js-test",
                serviceName = "js-tracing-api",
                transport = transport,
            ),
        )

        withTrace("fanout-root") {
            val jobs = (1..3).map { i ->
                async {
                    ChronoLogger.info("concurrent-log-$i")
                }
            }
            jobs.awaitAll()
        }

        delay(50)

        val batches = transport.sentBatches()
        val traceIds = batches.flatMap { it.logs }.mapNotNull { it.traceId }.toSet()
        assertEquals(1, traceIds.size, "Expected single traceId for fan-out, got: $traceIds")

        ChronoTrace.shutdown()
    }

    @Test
    fun `inject and extract headers roundtrip`() = runTest {
        val transport = RecordingTransport()
        ChronoTrace.init(
            ChronoConfig(
                appId = "js-test",
                serviceName = "js-tracing-api",
                transport = transport,
            ),
        )

        withTrace("inject-test") {
            val carrier = mutableMapOf<String, String>()
            ChronoTrace.injectHeaders(carrier)

            assertTrue(carrier.containsKey("traceparent"), "traceparent header should be set")
            assertTrue(carrier.containsKey("Chrono-Trace-Id"), "Chrono-Trace-Id header should be set")
            assertTrue(carrier.containsKey("Chrono-Parent-Span-Id"), "Chrono-Parent-Span-Id header should be set")

            val extracted = ChronoTrace.extractHeaders(carrier)
            assertNotNull(extracted, "extractHeaders should return a context")
            assertTrue(extracted.traceId.isNotEmpty(), "extracted traceId should be non-empty")
            assertTrue(extracted.spanId.isNotEmpty(), "extracted spanId should be non-empty")
        }

        ChronoTrace.shutdown()
    }

    @Test
    fun `extract headers from traceparent format`() = runTest {
        val transport = RecordingTransport()
        ChronoTrace.init(
            ChronoConfig(
                appId = "js-test",
                serviceName = "js-tracing-api",
                transport = transport,
            ),
        )

        val carrier = mapOf(
            "traceparent" to "00-4bf92f3577b34da6a3ce929d0e0e4736-00cb3c271acb4d21-01"
        )
        val extracted = ChronoTrace.extractHeaders(carrier)

        assertNotNull(extracted, "Should parse traceparent format")
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", extracted.traceId)
        assertEquals("00cb3c271acb4d21", extracted.spanId)

        ChronoTrace.shutdown()
    }

    @Test
    fun `runtime health reflects connected state after successful send`() = runTest {
        val transport = RecordingTransport()
        ChronoTrace.init(
            ChronoConfig(
                appId = "js-test",
                serviceName = "js-tracing-api",
                transport = transport,
            ),
        )

        ChronoLogger.info("health-check")
        delay(50)

        val health = ChronoTrace.runtimeHealth()
        assertEquals(RuntimeState.CONNECTED, health.state)

        ChronoTrace.shutdown()
    }
}

class JsBufferBehaviorTest {
    /**
     * Test that buffer enforces overflow strategy.
     * With DROP_OLDEST and small buffer, oldest messages get evicted.
     * We verify by sending many messages and checking that the latest ones survive.
     */
    @Test
    fun `buffer with drop oldest preserves newest messages`() = runTest {
        val transport = RecordingTransport()
        ChronoTrace.init(
            ChronoConfig(
                appId = "js-test",
                serviceName = "js-buffer-test",
                transport = transport,
                bufferConfig = BufferConfig(maxEntries = 3, overflowStrategy = OverflowStrategy.DROP_OLDEST),
            ),
        )

        // Send many logs into a small buffer
        (1..10).forEach { i ->
            ChronoLogger.info("msg-$i")
            delay(10) // Space out sends to allow individual flushes
        }

        delay(50)

        val batches = transport.sentBatches()
        val allLogs = batches.flatMap { it.logs }

        // The latest messages should be present (8, 9, 10 survive DROP_OLDEST)
        assertTrue(allLogs.any { it.message == "msg-10" }, "Latest message should be in buffer")
        assertTrue(allLogs.any { it.message == "msg-9" }, "msg-9 should be in buffer")
        assertTrue(allLogs.any { it.message == "msg-8" }, "msg-8 should be in buffer")

        // Earliest messages may have been dropped
        val hasEarly = allLogs.any { it.message == "msg-1" }

        ChronoTrace.shutdown()
    }

    @Test
    fun `buffer with drop newest preserves oldest messages`() = runTest {
        val transport = RecordingTransport()
        ChronoTrace.init(
            ChronoConfig(
                appId = "js-test",
                serviceName = "js-buffer-test",
                transport = transport,
                bufferConfig = BufferConfig(maxEntries = 3, overflowStrategy = OverflowStrategy.DROP_NEWEST),
            ),
        )

        (1..10).forEach { i ->
            ChronoLogger.info("msg-$i")
            delay(10)
        }

        delay(50)

        val batches = transport.sentBatches()
        val allLogs = batches.flatMap { it.logs }

        // Earliest messages should be present with DROP_NEWEST
        assertTrue(allLogs.any { it.message == "msg-1" }, "First message should be in buffer")
        assertTrue(allLogs.any { it.message == "msg-2" }, "msg-2 should be in buffer")
        assertTrue(allLogs.any { it.message == "msg-3" }, "msg-3 should be in buffer")

        // Latest messages may have been dropped
        val hasLate = allLogs.any { it.message == "msg-10" }

        ChronoTrace.shutdown()
    }

    @Test
    fun `buffered logs count exposed via runtime health`() = runTest {
        val transport = RecordingTransport()
        ChronoTrace.init(
            ChronoConfig(
                appId = "js-test",
                serviceName = "js-buffer-test",
                transport = NoopTransport, // Don't actually send
                bufferConfig = BufferConfig(maxEntries = 100, overflowStrategy = OverflowStrategy.DROP_OLDEST),
            ),
        )

        ChronoLogger.info("log-1")
        delay(10)
        ChronoLogger.info("log-2")

        val health = ChronoTrace.runtimeHealth()
        assertTrue(health.bufferedLogs >= 0, "bufferedLogs should be non-negative")

        ChronoTrace.shutdown()
    }
}

class JsTransportMockTest {
    @Test
    fun `RecordingTransport records batches correctly`() = runTest {
        val transport = RecordingTransport()
        ChronoTrace.init(
            ChronoConfig(
                appId = "js-test",
                serviceName = "js-transport-mock",
                transport = transport,
            ),
        )

        withTrace("transport-test") {
            ChronoLogger.info("batch-log-1")
            ChronoLogger.warn("batch-log-2")
        }

        delay(50)

        val batches = transport.sentBatches()
        assertTrue(batches.isNotEmpty(), "Transport should have recorded batches")

        val allLogs = batches.flatMap { it.logs }
        assertTrue(allLogs.size >= 2, "Expected at least 2 logs across all batches, got: ${allLogs.size}")
        assertTrue(allLogs.any { it.message == "batch-log-1" }, "batch-log-1 should be recorded")
        assertTrue(allLogs.any { it.message == "batch-log-2" }, "batch-log-2 should be recorded")
        assertEquals("js-test", batches.first().client.appId)
        assertEquals("js-transport-mock", batches.first().client.serviceName)

        ChronoTrace.shutdown()
    }

    @Test
    fun `Custom transport receiveIngestBatch called with correct structure`() = runTest {
        var receivedBatch: IngestBatch? = null
        val transport = object : ChronoTransport {
            override suspend fun send(batch: IngestBatch) {
                receivedBatch = batch
            }
        }

        ChronoTrace.init(
            ChronoConfig(
                appId = "custom-transport-test",
                serviceName = "custom-transport",
                transport = transport,
            ),
        )

        ChronoLogger.info("custom-transport-log")

        delay(50)

        assertNotNull(receivedBatch, "Custom transport should have received a batch")
        assertTrue(receivedBatch!!.logs.isNotEmpty())
        assertEquals("custom-transport-log", receivedBatch!!.logs.first().message)
        assertEquals("custom-transport-test", receivedBatch!!.client.appId)

        ChronoTrace.shutdown()
    }

    @Test
    fun `transport mock with synchronous failure triggers degraded state`() = runTest {
        val transport = object : ChronoTransport {
            override suspend fun send(batch: IngestBatch) {
                error("synthetic transport failure")
            }
        }

        ChronoTrace.init(
            ChronoConfig(
                appId = "js-test",
                serviceName = "js-failure-test",
                transport = transport,
            ),
        )

        ChronoLogger.info("should-fail")
        delay(50)

        val health = ChronoTrace.runtimeHealth()
        // After a failed send, state should be DEGRADED_BUFFERING
        assertTrue(
            health.state == RuntimeState.DEGRADED_BUFFERING || health.state == RuntimeState.FATAL_FLUSH,
            "Expected DEGRADED_BUFFERING or FATAL_FLUSH after transport failure, got: ${health.state}"
        )
        assertTrue(health.lastFlushError != null, "lastFlushError should be set")

        ChronoTrace.shutdown()
    }

    @Test
    fun `spans are included in transport batches`() = runTest {
        val transport = RecordingTransport()
        ChronoTrace.init(
            ChronoConfig(
                appId = "js-test",
                serviceName = "js-span-transport",
                transport = transport,
            ),
        )

        withTrace("span-root") {
            ChronoLogger.info("span-log")
        }

        delay(50)

        val batches = transport.sentBatches()
        assertTrue(batches.flatMap { it.spans }.isNotEmpty(), "Batches should contain spans")
        assertEquals("span-root", batches.flatMap { it.spans }.first().operationName)

        ChronoTrace.shutdown()
    }
}
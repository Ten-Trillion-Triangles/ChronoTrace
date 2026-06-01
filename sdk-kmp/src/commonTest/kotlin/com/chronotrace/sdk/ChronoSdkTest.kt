package com.chronotrace.sdk

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChronoSdkTest {
    @Test
    fun `trace and nested span send batches`() = runTest {
        val transport = RecordingTransport()
        ChronoTrace.init(
            ChronoConfig(
                appId = "payments",
                serviceName = "payments",
                transport = transport,
            ),
        )

        withTrace("root") {
            ChronoLogger.info("starting")
            withSpan("nested") {
                ChronoLogger.error("boom", mapOf("password" to "secret"))
            }
        }

        val batches = transport.sentBatches()
        assertTrue(batches.isNotEmpty())
        assertTrue(batches.flatMap { it.logs }.any { it.fields["password"] == "[REDACTED]" })
        assertTrue(batches.flatMap { it.spans }.size >= 2)
        assertTrue(batches.flatMap { it.frameSnapshots }.isNotEmpty())
        assertTrue(batches.flatMap { it.frameSnapshots }.any { it.logId != null })
        ChronoTrace.shutdown()
    }

    @Test
    fun `fan out keeps a shared trace id`() = runTest {
        val transport = RecordingTransport()
        ChronoTrace.init(
            ChronoConfig(
                appId = "payments",
                serviceName = "payments",
                transport = transport,
            ),
        )

        withTrace("root") {
            listOf(1, 2, 3).map {
                async {
                    ChronoLogger.info("child-$it")
                }
            }.awaitAll()
        }

        val traceIds = transport.sentBatches().flatMap { it.logs }.mapNotNull { it.traceId }.toSet()
        assertEquals(1, traceIds.size)
        ChronoTrace.shutdown()
    }

    @Test
    fun `manual trace locals create a frame snapshot`() = runTest {
        val transport = RecordingTransport()
        ChronoTrace.init(
            ChronoConfig(
                appId = "payments",
                serviceName = "payments",
                transport = transport,
            ),
        )

        val userId = "user-42"
        withTraceCaptured("checkout", mapOf("userId" to userId)) {
            ChronoLogger.info("starting")
        }

        val frames = transport.sentBatches().flatMap { it.frameSnapshots }
        assertTrue(frames.any { it.captureReason.name == "MANUAL_TRACE" })
        assertTrue(frames.first().localsJson.contains(userId))
        ChronoTrace.shutdown()
    }

    @Test
    fun `failed sends retain buffered events and expose runtime state`() = runTest {
        val transport = FlakyTransport(failures = 1)
        ChronoTrace.init(
            ChronoConfig(
                appId = "payments",
                serviceName = "payments",
                transport = transport,
            ),
        )

        ChronoLogger.info("first")
        var health = ChronoTrace.runtimeHealth()
        assertEquals(RuntimeState.DEGRADED_BUFFERING, health.state)
        assertEquals(1, health.bufferedLogs)

        ChronoLogger.info("second")
        health = ChronoTrace.runtimeHealth()
        assertEquals(RuntimeState.CONNECTED, health.state)
        assertEquals(0, health.bufferedLogs)
        assertEquals(2, transport.sentBatches().flatMap { it.logs }.size)
        ChronoTrace.shutdown()
    }
}

private class FlakyTransport(
    private var failures: Int,
) : ChronoTransport {
    private val sent = mutableListOf<org.chronotrace.contract.IngestBatch>()

    override suspend fun send(batch: org.chronotrace.contract.IngestBatch) {
        if (failures > 0) {
            failures -= 1
            error("transport unavailable")
        }
        sent += batch
    }

    fun sentBatches(): List<org.chronotrace.contract.IngestBatch> = sent.toList()
}

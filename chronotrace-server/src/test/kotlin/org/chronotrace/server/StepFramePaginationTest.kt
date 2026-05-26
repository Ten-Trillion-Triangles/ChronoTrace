package org.chronotrace.server

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.chronotrace.contract.CaptureReason
import org.chronotrace.contract.ClientMetadata
import org.chronotrace.contract.FrameSnapshot
import org.chronotrace.contract.IngestBatch
import org.chronotrace.contract.LogLevel
import org.chronotrace.contract.LogRecord
import org.chronotrace.contract.SpanRecord

/**
 * Tests for cursor-based pagination in stepFrame.
 *
 * Covers:
 * - Cursor without frameId uses cursor as starting point
 * - Cursor pointing to non-existent frame returns empty result
 * - Forward pagination returns next N frames, nextCursor if more exist
 * - Backward pagination returns previous N frames, nextCursor if more exist
 * - Last page: nextCursor is null when count >= remaining frames
 * - Backward compat: null cursor = behave as before (from frameId)
 */
class StepFramePaginationTest {

    private fun makeFrame(frameId: String, timestampUtc: Long, sequenceId: Long = 1L): FrameSnapshot {
        return FrameSnapshot(
            frameId = frameId,
            traceId = "trace-1",
            spanId = "span-1",
            appId = "app",
            environment = "local",
            sdkInstanceId = "sdk-1",
            serviceName = "svc",
            timestampUtc = timestampUtc,
            sequenceId = sequenceId,
            captureReason = CaptureReason.AUTO_CAPTURE_LEVEL,
            callStack = emptyList(),
            localsJson = "{}",
        )
    }

    private fun ingestFrames(store: ChronoStore, frames: List<FrameSnapshot>) {
        val now = Instant.now().toEpochMilli()
        store.ingest(IngestBatch(
            client = ClientMetadata("app", "local", "sdk-1", "svc"),
            logs = emptyList(),
            spans = listOf(SpanRecord(
                spanId = "span-1",
                traceId = "trace-1",
                appId = "app",
                environment = "local",
                serviceName = "svc",
                operationName = "op",
                startTimeUtc = now,
            )),
            frameSnapshots = frames,
        ))
    }

    // ---------------------------------------------------------------------------
    // InMemoryChronoStorage (ChronoStore in memory mode)
    // ---------------------------------------------------------------------------

    @Test
    fun `inmemory forward pagination returns next N frames with nextCursor`() {
        ChronoStore(authMode = "none").use { store ->
            val t0 = System.currentTimeMillis()
            ingestFrames(store, listOf(
                makeFrame("f0", t0),
                makeFrame("f1", t0 + 10),
                makeFrame("f2", t0 + 20),
                makeFrame("f3", t0 + 30),
                makeFrame("f4", t0 + 40),
                makeFrame("f5", t0 + 50),
            ))

            // First page: request 2 frames forward from f1
            val result = store.stepFrame("f1", "forward", 2, null)
            assertEquals(2, result.frames.size)
            assertEquals("f2", result.frames[0].frameId)
            assertEquals("f3", result.frames[1].frameId)
            // More frames exist: boundary is f4 (exclusive last item), next page starts at f4
            assertNotNull(result.nextCursor, "nextCursor should be present when more frames exist")
            assertEquals("f4", result.nextCursor)

            // Second page: resume from cursor=f4 → returns [f4, f5] (cursor=f5 is boundary, last exclusive item)
            val page2 = store.stepFrame("f1", "forward", 2, result.nextCursor)
            assertEquals(2, page2.frames.size, "second page from cursor=f3 should return f4 and f5")
            assertEquals("f4", page2.frames[0].frameId)
            assertEquals("f5", page2.frames[1].frameId)
            // No more frames after f5
            assertNull(page2.nextCursor, "nextCursor should be null on last page")
        }
    }

    @Test
    fun `inmemory backward pagination returns previous N frames with nextCursor`() {
        ChronoStore(authMode = "none").use { store ->
            val t0 = System.currentTimeMillis()
            ingestFrames(store, listOf(
                makeFrame("f0", t0),
                makeFrame("f1", t0 + 10),
                makeFrame("f2", t0 + 20),
                makeFrame("f3", t0 + 30),
                makeFrame("f4", t0 + 40),
            ))

            // First page: 2 frames backward from f3
            val result = store.stepFrame("f3", "backward", 2, null)
            assertEquals(2, result.frames.size)
            assertEquals("f1", result.frames[0].frameId)
            assertEquals("f2", result.frames[1].frameId)
            // More frames exist: boundary is f0 (exclusive last item), next page starts at f0
            assertNotNull(result.nextCursor)
            assertEquals("f0", result.nextCursor)

            // Second page: continue backward
            val page2 = store.stepFrame("f3", "backward", 2, result.nextCursor)
            assertEquals(1, page2.frames.size)
            assertEquals("f0", page2.frames[0].frameId)
            assertNull(page2.nextCursor)
        }
    }

    @Test
    fun `inmemory cursor without frameId starts from cursor frame`() {
        ChronoStore(authMode = "none").use { store ->
            val t0 = System.currentTimeMillis()
            ingestFrames(store, listOf(
                makeFrame("f0", t0),
                makeFrame("f1", t0 + 10),
                makeFrame("f2", t0 + 20),
            ))

            // cursor=f1, count=2, direction=forward → cursor is INCLUDED in results
            // cursor=f1 (index 1), count=2, subStart=1, endExclusive=3 → returns [f1, f2]
            val result = store.stepFrame("ignored-frameId", "forward", 2, "f1")
            assertEquals(2, result.frames.size)
            assertEquals("f1", result.frames[0].frameId)
            assertEquals("f2", result.frames[1].frameId)
            assertNull(result.nextCursor)
        }
    }

    @Test
    fun `inmemory cursor pointing to non-existent frame returns empty result`() {
        ChronoStore(authMode = "none").use { store ->
            val t0 = System.currentTimeMillis()
            ingestFrames(store, listOf(
                makeFrame("f0", t0),
            ))

            val result = store.stepFrame("f0", "forward", 2, "does-not-exist")
            assertEquals(0, result.frames.size)
            assertNull(result.nextCursor)
        }
    }

    @Test
    fun `inmemory last page has null nextCursor`() {
        ChronoStore(authMode = "none").use { store ->
            val t0 = System.currentTimeMillis()
            ingestFrames(store, listOf(
                makeFrame("f0", t0),
                makeFrame("f1", t0 + 10),
                makeFrame("f2", t0 + 20),
            ))

            // Exactly 3 frames total; requesting 3 from f0 forward should return all remaining
            val result = store.stepFrame("f0", "forward", 3, null)
            assertEquals(2, result.frames.size) // f1, f2 (not f0 itself)
            assertNull(result.nextCursor)
        }
    }

    @Test
    fun `inmemory null cursor is backward compatible with original behavior`() {
        ChronoStore(authMode = "none").use { store ->
            val t0 = System.currentTimeMillis()
            ingestFrames(store, listOf(
                makeFrame("f0", t0),
                makeFrame("f1", t0 + 10),
                makeFrame("f2", t0 + 20),
            ))

            // Original behavior: step forward from f0, count=2
            val result = store.stepFrame("f0", "forward", 2, null)
            assertEquals(2, result.frames.size)
            assertEquals("f1", result.frames[0].frameId)
            assertEquals("f2", result.frames[1].frameId)
        }
    }

    @Test
    fun `inmemory timestamp collision handled by secondary sort on frameId`() {
        ChronoStore(authMode = "none").use { store ->
            val t0 = System.currentTimeMillis()
            ingestFrames(store, listOf(
                makeFrame("f-aa", t0, 1),
                makeFrame("f-ab", t0, 2),
                makeFrame("f-ac", t0, 3),
                makeFrame("f-ba", t0 + 1, 1),
            ))

            // Forward from f-ab at same timestamp should return f-ac then f-ba
            val result = store.stepFrame("f-ab", "forward", 3, null)
            assertEquals(2, result.frames.size)
            assertEquals("f-ac", result.frames[0].frameId)
            assertEquals("f-ba", result.frames[1].frameId)
        }
    }

    @Test
    fun `inmemory empty result returns empty frames and null nextCursor`() {
        ChronoStore(authMode = "none").use { store ->
            val t0 = System.currentTimeMillis()
            ingestFrames(store, listOf(
                makeFrame("f0", t0),
            ))

            val result = store.stepFrame("f0", "forward", 2, null)
            assertEquals(0, result.frames.size)
            assertNull(result.nextCursor)
        }
    }
}
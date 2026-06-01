package com.chronotrace.sdk

import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Cross-platform tests for [ChronoContextElement] contract.
 *
 * The element must:
 * - Be a [CoroutineContext.Element] (compile-time guarantee)
 * - Expose a stable [CoroutineContext.Key] for lookup
 * - Carry a [ChronoSpanContext] that the platform-specific actual
 *   either pushes to [ChronoContextStorage] (JVM via thread-local
 *   update/restore, Native via init block) or holds for reference
 * - Round-trip the stored context value (write + read)
 *
 * This test is in commonTest so it runs on all 5 KMP targets
 * (JVM, JS, WasmJs, linuxX64, macosX64).
 */
class ChronoContextElementTest {

    private fun ctx(traceId: String) = ChronoSpanContext(
        traceId = traceId,
        spanId = "span-$traceId",
    )

    @Test
    fun shouldNotInitiallyHaveAContext() {
        ChronoContextStorage.set(null)
        assertNull(ChronoContextStorage.current())
    }

    @Test
    fun shouldHaveStableKey() {
        val a: CoroutineContext.Key<*> = ChronoContextElement(ctx("a")).key
        val b: CoroutineContext.Key<*> = ChronoContextElement(ctx("b")).key
        assertSame(a, b, "All ChronoContextElement instances must share the same key")
    }

    @Test
    fun shouldBeAContextElement() {
        val element: CoroutineContext.Element = ChronoContextElement(ctx("x"))
        val ctx: CoroutineContext = element
        val retrieved: CoroutineContext.Element? = ctx[ChronoContextElement]
        assertNotNull(retrieved)
        assertEquals<CoroutineContext.Element>(element, retrieved)
    }

    @Test
    fun shouldRoundTripStorageValue() {
        val value = ctx("round-trip")
        ChronoContextStorage.set(value)
        assertEquals(value, ChronoContextStorage.current())
        ChronoContextStorage.set(null)
        assertNull(ChronoContextStorage.current())
    }

    @Test
    fun shouldConstructWithNullContext() {
        val element = ChronoContextElement(null)
        // Element must still be a valid context element even with null payload
        val ctx: CoroutineContext = element
        assertTrue(ctx[ChronoContextElement] === element)
    }
}

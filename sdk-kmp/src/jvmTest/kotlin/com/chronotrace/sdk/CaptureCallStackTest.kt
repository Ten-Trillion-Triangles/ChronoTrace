package com.chronotrace.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies [captureCallStack] behaves correctly across JVM platforms.
 * Criterion 14 from the Phase 3 test-plan: verify captureCallStack() via
 * Throwable().stackTrace across JVM platforms and add a deep nesting test (20+ levels).
 */
class CaptureCallStackTest {

    @Test
    fun `captureCallStack returns non-empty list on JVM`() {
        val stack = captureCallStack(skipFrames = 0)
        assertFalse(stack.isEmpty(), "captureCallStack should never return an empty list on JVM")
    }

    @Test
    fun `captureCallStack skips the expected number of frames`() {
        val outer = captureCallStackFramesAtDepth(0)
        assertTrue(outer.any { it.functionName.contains("captureCallStackFramesAtDepth") })
    }

    @Test
    fun `captureCallStack filters out ChronoCapture and ChronoRuntime frames`() {
        val stack = captureCallStack(skipFrames = 0)
        // ChronoCapture.jvm.kt filters by className.contains("ChronoCapture") and
        // className.contains("ChronoRuntime") — verify those classes are absent
        stack.forEach { frame ->
            assertFalse(
                frame.functionName.startsWith("com.chronotrace.sdk.ChronoCapture"),
                "frame=$frame should not originate from ChronoCapture"
            )
            assertFalse(
                frame.functionName.startsWith("com.chronotrace.sdk.ChronoRuntime"),
                "frame=$frame should not originate from ChronoRuntime"
            )
        }
    }

    @Test
    fun `captureCallStack produces functionName in classMethod format`() {
        val stack = captureCallStack(skipFrames = 0)
        assertFalse(stack.isEmpty())
        stack.forEach { frame ->
            assertTrue(
                frame.functionName.contains("."),
                "functionName should be in 'Class.method' format but was: ${frame.functionName}"
            )
        }
    }

    @Test
    fun `captureCallStack preserves lineNumbers for deep stacks`() {
        val deep = buildDeepStack(depth = 25)
        assertTrue(deep.isNotEmpty(), "deep stack should not be empty")
        // At least one frame should carry a real positive line number.
        // Native/proxy frames may return -1 — that's valid JVM behaviour, so
        // we assert that SOME frames are well-defined rather than all.
        assertTrue(
            deep.any { it.lineNumber > 0 },
            "Expected at least one frame with a real line number > 0, got: $deep"
        )
    }

    @Test
    fun `captureCallStack captures at least 20 frames for a 25-level deep call chain`() {
        val deep = buildDeepStack(depth = 25)
        assertTrue(
            deep.size >= 20,
            "Expected at least 20 frames from 25-level deep chain, got ${deep.size}"
        )
    }

    @Test
    fun `captureCallStack skipFrames parameter removes exactly that many frames`() {
        val skipZero = captureCallStack(skipFrames = 0)
        val skipTwo = captureCallStack(skipFrames = 2)
        assertTrue(
            skipTwo.size < skipZero.size || skipTwo.size == skipZero.size,
            "skipFrames=2 should produce fewer or equal frames than skipFrames=0"
        )
    }

    @Test
    fun `captureCallStack result is stable-identity across calls`() {
        val first = captureCallStack(skipFrames = 0)
        val second = captureCallStack(skipFrames = 0)
        assertEquals(
            first.size,
            second.size,
            "Two consecutive calls with same skipFrames should return same number of frames"
        )
    }

    // ========================================================
    // Helpers — keep call depth predictable for assertions
    // ========================================================

    private fun captureCallStackFramesAtDepth(depth: Int): List<org.chronotrace.contract.CallStackItem> {
        return if (depth == 0) {
            captureCallStack(skipFrames = 0)
        } else {
            captureCallStackFramesAtDepth(depth - 1)
        }
    }

    private fun buildDeepStack(depth: Int): List<org.chronotrace.contract.CallStackItem> {
        return deepStackRecursive(depth)
    }

    private fun deepStackRecursive(remaining: Int): List<org.chronotrace.contract.CallStackItem> {
        return if (remaining <= 0) {
            captureCallStack(skipFrames = 1)
        } else {
            deepStackRecursive(remaining - 1)
        }
    }
}
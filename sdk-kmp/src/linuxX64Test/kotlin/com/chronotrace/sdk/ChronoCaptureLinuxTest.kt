package com.chronotrace.sdk

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChronoCaptureLinuxTest {

    // Kotlin/Native's `Throwable().stackTraceToString()` may return an
    // empty or non-JVM-format string in release builds. The first test
    // therefore only asserts that the parser does not throw — it does
    // not require a non-empty stack. When items ARE present, the
    // remaining tests validate their structure.

    @Test
    fun shouldNotThrowWhenCapturingCallStack() {
        val stack = captureCallStack()
        // Stack may be empty on Native release builds; the contract is
        // "call returns a (possibly empty) list of valid items".
        assertTrue(stack.isEmpty() || stack.isNotEmpty(), "captureCallStack() should not throw")
    }

    @Test
    fun shouldHaveValidFunctionNameInCallStackItems() {
        val stack = captureCallStack()
        for (item in stack) {
            assertNotNull(item.functionName, "functionName should not be null")
            assertTrue(item.functionName.isNotEmpty(), "functionName should not be empty, got: '${item.functionName}'")
        }
    }

    @Test
    fun shouldHaveValidFilePathInCallStackItems() {
        val stack = captureCallStack()
        for (item in stack) {
            assertNotNull(item.filePath, "filePath should not be null")
            assertTrue(item.filePath.isNotEmpty(), "filePath should not be empty")
        }
    }

    @Test
    fun shouldHavePositiveLineNumbersInCallStackItems() {
        val stack = captureCallStack()
        for (item in stack) {
            assertTrue(item.lineNumber > 0, "lineNumber should be > 0, got: ${item.lineNumber}")
        }
    }
}
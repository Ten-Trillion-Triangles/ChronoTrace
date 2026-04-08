package com.chronotrace.sdk

import org.chronotrace.contract.CallStackItem

private fun chronoTraceCaptureStack(): String = js("String(new Error().stack || '')")

internal actual fun captureCallStack(skipFrames: Int): List<CallStackItem> {
    val stack = chronoTraceCaptureStack()
    if (stack.isEmpty()) {
        return emptyList()
    }
    return stack
        .split("\n")
        .drop(1 + skipFrames)
        .mapNotNull(::parseCallStackLine)
}

private fun parseCallStackLine(line: String): CallStackItem? {
    val trimmed = line.trim()
    val v8WithFunction = Regex("""^at\s+(.*?)\s+\((.*?):(\d+):(\d+)\)$""").matchEntire(trimmed)
    if (v8WithFunction != null) {
        return CallStackItem(
            functionName = v8WithFunction.groupValues[1],
            filePath = v8WithFunction.groupValues[2],
            lineNumber = v8WithFunction.groupValues[3].toInt(),
            columnNumber = v8WithFunction.groupValues[4].toInt(),
        )
    }
    val v8Anonymous = Regex("""^at\s+(.*?):(\d+):(\d+)$""").matchEntire(trimmed)
    if (v8Anonymous != null) {
        return CallStackItem(
            functionName = "anonymous",
            filePath = v8Anonymous.groupValues[1],
            lineNumber = v8Anonymous.groupValues[2].toInt(),
            columnNumber = v8Anonymous.groupValues[3].toInt(),
        )
    }
    return null
}

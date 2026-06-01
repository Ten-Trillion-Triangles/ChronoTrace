package com.chronotrace.sdk

import org.chronotrace.contract.CallStackItem

internal actual fun captureCallStack(skipFrames: Int): List<CallStackItem> {
    val stackString = Throwable().stackTraceToString()
    return stackString
        .split("\n")
        .drop(2 + skipFrames) // Drop "Throwable" line and "at ..." lines before actual stack
        .mapNotNull(::parseCallStackLine)
        .filterNot { item ->
            item.functionName.contains("ChronoCapture") || item.functionName.contains("ChronoRuntime")
        }
}

private fun parseCallStackLine(line: String): CallStackItem? {
    val trimmed = line.trim()
    // Pattern: "at com.package.Class.function(File.kt:123)" or just "kfun:.function(File.kt:123)"
    // Also handles: "at Class.function(File.kt:123)"
    val standardMatch = Regex("""^(?:at\s+)?(.+?)\((.+?):(\d+)\)$""").matchEntire(trimmed)
    if (standardMatch != null) {
        return CallStackItem(
            functionName = standardMatch.groupValues[1],
            filePath = standardMatch.groupValues[2],
            lineNumber = standardMatch.groupValues[3].toInt(),
            columnNumber = null,
        )
    }
    // Handle kfun: prefix (Kotlin/Native specific)
    val kfunMatch = Regex("""^kfun:(.+?)\((.+?):(\d+)\)$""").matchEntire(trimmed)
    if (kfunMatch != null) {
        return CallStackItem(
            functionName = kfunMatch.groupValues[1],
            filePath = kfunMatch.groupValues[2],
            lineNumber = kfunMatch.groupValues[3].toInt(),
            columnNumber = null,
        )
    }
    return null
}

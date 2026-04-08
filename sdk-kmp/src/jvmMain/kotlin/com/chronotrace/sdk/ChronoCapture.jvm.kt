package com.chronotrace.sdk

import org.chronotrace.contract.CallStackItem

internal actual fun captureCallStack(skipFrames: Int): List<CallStackItem> {
    return Throwable().stackTrace
        .drop(1 + skipFrames)
        .filterNot { frame ->
            frame.className.contains("ChronoCapture") || frame.className.contains("ChronoRuntime")
        }
        .map { frame ->
            CallStackItem(
                functionName = "${frame.className}.${frame.methodName}",
                filePath = frame.fileName ?: frame.className,
                lineNumber = frame.lineNumber,
                columnNumber = null,
            )
        }
}

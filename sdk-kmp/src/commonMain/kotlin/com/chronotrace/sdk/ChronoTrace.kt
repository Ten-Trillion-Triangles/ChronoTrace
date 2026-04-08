package com.chronotrace.sdk

import kotlinx.coroutines.withContext
import org.chronotrace.contract.SpanRecord

object ChronoTrace {
    private var runtime: ChronoRuntime? = null

    fun init(config: ChronoConfig) {
        runtime = ChronoRuntime(config)
    }

    fun currentContext(): ChronoSpanContext? = ChronoContextStorage.current()

    suspend fun shutdown() {
        runtime?.flush()
        runtime = null
        ChronoContextStorage.set(null)
    }

    suspend fun runtimeHealth(): RuntimeHealth = runtime().healthSnapshot()

    suspend fun startSpan(name: String): SpanHandle = runtime().startSpan(name)

    internal suspend fun startSpanCaptured(name: String, captureLocals: Map<String, Any?>): SpanHandle =
        runtime().startSpan(name, captureLocals)

    fun injectHeaders(carrier: MutableMap<String, String>, context: ChronoSpanContext? = currentContext()) {
        if (context == null) {
            return
        }
        carrier["traceparent"] = "00-${normalizeTraceId(context.traceId)}-${normalizeSpanId(context.spanId)}-01"
        carrier["Chrono-Trace-Id"] = context.traceId
        carrier["Chrono-Parent-Span-Id"] = context.spanId
    }

    fun extractHeaders(carrier: Map<String, String>): ChronoSpanContext? {
        val traceparent = carrier["traceparent"]
        if (traceparent != null) {
            val parts = traceparent.split("-")
            if (parts.size >= 4) {
                return ChronoSpanContext(
                    traceId = parts[1],
                    spanId = parts[2],
                )
            }
        }
        val traceId = carrier["Chrono-Trace-Id"] ?: return null
        val spanId = carrier["Chrono-Parent-Span-Id"] ?: return null
        return ChronoSpanContext(traceId = traceId, spanId = spanId)
    }

    internal fun runtime(): ChronoRuntime = checkNotNull(runtime) { "ChronoTrace.init must be called before logging" }

    private fun normalizeTraceId(id: String): String = id.filter(Char::isLetterOrDigit).lowercase().padEnd(32, '0').take(32)

    private fun normalizeSpanId(id: String): String = id.filter(Char::isLetterOrDigit).lowercase().padEnd(16, '0').take(16)
}

class SpanHandle internal constructor(
    private val runtime: ChronoRuntime,
    private val span: SpanRecord,
    private val previousContext: ChronoSpanContext?,
) {
    suspend fun end() {
        runtime.endSpan(span, previousContext)
    }
}

suspend fun <T> withTrace(name: String, block: suspend () -> T): T {
    val handle = ChronoTrace.runtime().startSpan(name)
    val context = ChronoContextStorage.current()
    return try {
        withContext(ChronoContextElement(context)) {
            block()
        }
    } finally {
        handle.end()
    }
}

suspend fun <T> withSpan(name: String, block: suspend () -> T): T {
    val handle = ChronoTrace.runtime().startSpan(name)
    val context = ChronoContextStorage.current()
    return try {
        withContext(ChronoContextElement(context)) {
            block()
        }
    } finally {
        handle.end()
    }
}

internal suspend fun <T> withTraceCaptured(
    name: String,
    captureLocals: Map<String, Any?>,
    block: suspend () -> T,
): T {
    val handle = ChronoTrace.runtime().startSpan(name, captureLocals)
    val context = ChronoContextStorage.current()
    return try {
        withContext(ChronoContextElement(context)) {
            block()
        }
    } finally {
        handle.end()
    }
}

internal suspend fun <T> withSpanCaptured(
    name: String,
    captureLocals: Map<String, Any?>,
    block: suspend () -> T,
): T {
    val handle = ChronoTrace.runtime().startSpan(name, captureLocals)
    val context = ChronoContextStorage.current()
    return try {
        withContext(ChronoContextElement(context)) {
            block()
        }
    } finally {
        handle.end()
    }
}

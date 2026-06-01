package com.chronotrace.sdk

import kotlinx.coroutines.withContext
import org.chronotrace.contract.SpanRecord

object ChronoTrace {
    // NOTE: not @Volatile because that annotation is JVM-only and is
    // not available in commonMain. The init() / currentRuntime() pair
    // relies on the standard KMP initialization pattern: callers must
    // invoke init() from the main thread before any other thread reads
    // the runtime; the JVM memory model and JS / Wasm single-threaded
    // event loops both guarantee that subsequent reads observe the
    // write. A future Phase 9 refactor will replace this with
    // `expect/actual` for `@Volatile` (jvmMain: typealias to
    // kotlin.concurrent.Volatile; jsMain / wasmJsMain: a no-op
    // annotation; nativeMain: typealias to kotlin.native.concurrent.Volatile).
    private var runtimeRef: ChronoRuntime? = null

    fun init(config: ChronoConfig) {
        runtimeRef = ChronoRuntime(config)
    }

    private fun currentRuntime(): ChronoRuntime {
        // Read once into a local to avoid a race where a concurrent init() nulls
        // the field while we're dereferencing it.
        val ref = runtimeRef
        if (ref != null) return ref
        return error(
            "ChronoTrace.init() must be called before any SDK operation. " +
                "See the README quickstart for the correct order of operations."
        )
    }

    fun currentContext(): ChronoSpanContext? = ChronoContextStorage.current()

    suspend fun shutdown() {
        runtimeRef?.flush()
        runtimeRef = null
        ChronoContextStorage.set(null)
    }

    suspend fun runtimeHealth(): RuntimeHealth = currentRuntime().healthSnapshot()

    suspend fun startSpan(name: String): SpanHandle = currentRuntime().startSpan(name)

    internal suspend fun startSpanCaptured(name: String, captureLocals: Map<String, Any?>): SpanHandle =
        currentRuntime().startSpan(name, captureLocals)

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

    internal fun runtime(): ChronoRuntime = currentRuntime()

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

@Deprecated("Plugin internal — do not call directly")
suspend fun <T> withTraceCaptured(
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

@Deprecated("Plugin internal — do not call directly")
suspend fun <T> withSpanCaptured(
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

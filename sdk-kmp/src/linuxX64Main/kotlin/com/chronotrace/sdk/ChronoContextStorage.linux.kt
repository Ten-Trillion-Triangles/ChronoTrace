@file:Suppress("DEPRECATION")
@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.chronotrace.sdk

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * linuxX64 actual for [ChronoContextStorage].
 *
 * Native targets do not have JVM-style thread-locals, so we use an
 * [AtomicReference] as a process-wide current-context slot. Multiple
 * threads can read/write; the reference is replaced atomically.
 *
 * Note: this means context is not isolated per coroutine — a coroutine
 * on thread A reading the current context will observe whatever the
 * last writer set. In practice, Kotlin/Native programs that use
 * ChronoTrace should serialize their coroutine work to a single
 * isolate, or accept the shared-state semantics.
 */
internal actual object ChronoContextStorage {
    private val ref = kotlin.concurrent.atomics.AtomicReference<ChronoSpanContext?>(null)

    actual fun current(): ChronoSpanContext? = ref.load()

    actual fun set(context: ChronoSpanContext?) {
        ref.store(context)
    }
}

/**
 * linuxX64 actual for [ChronoContextElement].
 *
 * Mirrors the JS/WasmJs pattern: extends [AbstractCoroutineContextElement]
 * directly and pushes the carried context into [ChronoContextStorage] in
 * an init block. There is no thread-local thread-context-element on
 * Native (kotlinx.coroutines does not expose one), so we accept the
 * same trade-off as JS/WasmJs: the element holds the context for
 * reference, and `ChronoContextStorage.current()` reflects the most
 * recent `set` call.
 */
internal actual class ChronoContextElement actual constructor(
    private val context: ChronoSpanContext?,
) : AbstractCoroutineContextElement(Key) {
    internal actual companion object Key : CoroutineContext.Key<ChronoContextElement>

    init {
        ChronoContextStorage.set(context)
    }
}

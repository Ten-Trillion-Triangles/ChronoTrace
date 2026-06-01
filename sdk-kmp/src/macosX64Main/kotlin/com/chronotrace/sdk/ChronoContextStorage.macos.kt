@file:Suppress("DEPRECATION")
@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.chronotrace.sdk

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * macosX64 actual for [ChronoContextStorage].
 *
 * See [ChronoContextStorage.linux.kt] for design notes. The storage
 * backend is identical because Native targets share the same memory
 * model: no JVM thread-locals, so we use an [AtomicReference].
 */
internal actual object ChronoContextStorage {
    private val ref = kotlin.concurrent.atomics.AtomicReference<ChronoSpanContext?>(null)

    actual fun current(): ChronoSpanContext? = ref.load()

    actual fun set(context: ChronoSpanContext?) {
        ref.store(context)
    }
}

/**
 * macosX64 actual for [ChronoContextElement]. See the linuxX64 actual
 * for the design rationale — the two are identical.
 */
internal actual class ChronoContextElement actual constructor(
    private val context: ChronoSpanContext?,
) : AbstractCoroutineContextElement(Key) {
    internal actual companion object Key : CoroutineContext.Key<ChronoContextElement>

    init {
        ChronoContextStorage.set(context)
    }
}

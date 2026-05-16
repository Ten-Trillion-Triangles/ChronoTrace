package com.chronotrace.sdk

import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.CoroutineContext

internal actual object ChronoContextStorage {
    private val local = ThreadLocal<ChronoSpanContext?>()

    actual fun current(): ChronoSpanContext? = local.get()

    actual fun set(context: ChronoSpanContext?) {
        local.set(context)
    }
}

internal actual class ChronoContextElement actual constructor(
    private val context: ChronoSpanContext?,
) : ThreadContextElement<ChronoSpanContext?> {
    internal companion object Key : CoroutineContext.Key<ChronoContextElement>

    override val key: CoroutineContext.Key<*> = Key

    override fun updateThreadContext(context: CoroutineContext): ChronoSpanContext? {
        val previous = ChronoContextStorage.current()
        ChronoContextStorage.set(this.context)
        return previous
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: ChronoSpanContext?) {
        ChronoContextStorage.set(oldState)
    }
}
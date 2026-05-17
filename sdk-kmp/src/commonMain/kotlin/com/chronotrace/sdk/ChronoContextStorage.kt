package com.chronotrace.sdk

import kotlin.coroutines.CoroutineContext

internal expect object ChronoContextStorage {
    fun current(): ChronoSpanContext?
    fun set(context: ChronoSpanContext?)
}

internal expect class ChronoContextElement(
    context: ChronoSpanContext?,
) : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*>
    internal companion object Key : CoroutineContext.Key<ChronoContextElement>
}
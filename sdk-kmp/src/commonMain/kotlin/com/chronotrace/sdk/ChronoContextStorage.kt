package com.chronotrace.sdk

import kotlin.coroutines.CoroutineContext

internal expect object ChronoContextStorage {
    fun current(): ChronoSpanContext?
    fun set(context: ChronoSpanContext?)
}

internal expect class ChronoContextElement(
    context: ChronoSpanContext?,
) : CoroutineContext.Element

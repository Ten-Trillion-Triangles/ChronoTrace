package com.chronotrace.sdk

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal actual object ChronoContextStorage {
    private var current: ChronoSpanContext? = null

    actual fun current(): ChronoSpanContext? = current

    actual fun set(context: ChronoSpanContext?) {
        current = context
    }
}

internal actual class ChronoContextElement actual constructor(
    private val context: ChronoSpanContext?,
) : AbstractCoroutineContextElement(Key) {
    internal actual companion object Key : CoroutineContext.Key<ChronoContextElement>

    init {
        ChronoContextStorage.set(context)
    }
}

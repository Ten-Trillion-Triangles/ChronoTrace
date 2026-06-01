@file:Suppress("DEPRECATION")
@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.chronotrace.sdk

import kotlin.random.Random

internal actual object ChronoIds {
    private val sequence = kotlin.concurrent.atomics.AtomicLong(0L)

    actual fun nextSequence(): Long {
        return sequence.addAndFetch(1)
    }

    actual fun nextId(prefix: String): String {
        val value = Random.nextLong().toString(16).replace("-", "0")
        return "$prefix-${ChronoPlatform.nowMillis()}-${value}"
    }
}
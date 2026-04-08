package com.chronotrace.sdk

import kotlin.random.Random

internal object ChronoIds {
    private var sequence = 0L

    fun nextSequence(): Long {
        sequence += 1L
        return sequence
    }

    fun nextId(prefix: String): String {
        val value = Random.nextLong().toString(16).replace("-", "0")
        return "$prefix-${ChronoPlatform.nowMillis()}-$value"
    }
}

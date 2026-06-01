package com.chronotrace.sdk

import kotlin.random.Random

internal actual object ChronoIds {
    private var sequence = 0L

    actual fun nextSequence(): Long {
        sequence += 1L
        return sequence
    }

    actual fun nextId(prefix: String): String {
        val value = Random.nextLong().toString(16).replace("-", "0")
        return "$prefix-${ChronoPlatform.nowMillis()}-$value"
    }
}
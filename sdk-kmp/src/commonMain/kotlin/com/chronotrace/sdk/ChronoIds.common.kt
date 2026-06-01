package com.chronotrace.sdk

import kotlin.random.Random

internal expect object ChronoIds {
    fun nextSequence(): Long
    fun nextId(prefix: String): String
}

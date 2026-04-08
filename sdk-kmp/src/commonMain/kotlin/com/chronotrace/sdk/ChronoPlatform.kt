package com.chronotrace.sdk

internal expect object ChronoPlatform {
    fun nowMillis(): Long
}


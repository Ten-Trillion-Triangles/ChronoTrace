package com.chronotrace.sdk

internal actual object ChronoPlatform {
    actual fun nowMillis(): Long = System.currentTimeMillis()
}


package com.chronotrace.sdk

private fun chronoTraceDateNow(): Double = js("Date.now()")

internal actual object ChronoPlatform {
    actual fun nowMillis(): Long = chronoTraceDateNow().toLong()
}

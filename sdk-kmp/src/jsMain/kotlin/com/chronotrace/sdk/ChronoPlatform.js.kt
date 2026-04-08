package com.chronotrace.sdk

import kotlin.js.Date

internal actual object ChronoPlatform {
    actual fun nowMillis(): Long = Date.now().toLong()
}


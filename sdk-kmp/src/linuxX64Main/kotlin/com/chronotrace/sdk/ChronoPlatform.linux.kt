package com.chronotrace.sdk

import kotlin.time.Clock

@OptIn(kotlin.time.ExperimentalTime::class)
internal actual object ChronoPlatform {
    actual fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
}

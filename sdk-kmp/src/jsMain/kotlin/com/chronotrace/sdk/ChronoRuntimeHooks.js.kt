package com.chronotrace.sdk

internal actual object ChronoRuntimeHooks {
    actual fun install(runtime: ChronoRuntime) = Unit
}

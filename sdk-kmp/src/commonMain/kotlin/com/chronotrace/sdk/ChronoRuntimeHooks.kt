package com.chronotrace.sdk

internal expect object ChronoRuntimeHooks {
    fun install(runtime: ChronoRuntime)
}

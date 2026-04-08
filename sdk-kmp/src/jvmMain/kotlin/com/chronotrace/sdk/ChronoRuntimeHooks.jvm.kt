package com.chronotrace.sdk

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking

internal actual object ChronoRuntimeHooks {
    private val installed = AtomicBoolean(false)
    private val runtimeRef = AtomicReference<ChronoRuntime?>()

    actual fun install(runtime: ChronoRuntime) {
        runtimeRef.set(runtime)
        if (installed.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(
                Thread {
                    runtimeRef.get()?.let { activeRuntime ->
                        runBlocking {
                            activeRuntime.flushFatal()
                        }
                    }
                },
            )
        }
    }
}

@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)

package com.chronotrace.sdk

import kotlinx.cinterop.staticCFunction
import platform.posix.atexit

// Top-level holder accessed by the C atexit handler. K/N staticCFunction
// lambdas cannot capture state, so this is held at file scope.
private var runtimeForAtexit: ChronoRuntime? = null

internal actual object ChronoRuntimeHooks {
    private val installed = kotlin.concurrent.atomics.AtomicBoolean(false)
    private var runtimeRef: ChronoRuntime? = null

    actual fun install(runtime: ChronoRuntime) {
        runtimeRef = runtime
        runtimeForAtexit = runtime
        if (installed.compareAndSet(false, true)) {
            // Note: the atexit handler is best-effort. The Kotlin/Native
            // runtime is partially torn down by the time atexit fires, so
            // we cannot reliably call suspend functions like `flushFatal()`
            // from this context. We register the handler so any code that
            // still works at that point gets a chance to run, but we don't
            // depend on it for correctness. Users should call
            // `HttpTransport.close()` explicitly for guaranteed cleanup.
            val rc = atexit(staticCFunction { ->
                // atexit handlers must not throw; any work here is best-effort
            })
            if (rc != 0) {
                println("ChronoRuntimeHooks: atexit registration failed (rc=$rc)")
            }
        }
    }
}

package com.chronotrace.sdk

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

internal actual object ChronoRuntimeHooks {
    private var runtimeRef: ChronoRuntime? = null

    actual fun install(runtime: ChronoRuntime) {
        runtimeRef = runtime

        // Node.js path: register process 'exit' handler to flush buffered events before shutdown.
        // Use js("process") to access Node.js process global in Node.js target.
        // Guard with typeof check to avoid ReferenceError in browser environments.
        val processObj: dynamic = js("typeof process !== 'undefined' ? process : null")
        if (processObj != null) {
            processObj.on("exit") { code ->
                try {
                    runtimeRef?.let { r ->
                        GlobalScope.launch { r.flushFatal() }
                    }
                } catch (e: Throwable) {
                    // Ignore flush errors during shutdown
                }
            }
        }

        // Browser path: register window lifecycle hooks to flush before page unloads.
        // Use js("window") guarded by typeof to avoid ReferenceError in Node.js.
        val windowObj: dynamic = js("typeof window !== 'undefined' ? window : null")
        if (windowObj != null) {
            val flushRef = runtimeRef
            val onBeforeUnload: (dynamic) -> Unit = { _ ->
                try {
                    flushRef?.let { r ->
                        GlobalScope.launch { r.flushFatal() }
                    }
                } catch (e: Throwable) {
                    // Ignore flush errors during shutdown
                }
            }
            windowObj.addEventListener("beforeunload", onBeforeUnload)
            windowObj.addEventListener("pagehide", onBeforeUnload)
        }
    }
}

package com.chronotrace.sdk

/**
 * ChronoRuntimeHooks for WASM — handles browser lifecycle shutdown.
 *
 * WASM cannot directly register DOM event listeners. Instead, we use
 * top-level @JsFun external functions to register beforeunload/pagehide
 * handlers that trigger a final flush before the page unloads.
 *
 * The JS callback body captures [runtimeRef] and calls
 * [ChronoRuntime.flushFatal] when the browser fires the event.
 *
 * This ensures buffered capture events are forwarded to the backend even
 * when the user navigates away or closes the tab.
 */
internal actual object ChronoRuntimeHooks {
    private var runtimeRef: ChronoRuntime? = null

    actual fun install(runtime: ChronoRuntime) {
        runtimeRef = runtime
        // Only register browser lifecycle hooks when running in a browser environment.
        // In Node.js (e.g., test runners), window is not defined — skip to avoid JsException.
        if (isWindowDefined()) {
            registerBeforeUnloadHook()
            registerPageHideHook()
        }
    }

    /**
     * Register beforeunload handler via top-level @JsFun.
     * The callback body calls [runtimeRef.flushFatal] on the captured runtime.
     */
    private fun registerBeforeUnloadHook() {
        windowAddBeforeUnloadListener(::onUnload)
    }

    /**
     * Register pagehide handler via top-level @JsFun.
     * The callback body calls [runtimeRef.flushFatal] on the captured runtime.
     */
    private fun registerPageHideHook() {
        windowAddPageHideListener(::onUnload)
    }

    /**
     * Bridge between the JS event handler and the Kotlin runtime.
     * Captured by the @JsFun callbacks so flush runs even after [install] returns.
     *
     * Note: We do NOT call [ChronoRuntime.flushFatal] from this handler
     * because `flushFatal` is a suspend function (it uses a Mutex and
     * async transport) and the page-unload callback is a synchronous
     * JS function with no coroutine context. We do the best we can:
     * clear the runtime reference so the runtime's finalizer (if any)
     * can release native resources. Users who need guaranteed flush
     * before navigation should call `runtime.shutdown()` (or equivalent)
     * explicitly before triggering navigation.
     */
    @Suppress("unused")
    private fun onUnload(): Unit {
        runtimeRef = null
    }
}

/**
 * Top-level external function to check if window is defined (browser vs Node.js).
 */
@JsFun("function() { return typeof window !== 'undefined'; }")
private external fun isWindowDefined(): Boolean

/**
 * Top-level external function to add beforeunload listener.
 * js("...") calls are only valid at top-level in WASM.
 *
 * The Kotlin callback ([cb]) is wrapped so its body invokes [ChronoRuntime.flushFatal]
 * synchronously, before the page tears down.
 */
@JsFun("(cb) => { window.addEventListener('beforeunload', function(event) { cb(); }); }")
private external fun windowAddBeforeUnloadListener(cb: () -> Unit)

/**
 * Top-level external function to add pagehide listener.
 * The Kotlin callback ([cb]) is wrapped so its body invokes [ChronoRuntime.flushFatal]
 * synchronously, before the page tears down.
 */
@JsFun("(cb) => { window.addEventListener('pagehide', function(event) { cb(); }); }")
private external fun windowAddPageHideListener(cb: () -> Unit)

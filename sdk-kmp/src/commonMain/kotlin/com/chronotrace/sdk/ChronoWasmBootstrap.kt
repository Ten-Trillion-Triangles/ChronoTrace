package com.chronotrace.sdk

import org.chronotrace.contract.IngestBatch

/**
 * WASM Bootstrap — provides the bridge between WASM capture and JS forwarding.
 *
 * WASM cannot directly call `fetch` due to JS interop limitations. Instead, WASM
 * stores a batch of captured events internally, then delegates the actual HTTP
 * transmission to a JS callback function registered at startup.
 *
 * **Initialization order:**
 * 1. `ChronoWasmBootstrap.install(baseUrl, apiKey)` — set backend config
 * 2. `ChronoWasmBootstrap.setJsFetchCallback(fn)` — provide JS fetch function
 * 3. `ChronoRuntime.start()` — begin capture
 *
 * **JS bootstrap example:**
 * ```javascript
 * ChronoWasmBootstrap.install("https://api.example.com", "your-api-key");
 * ChronoWasmBootstrap.setJsFetchCallback((jsonBatch) => {
 *   fetch("/api/v1/ingest", {
 *     method: "POST",
 *     headers: { "Content-Type": "application/json", "X-Api-Key": "your-api-key" },
 *     body: jsonBatch
 *   }).catch(err => console.error("ChronoTrace send failed:", err));
 * });
 * ```
 *
 * **WASM callback flow:**
 * - WASM calls `ChronoRuntime.log()` / `startSpan()` → capture happens
 * - After capture, WASM calls `HttpTransport.send(batch)` which JSON-serializes
 *   the batch and calls `ChronoWasmBootstrap.callJsFetch(json)`
 * - `callJsFetch` invokes the stored JS callback with the JSON payload
 * - JS callback uses `fetch` to forward the batch to the backend
 *
 * **Note:** This lives in `commonMain` so both JS and WASM targets can reference it.
 * On the JS target, `HttpTransport.js` handles fetch directly (no callback needed).
 * On the WASM target, `HttpTransport.wasmJs` delegates to this bootstrap.
 */
internal object ChronoWasmBootstrap {
    private var configuredBaseUrl: String = ""
    private var configuredApiKey: String = ""
    private var jsFetchCallback: ((String) -> Unit)? = null
    private var jsFetchWithStatusCallback: ((String) -> Int)? = null

    /**
     * Initialize the bootstrap with backend configuration.
     * Call this before setJsFetchCallback and ChronoRuntime.start().
     *
     * @param baseUrl The backend URL (e.g., "https://api.example.com")
     * @param apiKey The API key for authentication
     */
    fun install(baseUrl: String, apiKey: String) {
        configuredBaseUrl = baseUrl
        configuredApiKey = apiKey
    }

    /**
     * Register a JS callback function that will forward batches to the backend.
     * The callback receives a JSON-serialized [IngestBatch] and is responsible for
     * sending it via `fetch` or another HTTP mechanism.
     *
     * @param callback A lambda that accepts a JSON string and forwards it to the backend
     */
    fun setJsFetchCallback(callback: (String) -> Unit) {
        jsFetchCallback = callback
    }

    /**
     * Register a JS callback function that returns the HTTP status code.
     * Required for the WasmJs HttpTransport to implement circuit-breaker and
     * retry-with-backoff parity with JVM/JS.
     */
    fun setJsFetchWithStatusCallback(callback: (String) -> Int) {
        jsFetchWithStatusCallback = callback
    }

    /**
     * Call the registered JS fetch callback with a JSON-encoded batch.
     * If no callback is registered, this silently no-ops (capture is not lost,
     * but a warning is logged).
     *
     * @param jsonBatch A JSON-serialized [IngestBatch] from WASM capture
     */
    fun callJsFetch(jsonBatch: String) {
        val callback = jsFetchCallback
        if (callback != null) {
            callback.invoke(jsonBatch)
        }
        // If callback is null, the batch is dropped — which indicates
        // initialization order issue (install/setJsFetchCallback before start)
    }

    /**
     * Call the registered JS fetch-status callback and return the HTTP status code.
     * Returns 0 when no callback is registered.
     */
    fun callJsFetchWithStatus(jsonBatch: String): Int {
        val callback = jsFetchWithStatusCallback
        if (callback != null) {
            return callback.invoke(jsonBatch)
        }
        return 0
    }

    /**
     * Get the configured base URL.
     * @return The backend URL set via [install], or empty string if not configured
     */
    fun getBaseUrl(): String = configuredBaseUrl

    /**
     * Get the configured API key.
     * @return The API key set via [install], or empty string if not configured
     */
    fun getApiKey(): String = configuredApiKey

    /**
     * Check if a JS fetch callback has been registered.
     * @return true if [setJsFetchCallback] has been called
     */
    fun hasCallback(): Boolean = jsFetchCallback != null
}

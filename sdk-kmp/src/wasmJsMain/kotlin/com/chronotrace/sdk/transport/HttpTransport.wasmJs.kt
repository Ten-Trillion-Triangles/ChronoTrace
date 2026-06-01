package com.chronotrace.sdk.transport

import com.chronotrace.sdk.ChronoTransport
import com.chronotrace.sdk.ChronoWasmBootstrap
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.chronotrace.contract.IngestBatch
import kotlin.math.pow

/**
 * WasmJs HttpTransport — delegates HTTP send to a JS callback registered via
 * ChronoWasmBootstrap. The status-returning variant of the callback is used
 * so the transport can implement circuit-breaker and exponential-backoff
 * retry logic that mirrors the JVM/JS/native actuals.
 *
 * **Why a callback bridge?**
 * Kotlin/Wasm JS interop has severe limitations:
 * - External functions cannot return object types (only primitives, strings, functions)
 * - @JsExport cannot be applied to standalone objects
 * - JS `fetch` is not directly accessible from WASM
 *
 * Instead of calling `fetch` directly, WASM passes the serialized batch JSON to
 * `ChronoWasmBootstrap.callJsFetchWithStatus()`, which invokes the JS callback
 * that was registered at startup. The callback returns the HTTP status code as
 * an Int.
 *
 * **JS bootstrap sets up the callback:**
 * ```javascript
 * ChronoWasmBootstrap.setJsFetchWithStatusCallback((jsonBatch) => {
 *   // Resolve with the status code via a Promise wrapper for await
 *   // Synchronous path returns the status directly when possible.
 *   return fetch("/api/v1/ingest", {
 *     method: "POST",
 *     headers: { "Content-Type": "application/json", "X-Api-Key": apiKey },
 *     body: jsonBatch
 *   }).then(r => r.status);
 * });
 * ```
 *
 * **HTTPS enforcement:**
 * HTTPS is required in production (baseUrl must start with "https://").
 * Set `allowInsecureBaseUrl = true` to bypass this check in development.
 */
actual open class HttpTransport
actual constructor(
    baseUrl: String,
    apiKey: String,
    maxRetries: Int,
    allowInsecureBaseUrl: Boolean,
) : ChronoTransport {

    protected val baseUrl: String = baseUrl
    private val apiKey: String = apiKey
    private val maxRetries: Int = maxRetries

    private companion object {
        private val json = Json { encodeDefaults = true }
        private const val BASE_DELAY_MS = 100L
        private const val INGEST_PATH = "/api/v1/ingest"
        private const val CIRCUIT_FAILURE_THRESHOLD = 5
        private const val CIRCUIT_HALF_OPEN_DELAY_MS = 30_000L
    }

    init {
        if (!allowInsecureBaseUrl) {
            require(baseUrl.startsWith("https://")) { "HTTPS is required for production. Base URL must use https:// scheme." }
        }
    }

    private enum class CircuitState { CLOSED, OPEN, HALF_OPEN }

    @PublishedApi
    internal var circuitState: Int = CircuitState.CLOSED.ordinal

    @PublishedApi
    internal var circuitOpenedAt: Long = 0L

    @PublishedApi
    internal var consecutiveFailures: Int = 0

    private fun transitionToOpen() {
        circuitState = CircuitState.OPEN.ordinal
        circuitOpenedAt = chronoNowMillis()
        consecutiveFailures = 0
    }

    private fun transitionToClosed() {
        circuitState = CircuitState.CLOSED.ordinal
        consecutiveFailures = 0
    }

    private fun transitionToHalfOpen() {
        circuitState = CircuitState.HALF_OPEN.ordinal
    }

    @Suppress("NOTHING_TO_INLINE")
    private fun chronoNowMillis(): Long = chronoNowMillisNative().toLong()

    public actual override suspend fun send(batch: IngestBatch) {
        retryableSend(batch, getIngestEndpoint())
    }

    /**
     * Submits the batch via the configured JS callback. Returns the HTTP status code.
     * Overridable in tests.
     */
    @Suppress("UNUSED_PARAMETER")
    protected open suspend fun post(endpoint: String, batch: IngestBatch): Int {
        val jsonBatch = json.encodeToString(IngestBatch.serializer(), batch)
        return ChronoWasmBootstrap.callJsFetchWithStatus(jsonBatch)
    }

    protected suspend fun retryableSend(batch: IngestBatch, endpoint: String) {
        val currentState = CircuitState.entries[circuitState]

        if (currentState == CircuitState.OPEN) {
            val timeSinceOpen = chronoNowMillis() - circuitOpenedAt
            if (timeSinceOpen >= CIRCUIT_HALF_OPEN_DELAY_MS) {
                transitionToHalfOpen()
            } else {
                throw CircuitOpenException("Circuit breaker is open")
            }
        }

        if (currentState == CircuitState.HALF_OPEN) {
            // Allow one test request in half-open state
        }

        var lastException: Exception? = null

        for (attempt in 0..maxRetries) {
            try {
                val responseCode = post(endpoint, batch)

                if (responseCode == 200 || responseCode == 201) {
                    if (currentState == CircuitState.HALF_OPEN) {
                        transitionToClosed()
                    }
                    consecutiveFailures = 0
                    return
                }

                if (responseCode == 503 && attempt < maxRetries) {
                    val delayMs = BASE_DELAY_MS * 2.0.pow(attempt.toDouble()).toLong()
                    delay(delayMs)
                    lastException = Exception("HTTP 503 on attempt $attempt")
                    continue
                }

                throw Exception("HTTP $responseCode")
            }
            catch (e: Exception) {
                lastException = e
                consecutiveFailures++

                if (consecutiveFailures >= CIRCUIT_FAILURE_THRESHOLD) {
                    transitionToOpen()
                }

                val is503 = e.message?.contains("503") == true ||
                    (e.cause?.message?.contains("503") == true)

                if (is503 && attempt < maxRetries) {
                    val delayMs = BASE_DELAY_MS * 2.0.pow(attempt.toDouble()).toLong()
                    delay(delayMs)
                    continue
                }

                throw e
            }
        }

        throw lastException ?: Exception("HttpTransport send failed after ${maxRetries + 1} attempts")
    }

    protected fun getIngestEndpoint(): String = baseUrl.trimEnd('/') + INGEST_PATH
}

/**
 * Top-level external function for the current wall-clock time in
 * milliseconds since the Unix epoch. Mirrors the pattern used elsewhere
 * in this source set (e.g., `ChronoRuntimeHooks.wasmJs.kt`'s
 * `isWindowDefined`) — `js("Date.now()")` cannot be used inside an
 * inline function body in Kotlin/Wasm, so the call is hoisted to a
 * top-level `@JsFun`.
 */
@JsFun("() => Date.now()")
private external fun chronoNowMillisNative(): Double

/**
 * Circuit breaker exception — raised when the breaker is open.
 */
public class CircuitOpenException(message: String) : Exception(message)

public object HttpTransportDefaults {
    public const val DEFAULT_MAX_RETRIES: Int = 3
}

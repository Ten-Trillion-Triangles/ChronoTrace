package com.chronotrace.sdk.transport

import com.chronotrace.sdk.ChronoTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.chronotrace.contract.IngestBatch
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.pow

/**
 * JS HttpTransport — uses the native fetch API for HTTP requests.
 * Implements circuit breaker pattern and exponential backoff retry logic.
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
    internal val apiKeyValue: String get() = apiKey
    private val maxRetries: Int = maxRetries

    init {
        if (!allowInsecureBaseUrl) {
            require(baseUrl.startsWith("https://")) { "HTTPS is required for production. Base URL must use https:// scheme." }
        }
    }

    public actual open override suspend fun send(batch: IngestBatch) {
        retryableSend(batch, getIngestEndpoint())
    }

    private companion object {
        // Use kotlinx-serialization rather than the default JS `JSON.stringify`
        // because `IngestBatch` is a `@Serializable` data class. The default
        // serializer emits the IR-mangled field names (`appId_1`, `logs_1`,
        // `serialVersionUID_1`, etc.) which the server cannot parse and which
        // do not match the contract JSON shape.
        private val json = Json { encodeDefaults = true }
        private const val BASE_DELAY_MS = 100L
        private const val INGEST_PATH = "/api/v1/ingest"
        private const val CIRCUIT_FAILURE_THRESHOLD = 5
        private const val CIRCUIT_HALF_OPEN_DELAY_MS = 30_000L
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
        circuitOpenedAt = currentTimeMillis()
        consecutiveFailures = 0
    }

    private fun transitionToClosed() {
        circuitState = CircuitState.CLOSED.ordinal
        consecutiveFailures = 0
    }

    private fun transitionToHalfOpen() {
        circuitState = CircuitState.HALF_OPEN.ordinal
    }

    /**
     * Performs the actual HTTP POST. Awaits the fetch() Promise and reads
     * the resolved `Response.status` — NOT the Promise's non-existent
     * `.status` (which was the pre-Phase-2 bug).
     *
     * Implementation notes:
     * - `fetch()` returns a `Promise<Response>`. The pre-Phase-2 code was
     *   reading `.status` directly off the Promise, which is `undefined` —
     *   so the cast-to-Int yielded 0 (or threw). The fix is to `await` the
     *   Promise and read `.status` from the resolved `Response`.
     * - We use `suspendCancellableCoroutine` to bridge the JS Promise into
     *   a coroutine. On resolution, the Response is passed through to the
     *   caller; on rejection, we propagate the error so `retryableSend` can
     *   decide whether to back off and retry.
     * - The body is not consumed — we only need the status code; consuming
     *   the body would tie up the connection until the request is fully
     *   read and would prevent `retryableSend` from short-circuiting on a
     *   503 response.
     * - We never throw on non-2xx status codes: the upstream `retryableSend`
     *   is the sole owner of the 503-retry / circuit-breaker logic.
     */
    @Suppress("UNUSED_PARAMETER")
    protected open suspend fun post(endpoint: String, batch: IngestBatch): Int {
        // Use kotlinx-serialization so the JSON field names match the
        // contract (`appId`, `logs`, `sdkInstanceId`, …) — not the
        // IR-mangled names emitted by JS's default `JSON.stringify`.
        val jsonBody: String = json.encodeToString(IngestBatch.serializer(), batch)
        // Capture the apiKey class field into a local variable. Inside the
        // `js("...")` block below, only local variables and function
        // parameters are in scope — class fields are NOT resolved by the
        // Kotlin/JS compiler, so referencing `apiKey` directly would emit
        // a bare JS identifier that throws `ReferenceError: apiKey is not
        // defined` at runtime. The same is true of class members accessed
        // through `this.*` in raw JS code.
        val apiKeyLocal: String = apiKey
        val response: dynamic = suspendCancellableCoroutine { cont ->
            // Capture the Promise returned by fetch() in a local var so we
            // can attach .then / .catch to it inside the coroutine. We do
            // NOT read .status off this Promise (that was the bug) — we
            // wait for the resolved Response.
            //
            // The strings are JS template literals with `${...}` placeholders.
            // Kotlin/JS does NOT evaluate these as Kotlin string templates —
            // they are emitted verbatim as JS template literal expressions.
            // We pass endpoint, apiKey, and jsonBody as local Kotlin vars
            // that get resolved by the JS runtime from this function's
            // closure scope.
            val promise: dynamic = js(
                "(function(url, key, body) { return fetch(url, { method: 'POST', " +
                    "headers: { 'Content-Type': 'application/json', 'X-Api-Key': key }, " +
                    "body: body }); })(endpoint, apiKeyLocal, jsonBody)"
            )
            promise.then(
                { resolved: dynamic -> cont.resume(resolved) },
                { rejected: dynamic ->
                    cont.resumeWithException(
                        Exception("fetch() rejected: " + (rejected?.toString() ?: "unknown"))
                    )
                    null
                },
            )
        }
        return response.status as Int
    }

    protected suspend fun retryableSend(batch: IngestBatch, endpoint: String) {
        val currentState = CircuitState.entries[circuitState]

        if (currentState == CircuitState.OPEN) {
            val timeSinceOpen = currentTimeMillis() - circuitOpenedAt
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
                    // 503 counts as a consecutive failure for circuit-breaker
                    // purposes. Without this, the OPEN transition never
                    // fires for sustained 503s (only thrown exceptions trip
                    // it). Mirrors the JVM variant's behaviour.
                    consecutiveFailures++
                    if (consecutiveFailures >= CIRCUIT_FAILURE_THRESHOLD) {
                        transitionToOpen()
                    }
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

    @Suppress("NOTHING_TO_INLINE")
    private inline fun currentTimeMillis(): Long = js("Date.now()") as Long
}

public class CircuitOpenException(message: String) : Exception(message)

public object HttpTransportDefaults {
    public const val DEFAULT_MAX_RETRIES: Int = 3
}

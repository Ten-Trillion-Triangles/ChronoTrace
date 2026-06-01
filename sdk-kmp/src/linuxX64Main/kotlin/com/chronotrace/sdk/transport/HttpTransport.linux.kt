package com.chronotrace.sdk.transport

import com.chronotrace.sdk.ChronoTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import org.chronotrace.contract.IngestBatch
import kotlin.math.pow
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
internal actual open class HttpTransport
public actual constructor(
    baseUrl: String,
    apiKey: String,
    maxRetries: Int,
    allowInsecureBaseUrl: Boolean,
) : ChronoTransport {

    protected val baseUrl: String = baseUrl
    private val apiKey: String = apiKey
    private val maxRetries: Int = maxRetries

    init {
        if (!allowInsecureBaseUrl) {
            require(baseUrl.startsWith("https://")) { "HTTPS is required for production. Base URL must use https:// scheme." }
        }
    }

    public actual open override suspend fun send(batch: IngestBatch) {
        retryableSend(batch, getIngestEndpoint())
    }

    private enum class CircuitState { CLOSED, OPEN, HALF_OPEN }

    // Simple mutable state - Kotlin/Native is single-threaded per isolate
    private var circuitState: CircuitState = CircuitState.CLOSED
    private var circuitOpenedAt: Long = 0L
    private var consecutiveFailures: Int = 0

    private val httpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 10_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 10_000
        }
    }

    private fun transitionToOpen() {
        circuitState = CircuitState.OPEN
        circuitOpenedAt = monotonicTimeMillis()
        consecutiveFailures = 0
    }

    private fun transitionToClosed() {
        circuitState = CircuitState.CLOSED
        consecutiveFailures = 0
    }

    private fun transitionToHalfOpen() {
        circuitState = CircuitState.HALF_OPEN
    }

    // Real wall-clock time for circuit breaker timing
    private fun monotonicTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

    /**
     * Closes the underlying [HttpClient], releasing the socket file descriptor
     * and any pooled connections. Without this, every [HttpTransport] instance
     * leaks one FD at process exit (Ktor's CIO engine is single-shot and
     * requires explicit [HttpClient.close] to free native resources).
     *
     * Safe to call multiple times; subsequent calls are no-ops. The
     * `ChronoRuntimeHooks` JVM shutdown hook handles this for the JVM target;
     * Native targets must call it explicitly (or rely on `atexit` if
     * registered in [com.chronotrace.sdk.ChronoRuntimeHooks.nativeMain]).
     */
    fun close() {
        httpClient.close()
    }

    protected suspend fun post(endpoint: String, batch: IngestBatch): Int {
        val response: HttpResponse = httpClient.post(endpoint) {
            contentType(ContentType.Application.Json)
            setBody(kotlinx.serialization.json.Json.encodeToString(batch))
            headers.append("X-Api-Key", apiKey)
        }
        return response.status.value
    }

    protected suspend fun retryableSend(batch: IngestBatch, endpoint: String) {
        val currentState = circuitState
        val openedAt = circuitOpenedAt

        if (currentState == CircuitState.OPEN) {
            val timeSinceOpen = monotonicTimeMillis() - openedAt
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

                if (responseCode == HttpStatusCode.OK.value ||
                    responseCode == HttpStatusCode.Created.value) {
                    if (currentState == CircuitState.HALF_OPEN) {
                        transitionToClosed()
                    }
                    consecutiveFailures = 0
                    return
                }

                if (responseCode == HttpStatusCode.ServiceUnavailable.value) {
                    consecutiveFailures++
                    if (consecutiveFailures >= CIRCUIT_FAILURE_THRESHOLD) {
                        transitionToOpen()
                    }
                    if (attempt < maxRetries) {
                        val delayMs = BASE_DELAY_MS * 2.0.pow(attempt.toDouble()).toLong()
                        delay(delayMs)
                        lastException = Exception("HTTP 503 on attempt $attempt")
                        continue
                    }
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

    private companion object HttpTransportConstants {
        private const val BASE_DELAY_MS = 100L
        private const val INGEST_PATH = "/api/v1/ingest"
        private const val CIRCUIT_FAILURE_THRESHOLD = 5
        private const val CIRCUIT_HALF_OPEN_DELAY_MS = 30_000L
    }
}

public class CircuitOpenException(message: String) : Exception(message)

public object HttpTransportDefaults {
    public const val DEFAULT_MAX_RETRIES: Int = 3
}

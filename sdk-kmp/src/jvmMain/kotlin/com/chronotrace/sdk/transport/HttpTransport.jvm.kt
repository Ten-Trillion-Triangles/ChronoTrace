package com.chronotrace.sdk.transport

import com.chronotrace.sdk.ChronoTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import org.chronotrace.contract.IngestBatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.pow

internal actual abstract class HttpTransport
public actual constructor(
    baseUrl: String,
    apiKey: String,
    maxRetries: Int,
) : ChronoTransport {

    protected val baseUrl: String = baseUrl
    private val apiKey: String = apiKey
    private val maxRetries: Int = maxRetries

    public actual abstract override suspend fun send(batch: IngestBatch)

    private companion object {
        private const val BASE_DELAY_MS = 100L
        private const val INGEST_PATH = "/api/v1/ingest"
        private const val CIRCUIT_FAILURE_THRESHOLD = 5
        private const val CIRCUIT_HALF_OPEN_DELAY_MS = 30_000L
    }

    private enum class CircuitState { CLOSED, OPEN, HALF_OPEN }

    private val circuitState = AtomicInteger(CircuitState.CLOSED.ordinal)
    private val circuitOpenedAt = AtomicLong(0L)
    private val consecutiveFailures = AtomicInteger(0)

    private val connectionPool = ConnectionPool(5, 30, TimeUnit.SECONDS)

    private val okHttpClient = OkHttpClient.Builder()
        .connectionPool(connectionPool)
        .retryOnConnectionFailure(false)
        .build()

    private val httpClient = HttpClient(OkHttp) {
        engine {
            preconfigured = okHttpClient
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 10_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 10_000
        }
    }

    private fun transitionToOpen() {
        circuitState.set(CircuitState.OPEN.ordinal)
        circuitOpenedAt.set(System.currentTimeMillis())
        consecutiveFailures.set(0)
    }

    private fun transitionToClosed() {
        circuitState.set(CircuitState.CLOSED.ordinal)
        consecutiveFailures.set(0)
    }

    private fun transitionToHalfOpen() {
        circuitState.set(CircuitState.HALF_OPEN.ordinal)
    }

    protected suspend fun post(endpoint: String, batch: IngestBatch): Int {
        return withContext(Dispatchers.IO) {
            val response: HttpResponse = httpClient.post(endpoint) {
                contentType(ContentType.Application.Json)
                setBody(kotlinx.serialization.json.Json.encodeToString(batch))
                headers.append("X-Api-Key", apiKey)
            }
            response.status.value
        }
    }

    protected suspend fun retryableSend(batch: IngestBatch, endpoint: String) {
        val currentState = CircuitState.entries[circuitState.get()]

        if (currentState == CircuitState.OPEN) {
            val timeSinceOpen = System.currentTimeMillis() - circuitOpenedAt.get()
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
                    consecutiveFailures.set(0)
                    return
                }

                if (responseCode == HttpStatusCode.ServiceUnavailable.value && attempt < maxRetries) {
                    val delayMs = BASE_DELAY_MS * 2.0.pow(attempt.toDouble()).toLong()
                    kotlinx.coroutines.delay(delayMs)
                    lastException = Exception("HTTP 503 on attempt $attempt")
                    continue
                }

                throw Exception("HTTP $responseCode")
            }
            catch (e: Exception) {
                lastException = e
                consecutiveFailures.incrementAndGet()

                if (consecutiveFailures.get() >= CIRCUIT_FAILURE_THRESHOLD) {
                    transitionToOpen()
                }

                val is503 = e.message?.contains("503") == true ||
                    (e.cause?.message?.contains("503") == true)

                if (is503 && attempt < maxRetries) {
                    val delayMs = BASE_DELAY_MS * 2.0.pow(attempt.toDouble()).toLong()
                    kotlinx.coroutines.delay(delayMs)
                    continue
                }

                throw e
            }
        }

        throw lastException ?: Exception("HttpTransport send failed after ${maxRetries + 1} attempts")
    }

    protected fun getIngestEndpoint(): String = baseUrl.trimEnd('/') + INGEST_PATH
}

public class CircuitOpenException(message: String) : Exception(message)

public object HttpTransportDefaults {
    public const val DEFAULT_MAX_RETRIES: Int = 3
}
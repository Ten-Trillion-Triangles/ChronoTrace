package com.chronotrace.sdk.transport

import com.chronotrace.sdk.ChronoTransport
import org.chronotrace.contract.IngestBatch

/**
 * JS HttpTransport — network operations in JavaScript require a proper HTTP client.
 * This stub performs no network calls and discards all batches silently at runtime.
 */
internal actual abstract class HttpTransport
internal actual constructor(
    baseUrl: String,
    apiKey: String,
    maxRetries: Int,
) : ChronoTransport {

    public actual abstract override suspend fun send(batch: IngestBatch)
}

package com.chronotrace.sdk.transport

import com.chronotrace.sdk.ChronoTransport
import org.chronotrace.contract.IngestBatch

internal expect abstract class HttpTransport(
    baseUrl: String,
    apiKey: String,
    maxRetries: Int = 3,
) : ChronoTransport {
    abstract override suspend fun send(batch: IngestBatch)
}

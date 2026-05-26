package com.chronotrace.sdk.transport

import com.chronotrace.sdk.ChronoTransport
import org.chronotrace.contract.IngestBatch

/**
 * WasmJs HttpTransport — browser/network operations are not available in Kotlin/Wasm.
 * Network calls require a full HTTP client library with Kotlin/Wasm support (e.g. ktor-client-wasm).
 * This stub performs no network calls and discards all batches silently at runtime.
 */
public actual abstract class HttpTransport
actual constructor(
    baseUrl: String,
    apiKey: String,
    maxRetries: Int,
) : ChronoTransport {

    public actual abstract override suspend fun send(batch: IngestBatch)
}
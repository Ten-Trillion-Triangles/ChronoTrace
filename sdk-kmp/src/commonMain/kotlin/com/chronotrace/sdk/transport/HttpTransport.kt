package com.chronotrace.sdk.transport

import com.chronotrace.sdk.ChronoTransport
import org.chronotrace.contract.IngestBatch

/**
 * @param allowInsecureBaseUrl DEV-ONLY escape hatch. When `true`, [HttpTransport] will
 *   accept a `baseUrl` that does not use `https://`. **Never set this to `true` in
 *   production**: production deploys must use TLS, and an attacker who can MITM the
 *   plaintext channel can both read log payloads and inject forged telemetry.
 */
internal expect open class HttpTransport(
    baseUrl: String,
    apiKey: String,
    maxRetries: Int = 3,
    allowInsecureBaseUrl: Boolean = false,
) : ChronoTransport {
    open override suspend fun send(batch: IngestBatch)
}

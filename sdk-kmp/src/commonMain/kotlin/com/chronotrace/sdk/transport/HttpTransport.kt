package com.chronotrace.sdk.transport

import com.chronotrace.sdk.ChronoTransport

internal expect class HttpTransport(
    baseUrl: String,
    apiKey: String,
    maxRetries: Int,
) : ChronoTransport

expect val DEFAULT_MAX_RETRIES: Int
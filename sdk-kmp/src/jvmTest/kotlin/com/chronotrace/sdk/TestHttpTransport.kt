package com.chronotrace.sdk

import com.chronotrace.sdk.transport.HttpTransport
import org.chronotrace.contract.IngestBatch
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit

/**
 * TestHttpTransport wraps MockWebServer to provide a testable HttpTransport.
 * This allows tests to create an HttpTransport where send() actually posts to the mock server.
 */
internal class TestHttpTransport(
    baseUrl: String,
    apiKey: String,
    maxRetries: Int = 3,
    allowInsecureBaseUrl: Boolean = true,
) : HttpTransport(baseUrl, apiKey, maxRetries, allowInsecureBaseUrl) {

    override suspend fun send(batch: IngestBatch) {
        retryableSend(batch, baseUrl + "api/v1/ingest")
    }
}

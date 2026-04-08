package org.chronotrace.contract

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class ChronoContractsTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun searchResponseIsSerializable() {
        val payload = SearchLogsResponse(
            items = listOf(
                LogRecord(
                    logId = "log-1",
                    appId = "payments",
                    environment = "local",
                    sdkInstanceId = "sdk-1",
                    serviceName = "payments",
                    timestampUtc = 1L,
                    sequenceId = 2L,
                    level = LogLevel.INFO,
                    message = "hello",
                ),
            ),
        )

        val encoded = json.encodeToString(SearchLogsResponse.serializer(), payload)

        assertTrue(encoded.contains("payments"))
    }
}


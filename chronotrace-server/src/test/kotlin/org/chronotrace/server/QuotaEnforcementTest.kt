package org.chronotrace.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.chronotrace.contract.ClientMetadata
import org.chronotrace.contract.IngestBatch
import org.chronotrace.contract.LogLevel
import org.chronotrace.contract.LogRecord

/**
 * Integration tests for per-key quota enforcement on ChronoTrace server endpoints.
 *
 * Phase 6: auth hardening. These tests verify that API keys with configured quotas
 * are rate-limited — requests exceeding the quota window receive 429 Too Many Requests.
 */
class QuotaEnforcementTest {
    private val json = Json { encodeDefaults = true }

    // -----------------------------------------------------------------------
    // Quota exceeded — 429 response
    // -----------------------------------------------------------------------

    @Test
    fun `quota exceeded returns 429 on ingest`() = testApplication {
        val store = ChronoStore("apiKey", ChronoStoreOptions(
            apiKeys = setOf("key-with-limit"),
            // Key "key-with-limit" has a quota: 2 requests per 60 seconds
            keyMetadata = mapOf("key-with-limit" to ApiKeyMetadata(
                keyId = "key-with-limit",
                createdAtUtc = Instant.now().toEpochMilli() - 86_400_000, // 24h ago
                quota = ApiKeyQuota(limit = 2, windowSeconds = 60),
            )),
        ))
        application { chronoTraceModule(store) }

        // Two requests within the window — both accepted
        val first = client.post("/api/v1/ingest") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "key-with-limit")
            setBody(json.encodeToString(emptyIngest("app-quota-test")))
        }
        assertEquals(HttpStatusCode.OK, first.status)

        val second = client.post("/api/v1/ingest") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "key-with-limit")
            setBody(json.encodeToString(emptyIngest("app-quota-test")))
        }
        assertEquals(HttpStatusCode.OK, second.status)

        // Third request — quota exhausted, expect 429
        val third = client.post("/api/v1/ingest") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "key-with-limit")
            setBody(json.encodeToString(emptyIngest("app-quota-test")))
        }
        assertEquals(HttpStatusCode.TooManyRequests, third.status)
        assertTrue(third.bodyAsText().contains("quota"))
    }

    @Test
    fun `quota exceeded returns 429 on search`() = testApplication {
        val store = ChronoStore("apiKey", ChronoStoreOptions(
            apiKeys = setOf("search-key"),
            keyMetadata = mapOf("search-key" to ApiKeyMetadata(
                keyId = "search-key",
                createdAtUtc = Instant.now().toEpochMilli(),
                quota = ApiKeyQuota(limit = 1, windowSeconds = 60),
            )),
        ))
        application { chronoTraceModule(store) }

        val first = client.post("/api/v1/logs/search") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "search-key")
            setBody(json.encodeToString(emptySearchRequest()))
        }
        assertEquals(HttpStatusCode.OK, first.status)

        val second = client.post("/api/v1/logs/search") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "search-key")
            setBody(json.encodeToString(emptySearchRequest()))
        }
        assertEquals(HttpStatusCode.TooManyRequests, second.status)
    }

    @Test
    fun `quota exceeded returns 429 on MCP endpoint`() = testApplication {
        val store = ChronoStore("apiKey", ChronoStoreOptions(
            apiKeys = setOf("mcp-key"),
            keyMetadata = mapOf("mcp-key" to ApiKeyMetadata(
                keyId = "mcp-key",
                createdAtUtc = Instant.now().toEpochMilli(),
                quota = ApiKeyQuota(limit = 1, windowSeconds = 60),
            )),
        ))
        application { chronoTraceModule(store) }

        val first = client.post("/mcp") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "mcp-key")
            setBody("""{"id":"1","method":"tools/list","params":{}}""")
        }
        assertEquals(HttpStatusCode.OK, first.status)

        val second = client.post("/mcp") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "mcp-key")
            setBody("""{"id":"2","method":"tools/list","params":{}}""")
        }
        assertEquals(HttpStatusCode.TooManyRequests, second.status)
    }

    // -----------------------------------------------------------------------
    // Unquotaed keys pass through normally
    // -----------------------------------------------------------------------

    @Test
    fun `key without quota is not rate limited`() = testApplication {
        val store = ChronoStore("apiKey", ChronoStoreOptions(
            apiKeys = setOf("unlimited-key"),
            // No keyMetadata entry means no quota limit
        ))
        application { chronoTraceModule(store) }

        // 100 requests — all should succeed
        repeat(100) {
            val response = client.post("/api/v1/ingest") {
                contentType(ContentType.Application.Json)
                header("X-Api-Key", "unlimited-key")
                setBody(json.encodeToString(emptyIngest("app-unlimited")))
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    // -----------------------------------------------------------------------
    // Quota window resets after windowSeconds
    // -----------------------------------------------------------------------

    @Test
    fun `quota resets after window expires`() = testApplication {
        val store = ChronoStore("apiKey", ChronoStoreOptions(
            apiKeys = setOf("windowed-key"),
            keyMetadata = mapOf("windowed-key" to ApiKeyMetadata(
                keyId = "windowed-key",
                createdAtUtc = Instant.now().toEpochMilli(),
                quota = ApiKeyQuota(limit = 1, windowSeconds = 2), // 2-second window
            )),
        ))
        application { chronoTraceModule(store) }

        val first = client.post("/api/v1/ingest") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "windowed-key")
            setBody(json.encodeToString(emptyIngest("app-windowed")))
        }
        assertEquals(HttpStatusCode.OK, first.status)

        // Immediate second — should be blocked
        val second = client.post("/api/v1/ingest") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "windowed-key")
            setBody(json.encodeToString(emptyIngest("app-windowed")))
        }
        assertEquals(HttpStatusCode.TooManyRequests, second.status)

        // Sleep past the 2-second window and retry
        Thread.sleep(2_500)

        val afterReset = client.post("/api/v1/ingest") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "windowed-key")
            setBody(json.encodeToString(emptyIngest("app-windowed")))
        }
        assertEquals(HttpStatusCode.OK, afterReset.status)
    }

    // -----------------------------------------------------------------------
    // Quota headers in 429 response
    // -----------------------------------------------------------------------

    @Test
    fun `quota exceeded response includes Retry-After header`() = testApplication {
        val store = ChronoStore("apiKey", ChronoStoreOptions(
            apiKeys = setOf("retry-after-key"),
            keyMetadata = mapOf("retry-after-key" to ApiKeyMetadata(
                keyId = "retry-after-key",
                createdAtUtc = Instant.now().toEpochMilli(),
                quota = ApiKeyQuota(limit = 1, windowSeconds = 60),
            )),
        ))
        application { chronoTraceModule(store) }

        // Use up the quota
        val first = client.post("/api/v1/ingest") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "retry-after-key")
            setBody(json.encodeToString(emptyIngest("app-retry")))
        }
        assertEquals(HttpStatusCode.OK, first.status)

        val blocked = client.post("/api/v1/ingest") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "retry-after-key")
            setBody(json.encodeToString(emptyIngest("app-retry")))
        }
        assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
        val retryAfter = blocked.headers["Retry-After"]
        assertTrue(retryAfter != null && retryAfter.toInt() in 1..60)
    }

    @Test
    fun `quota exceeded response includes X-RateLimit headers`() = testApplication {
        val store = ChronoStore("apiKey", ChronoStoreOptions(
            apiKeys = setOf("ratelimit-header-key"),
            keyMetadata = mapOf("ratelimit-header-key" to ApiKeyMetadata(
                keyId = "ratelimit-header-key",
                createdAtUtc = Instant.now().toEpochMilli(),
                quota = ApiKeyQuota(limit = 5, windowSeconds = 120),
            )),
        ))
        application { chronoTraceModule(store) }

        repeat(5) {
            client.post("/api/v1/ingest") {
                contentType(ContentType.Application.Json)
                header("X-Api-Key", "ratelimit-header-key")
                setBody(json.encodeToString(emptyIngest("app-rh")))
            }
        }

        val blocked = client.post("/api/v1/ingest") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "ratelimit-header-key")
            setBody(json.encodeToString(emptyIngest("app-rh")))
        }
        assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
        assertEquals("5", blocked.headers["X-RateLimit-Limit"])
        assertEquals("0", blocked.headers["X-RateLimit-Remaining"])
        assertTrue(blocked.headers["X-RateLimit-Window"] != null)
    }

    // -----------------------------------------------------------------------
    // Health endpoint bypasses quota check (always accessible)
    // -----------------------------------------------------------------------

    @Test
    fun `health endpoint ignores quota — always accessible`() = testApplication {
        val store = ChronoStore("apiKey", ChronoStoreOptions(
            apiKeys = setOf("quota-key"),
            keyMetadata = mapOf("quota-key" to ApiKeyMetadata(
                keyId = "quota-key",
                createdAtUtc = Instant.now().toEpochMilli(),
                quota = ApiKeyQuota(limit = 1, windowSeconds = 60),
            )),
        ))
        application { chronoTraceModule(store) }

        // Exhaust quota on ingest
        repeat(2) {
            client.post("/api/v1/ingest") {
                contentType(ContentType.Application.Json)
                header("X-Api-Key", "quota-key")
                setBody(json.encodeToString(emptyIngest("app-health")))
            }
        }

        // Health should still work even when quota is exhausted
        val health = client.get("/health") {
            header("X-Api-Key", "quota-key")
        }
        assertEquals(HttpStatusCode.OK, health.status)
    }

    // -----------------------------------------------------------------------
    // 429 body shape
    // -----------------------------------------------------------------------

    @Test
    fun `quota exceeded body includes error code and retry info`() = testApplication {
        val store = ChronoStore("apiKey", ChronoStoreOptions(
            apiKeys = setOf("body-check-key"),
            keyMetadata = mapOf("body-check-key" to ApiKeyMetadata(
                keyId = "body-check-key",
                createdAtUtc = Instant.now().toEpochMilli(),
                quota = ApiKeyQuota(limit = 1, windowSeconds = 60),
            )),
        ))
        application { chronoTraceModule(store) }

        client.post("/api/v1/ingest") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "body-check-key")
            setBody(json.encodeToString(emptyIngest("app-body")))
        }

        val blocked = client.post("/api/v1/ingest") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "body-check-key")
            setBody(json.encodeToString(emptyIngest("app-body")))
        }
        assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
        val body = blocked.bodyAsText()
        assertTrue(body.contains("quota") || body.contains("rate_limit"), "body: $body")
        assertTrue(body.contains("retry") || body.contains("window"), "body: $body")
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private fun emptyIngest(appId: String = "test") = IngestBatch(
        client = ClientMetadata(
            appId = appId,
            environment = "test",
            sdkInstanceId = "sdk-1",
            serviceName = "test",
        ),
        logs = listOf(
            LogRecord(
                logId = "log-quota-test",
                appId = appId,
                environment = "test",
                sdkInstanceId = "sdk-1",
                serviceName = "test",
                traceId = "trace-quota",
                spanId = "span-quota",
                timestampUtc = Instant.now().toEpochMilli(),
                sequenceId = 1L,
                level = LogLevel.INFO,
                message = "quota test",
            ),
        ),
        spans = emptyList(),
        frameSnapshots = emptyList(),
    )

    private fun emptySearchRequest() = org.chronotrace.contract.SearchLogsRequest(appId = "test")
}
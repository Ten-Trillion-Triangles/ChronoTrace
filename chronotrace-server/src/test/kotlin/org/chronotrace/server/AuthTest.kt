package org.chronotrace.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.chronotrace.contract.ClientMetadata
import org.chronotrace.contract.IngestBatch
import org.chronotrace.contract.LogLevel
import org.chronotrace.contract.LogRecord
import org.chronotrace.contract.SearchLogsRequest

/**
 * Integration tests for authentication enforcement on server endpoints.
 * Tests apiKey, bearer, and none auth modes.
 */
class AuthTest {
    private val json = Json { encodeDefaults = true }

    // ---------------------------------------------------------------------------
    // apiKey auth mode tests
    // ---------------------------------------------------------------------------

    @Test
    fun `apiKey mode rejects request without X-API-Key header`() = testApplication {
        application {
            chronoTraceModule(ChronoStore("apiKey", ChronoStoreOptions(
                apiKeys = setOf("valid-key-123"),
            )))
        }

        val response = client.post("/api/v1/ingest") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(emptyIngest()))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(response.bodyAsText().contains("Unauthorized"))
    }

    @Test
    fun `apiKey mode rejects request with invalid X-API-Key header`() = testApplication {
        application {
            chronoTraceModule(ChronoStore("apiKey", ChronoStoreOptions(
                apiKeys = setOf("valid-key-123"),
            )))
        }

        val response = client.post("/api/v1/ingest") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "wrong-key")
            setBody(json.encodeToString(emptyIngest()))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `apiKey mode accepts request with valid X-API-Key header`() = testApplication {
        application {
            chronoTraceModule(ChronoStore("apiKey", ChronoStoreOptions(
                apiKeys = setOf("valid-key-123"),
            )))
        }

        val response = client.post("/api/v1/ingest") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "valid-key-123")
            setBody(json.encodeToString(emptyIngest()))
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `apiKey mode applies to query endpoints`() = testApplication {
        application {
            chronoTraceModule(ChronoStore("apiKey", ChronoStoreOptions(
                apiKeys = setOf("valid-key-123"),
            )))
        }

        val response = client.post("/api/v1/logs/search") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "valid-key-123")
            setBody(json.encodeToString(SearchLogsRequest()))
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `apiKey mode applies to trace and frame endpoints`() = testApplication {
        application {
            chronoTraceModule(ChronoStore("apiKey", ChronoStoreOptions(
                apiKeys = setOf("valid-key-123"),
            )))
        }

        val frameResponse = client.get("/api/v1/frames/nonexistent") {
            header("X-Api-Key", "valid-key-123")
        }
        assertEquals(HttpStatusCode.OK, frameResponse.status)

        val traceResponse = client.get("/api/v1/traces/nonexistent") {
            header("X-Api-Key", "valid-key-123")
        }
        assertEquals(HttpStatusCode.OK, traceResponse.status)
    }

    // ---------------------------------------------------------------------------
    // bearer auth mode tests
    // ---------------------------------------------------------------------------

    @Test
    fun `bearer mode rejects request without Authorization header`() = testApplication {
        application {
            chronoTraceModule(ChronoStore("bearer", ChronoStoreOptions(
                bearerToken = "secret-token-456",
            )))
        }

        val response = client.post("/api/v1/ingest") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(emptyIngest()))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `bearer mode rejects request with wrong Bearer token`() = testApplication {
        application {
            chronoTraceModule(ChronoStore("bearer", ChronoStoreOptions(
                bearerToken = "secret-token-456",
            )))
        }

        val response = client.post("/api/v1/ingest") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer wrong-token")
            setBody(json.encodeToString(emptyIngest()))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `bearer mode rejects request with wrong auth scheme`() = testApplication {
        application {
            chronoTraceModule(ChronoStore("bearer", ChronoStoreOptions(
                bearerToken = "secret-token-456",
            )))
        }

        val response = client.post("/api/v1/ingest") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Basic dXNlcjpwYXNz")
            setBody(json.encodeToString(emptyIngest()))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `bearer mode accepts request with valid Bearer token`() = testApplication {
        application {
            chronoTraceModule(ChronoStore("bearer", ChronoStoreOptions(
                bearerToken = "secret-token-456",
            )))
        }

        val response = client.post("/api/v1/ingest") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer secret-token-456")
            setBody(json.encodeToString(emptyIngest()))
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `bearer mode applies to query endpoints`() = testApplication {
        application {
            chronoTraceModule(ChronoStore("bearer", ChronoStoreOptions(
                bearerToken = "secret-token-456",
            )))
        }

        val response = client.post("/api/v1/logs/search") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer secret-token-456")
            setBody(json.encodeToString(SearchLogsRequest()))
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    // ---------------------------------------------------------------------------
    // none auth mode tests
    // ---------------------------------------------------------------------------

    @Test
    fun `none mode allows all requests without credentials`() = testApplication {
        application {
            chronoTraceModule(ChronoStore("none"))
        }

        val ingestResponse = client.post("/api/v1/ingest") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(emptyIngest()))
        }
        assertEquals(HttpStatusCode.OK, ingestResponse.status)

        val searchResponse = client.post("/api/v1/logs/search") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(SearchLogsRequest()))
        }
        assertEquals(HttpStatusCode.OK, searchResponse.status)
    }

    @Test
    fun `none mode still accepts credentials if provided`() = testApplication {
        application {
            chronoTraceModule(ChronoStore("none"))
        }

        val response = client.post("/api/v1/ingest") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "any-key")
            header(HttpHeaders.Authorization, "Bearer any-token")
            setBody(json.encodeToString(emptyIngest()))
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    // ---------------------------------------------------------------------------
    // health endpoint - always accessible
    // ---------------------------------------------------------------------------

    @Test
    fun `health endpoint is always accessible regardless of auth mode`() = testApplication {
        application {
            chronoTraceModule(ChronoStore("apiKey", ChronoStoreOptions(
                apiKeys = setOf("valid-key-123"),
            )))
        }

        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("authMode"))
        assertTrue(response.bodyAsText().contains("apiKey"))
    }

    // ---------------------------------------------------------------------------
    // MCP endpoint auth
    // ---------------------------------------------------------------------------

    @Test
    fun `apiKey mode protects MCP endpoint`() = testApplication {
        application {
            chronoTraceModule(ChronoStore("apiKey", ChronoStoreOptions(
                apiKeys = setOf("valid-key-123"),
            )))
        }

        val response = client.post("/mcp") {
            contentType(ContentType.Application.Json)
            setBody("""{"id":"1","method":"tools/list","params":{}}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `bearer mode protects MCP endpoint`() = testApplication {
        application {
            chronoTraceModule(ChronoStore("bearer", ChronoStoreOptions(
                bearerToken = "secret-token-456",
            )))
        }

        val response = client.post("/mcp") {
            contentType(ContentType.Application.Json)
            setBody("""{"id":"1","method":"tools/list","params":{}}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ---------------------------------------------------------------------------
    // end-to-end ingest with real data
    // ---------------------------------------------------------------------------

    @Test
    fun `apiKey auth end-to-end ingest and query`() = testApplication {
        application {
            chronoTraceModule(ChronoStore("apiKey", ChronoStoreOptions(
                apiKeys = setOf("test-key"),
            )))
        }

        val now = Instant.now().toEpochMilli()
        val batch = IngestBatch(
            client = ClientMetadata(
                appId = "test-app",
                environment = "test",
                sdkInstanceId = "sdk-1",
                serviceName = "test-svc",
            ),
            logs = listOf(
                LogRecord(
                    logId = "log-auth-test",
                    appId = "test-app",
                    environment = "test",
                    sdkInstanceId = "sdk-1",
                    serviceName = "test-svc",
                    traceId = "trace-auth",
                    spanId = "span-auth",
                    timestampUtc = now,
                    sequenceId = 1L,
                    level = LogLevel.INFO,
                    message = "auth test message",
                ),
            ),
            spans = emptyList(),
            frameSnapshots = emptyList(),
        )

        val ingest = client.post("/api/v1/ingest") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "test-key")
            setBody(json.encodeToString(batch))
        }
        assertEquals(HttpStatusCode.OK, ingest.status)

        val search = client.post("/api/v1/logs/search") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "test-key")
            setBody(json.encodeToString(SearchLogsRequest(appId = "test-app")))
        }
        assertEquals(HttpStatusCode.OK, search.status)
        assertTrue(search.bodyAsText().contains("auth test message"))
    }

    // ---------------------------------------------------------------------------
    // helper
    // ---------------------------------------------------------------------------

    private fun emptyIngest() = IngestBatch(
        client = ClientMetadata(
            appId = "test",
            environment = "test",
            sdkInstanceId = "sdk-1",
            serviceName = "test",
        ),
        logs = emptyList(),
        spans = emptyList(),
        frameSnapshots = emptyList(),
    )
}

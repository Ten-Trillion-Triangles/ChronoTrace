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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.chronotrace.contract.ClientMetadata
import org.chronotrace.contract.IngestBatch
import org.chronotrace.contract.LogLevel
import org.chronotrace.contract.LogRecord

/**
 * Integration tests for audit logging on ChronoTrace server endpoints.
 *
 * Phase 6: auth hardening. Every request to a protected endpoint must be
 * recorded in the audit log with the key identity, action, outcome, and timestamp.
 *
 * Audit logs are stored in ClickHouse (shared mode) and read back via the
 * /api/v1/admin/audit/logs endpoint for operator review.
 */
class AuditLoggingTest {
    private val json = Json { encodeDefaults = true; prettyPrint = true }

    // -----------------------------------------------------------------------
    // All protected endpoints are audited
    // -----------------------------------------------------------------------

    @Test
    fun `ingest endpoint is audited with key identity`() = testApplication {
        val store = ChronoStore("apiKey", ChronoStoreOptions(
            apiKeys = setOf("audit-test-key"),
            keyMetadata = mapOf(
                "audit-test-key" to ApiKeyMetadata(
                    keyId = "audit-test-key",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "admin",
                ),
            ),
        ))
        application { chronoTraceModule(store) }

        val before = Instant.now().toEpochMilli()

        val response = client.post("/api/v1/ingest") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "audit-test-key")
            setBody(json.encodeToString(emptyIngest("app-audit-ingest")))
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val after = Instant.now().toEpochMilli()

        // Query audit log — requires admin key
        val auditResponse = client.get("/api/v1/admin/audit/logs") {
            header("X-Api-Key", "audit-test-key")
        }
        assertEquals(HttpStatusCode.OK, auditResponse.status)

        val auditJson = json.parseToJsonElement(auditResponse.bodyAsText())
        val entries = auditJson.jsonObject.getValue("entries").jsonArray
            ?: throw AssertionError("No entries in audit response")
        assertTrue(entries.size >= 1, "Expected at least 1 audit entry, got ${entries.size}")

        val ingestEntry = entries.find {
            it.jsonObject["action"]?.jsonPrimitive?.content == "ingest"
        }
        assertTrue(ingestEntry != null, "No audit entry for ingest action")
        val entry = ingestEntry.jsonObject

        assertEquals("audit-test-key", entry["apiKeyId"]?.jsonPrimitive?.content)
        assertEquals("/api/v1/ingest", entry["endpoint"]?.jsonPrimitive?.content)
        assertEquals("success", entry["outcome"]?.jsonPrimitive?.content)
        val timestamp = entry["timestampUtc"]?.jsonPrimitive?.content?.toLongOrNull()
        assertTrue(timestamp != null && timestamp in before..after)
    }

    @Test
    fun `search endpoint is audited`() = testApplication {
        val store = ChronoStore("apiKey", ChronoStoreOptions(
            apiKeys = setOf("audit-search-key"),
            keyMetadata = mapOf(
                "audit-search-key" to ApiKeyMetadata(
                    keyId = "audit-search-key",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "admin",
                ),
            ),
        ))
        application { chronoTraceModule(store) }

        // Ingest first
        client.post("/api/v1/ingest") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "audit-search-key")
            setBody(json.encodeToString(emptyIngest("app-audit-search")))
        }

        // Call search endpoint (POST with empty query)
        client.post("/api/v1/logs/search") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "audit-search-key")
            setBody("""{"appId":"app-audit-search"}""")
        }

        val auditResponse = client.get("/api/v1/admin/audit/logs") {
            header("X-Api-Key", "audit-search-key")
        }
        assertEquals(HttpStatusCode.OK, auditResponse.status)

        val entries = json.parseToJsonElement(auditResponse.bodyAsText()).jsonObject.getValue("entries").jsonArray
            ?: throw AssertionError("No entries in audit response")
        val searchEntry = entries.find {
            it.jsonObject["action"]?.jsonPrimitive?.content == "search"
        }
        assertTrue(searchEntry != null, "No audit entry for search action")
    }

    @Test
    fun `remote-rules POST is audited`() = testApplication {
        val store = ChronoStore("apiKey", ChronoStoreOptions(
            apiKeys = setOf("audit-rules-key"),
            keyMetadata = mapOf(
                "audit-rules-key" to ApiKeyMetadata(
                    keyId = "audit-rules-key",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "admin",
                ),
            ),
        ))
        application { chronoTraceModule(store) }

        val ruleJson = """{
            "ruleId": "audit-test-rule",
            "targetApps": ["app-audit-rules"],
            "ttlSeconds": 300,
            "priority": 0,
            "expression": "level == ERROR",
            "createdBy": "audit-test"
        }""".trimMargin()

        val response = client.post("/api/v1/remote-rules") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "audit-rules-key")
            setBody(ruleJson)
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val auditResponse = client.get("/api/v1/admin/audit/logs") {
            header("X-Api-Key", "audit-rules-key")
        }

        val entries = json.parseToJsonElement(auditResponse.bodyAsText()).jsonObject.getValue("entries").jsonArray
        val ruleEntry = entries.find {
            it.jsonObject["action"]?.jsonPrimitive?.content == "upsert_rule"
        }
        assertTrue(ruleEntry != null, "No audit entry for upsert_rule action")
        assertEquals("audit-rules-key", ruleEntry.jsonObject["apiKeyId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `purge endpoint is audited`() = testApplication {
        val store = ChronoStore("apiKey", ChronoStoreOptions(
            apiKeys = setOf("audit-purge-key"),
            keyMetadata = mapOf(
                "audit-purge-key" to ApiKeyMetadata(
                    keyId = "audit-purge-key",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "admin",
                ),
            ),
        ))
        application { chronoTraceModule(store) }

        val purgeBody = """{"requestedBy":"audit-test","field":"appId","value":"app-audit-purge"}"""

        val response = client.post("/api/v1/purge") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "audit-purge-key")
            setBody(purgeBody)
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val auditResponse = client.get("/api/v1/admin/audit/logs") {
            header("X-Api-Key", "audit-purge-key")
        }

        val entries = json.parseToJsonElement(auditResponse.bodyAsText()).jsonObject.getValue("entries").jsonArray
        val purgeEntry = entries.find {
            it.jsonObject["action"]?.jsonPrimitive?.content == "purge"
        }
        assertTrue(purgeEntry != null, "No audit entry for purge action")
        assertEquals("audit-purge-key", purgeEntry.jsonObject["apiKeyId"]?.jsonPrimitive?.content)
        assertEquals("success", purgeEntry.jsonObject["outcome"]?.jsonPrimitive?.content)
    }

    @Test
    fun `MCP toolscall is audited`() = testApplication {
        val store = ChronoStore("apiKey", ChronoStoreOptions(
            apiKeys = setOf("audit-mcp-key"),
            keyMetadata = mapOf(
                "audit-mcp-key" to ApiKeyMetadata(
                    keyId = "audit-mcp-key",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "admin",
                ),
            ),
        ))
        application { chronoTraceModule(store) }

        val mcpCall = """{"id":"99","method":"tools/call","params":{"name":"search_logs","query":"test"}}"""

        val response = client.post("/mcp") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "audit-mcp-key")
            setBody(mcpCall)
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val auditResponse = client.get("/api/v1/admin/audit/logs") {
            header("X-Api-Key", "audit-mcp-key")
        }

        val entries = json.parseToJsonElement(auditResponse.bodyAsText()).jsonObject.getValue("entries").jsonArray
        val mcpEntry = entries.find {
            it.jsonObject["action"]?.jsonPrimitive?.content == "mcp_tools/call"
        }
        assertTrue(mcpEntry != null, "No audit entry for mcp_tools/call action")
        assertEquals("audit-mcp-key", mcpEntry.jsonObject["apiKeyId"]?.jsonPrimitive?.content)
    }

    // -----------------------------------------------------------------------
    // Failed auth attempts are also audited (with redacted key)
    // -----------------------------------------------------------------------

    @Test
    fun `failed auth with wrong API key is audited with redacted key`() = testApplication {
        val store = ChronoStore("apiKey", ChronoStoreOptions(
            apiKeys = setOf("real-key"),
            keyMetadata = mapOf(
                "real-key" to ApiKeyMetadata(
                    keyId = "real-key",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "admin",
                ),
            ),
        ))
        application { chronoTraceModule(store) }

        // Deliberately use a wrong key
        val response = client.post("/api/v1/ingest") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "wrong-key")
            setBody(json.encodeToString(emptyIngest("app-auth-fail")))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)

        // Check audit log — the failed attempt should be recorded
        // but the wrong key value should be redacted/hashed in the log
        val auditResponse = client.get("/api/v1/admin/audit/logs") {
            header("X-Api-Key", "real-key")
        }

        val entries = json.parseToJsonElement(auditResponse.bodyAsText()).jsonObject.getValue("entries").jsonArray
        val failEntry = entries.find {
            it.jsonObject["outcome"]?.jsonPrimitive?.content == "unauthorized"
        }
        assertTrue(failEntry != null, "No audit entry for failed auth attempt")
        val keyId = failEntry.jsonObject["apiKeyId"]?.jsonPrimitive?.content
        // When an invalid key is used (not in apiKeys), the server records it as "anonymous"
        // since the key is rejected before it can be identified or hashed
        assertEquals("anonymous", keyId)
    }

    @Test
    fun `failed auth without API key is audited`() = testApplication {
        val store = ChronoStore("apiKey", ChronoStoreOptions(
            apiKeys = setOf("present-key"),
            keyMetadata = mapOf(
                "present-key" to ApiKeyMetadata(
                    keyId = "present-key",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "admin",
                ),
            ),
        ))
        application { chronoTraceModule(store) }

        val response = client.post("/api/v1/ingest") {
            contentType(ContentType.Application.Json)
            // No X-Api-Key header
            setBody(json.encodeToString(emptyIngest("app-no-key")))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)

        val auditResponse = client.get("/api/v1/admin/audit/logs") {
            header("X-Api-Key", "present-key")
        }
        assertEquals(HttpStatusCode.OK, auditResponse.status)

        val entries = json.parseToJsonElement(auditResponse.bodyAsText()).jsonObject.getValue("entries").jsonArray
        val failEntry = entries.find {
            it.jsonObject["outcome"]?.jsonPrimitive?.content == "unauthorized"
        }
        assertTrue(failEntry != null, "No audit entry for missing-key auth failure")
    }

    // -----------------------------------------------------------------------
    // Health endpoint is NOT audited (always public)
    // -----------------------------------------------------------------------

    @Test
    fun `health endpoint does not appear in audit log`() = testApplication {
        val store = ChronoStore("apiKey", ChronoStoreOptions(
            apiKeys = setOf("health-check-key"),
            keyMetadata = mapOf(
                "health-check-key" to ApiKeyMetadata(
                    keyId = "health-check-key",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "admin",
                ),
            ),
        ))
        application { chronoTraceModule(store) }

        // Hit health endpoint
        repeat(5) {
            client.get("/health")
        }

        // Check audit log for any health entries
        val auditResponse = client.get("/api/v1/admin/audit/logs") {
            header("X-Api-Key", "health-check-key")
        }
        assertEquals(HttpStatusCode.OK, auditResponse.status)

        val entries = json.parseToJsonElement(auditResponse.bodyAsText()).jsonObject.getValue("entries").jsonArray
        val healthEntries = entries.filter {
            it.jsonObject["endpoint"]?.jsonPrimitive?.content == "/health"
        }
        assertEquals(0, healthEntries.size, "Health endpoint should not appear in audit log")
    }

    // -----------------------------------------------------------------------
    // Audit log filtering by key, action, outcome, time range
    // -----------------------------------------------------------------------

    @Test
    fun `audit logs can be filtered by apiKeyId`() = testApplication {
        val store = ChronoStore("apiKey", ChronoStoreOptions(
            apiKeys = setOf("filter-key-a", "filter-key-b"),
            keyMetadata = mapOf(
                "filter-key-a" to ApiKeyMetadata(
                    keyId = "filter-key-a",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "admin",
                ),
                "filter-key-b" to ApiKeyMetadata(
                    keyId = "filter-key-b",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "client",
                ),
            ),
        ))
        application { chronoTraceModule(store) }

        // Send requests with both keys
        client.post("/api/v1/ingest") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "filter-key-a")
            setBody(json.encodeToString(emptyIngest("app-filter-a")))
        }
        client.post("/api/v1/ingest") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "filter-key-b")
            setBody(json.encodeToString(emptyIngest("app-filter-b")))
        }

        // Filter by key-a only
        val filteredResponse = client.get("/api/v1/admin/audit/logs?apiKeyId=filter-key-a") {
            header("X-Api-Key", "filter-key-a")
        }
        assertEquals(HttpStatusCode.OK, filteredResponse.status)

        val entries = json.parseToJsonElement(filteredResponse.bodyAsText()).jsonObject.getValue("entries").jsonArray
        assertTrue(entries.all {
            it.jsonObject["apiKeyId"]?.jsonPrimitive?.content == "filter-key-a"
        }, "Filter by apiKeyId returned entries for other keys")
    }

    @Test
    fun `audit logs can be filtered by action`() = testApplication {
        val store = ChronoStore("apiKey", ChronoStoreOptions(
            apiKeys = setOf("action-filter-key"),
            keyMetadata = mapOf(
                "action-filter-key" to ApiKeyMetadata(
                    keyId = "action-filter-key",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "admin",
                ),
            ),
        ))
        application { chronoTraceModule(store) }

        client.post("/api/v1/ingest") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "action-filter-key")
            setBody(json.encodeToString(emptyIngest("app-action-filter")))
        }
        client.post("/api/v1/remote-rules") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "action-filter-key")
            setBody("""{"ruleId":"r1","ttlSeconds":60,"expression":"true","createdBy":"test"}""")
        }

        val filteredResponse = client.get("/api/v1/admin/audit/logs?action=ingest") {
            header("X-Api-Key", "action-filter-key")
        }
        assertEquals(HttpStatusCode.OK, filteredResponse.status)

        val entries = json.parseToJsonElement(filteredResponse.bodyAsText()).jsonObject.getValue("entries").jsonArray
        assertTrue(entries.all {
            it.jsonObject["action"]?.jsonPrimitive?.content == "ingest"
        }, "Filter by action returned non-ingest entries")
    }

    @Test
    fun `audit logs can be filtered by time range`() = testApplication {
        val store = ChronoStore("apiKey", ChronoStoreOptions(
            apiKeys = setOf("time-filter-key"),
            keyMetadata = mapOf(
                "time-filter-key" to ApiKeyMetadata(
                    keyId = "time-filter-key",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "admin",
                ),
            ),
        ))
        application { chronoTraceModule(store) }

        val before = Instant.now().toEpochMilli()

        client.post("/api/v1/ingest") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "time-filter-key")
            setBody(json.encodeToString(emptyIngest("app-time-filter")))
        }

        val after = Instant.now().toEpochMilli()

        // Filter to time range that includes the request
        val filteredResponse = client.get("/api/v1/admin/audit/logs?startTimeUtc=$before&endTimeUtc=$after") {
            header("X-Api-Key", "time-filter-key")
        }
        assertEquals(HttpStatusCode.OK, filteredResponse.status)

        val entries = json.parseToJsonElement(filteredResponse.bodyAsText()).jsonObject.getValue("entries").jsonArray
        assertTrue(entries.isNotEmpty(), "Expected entries in time range")
    }

    // -----------------------------------------------------------------------
    // Quota-exceeded requests are also audited (outcome = quota_exceeded)
    // -----------------------------------------------------------------------

    @Test
    fun `quota exceeded requests are audited with outcome quota_exceeded`() = testApplication {
        val store = ChronoStore("apiKey", ChronoStoreOptions(
            apiKeys = setOf("quota-audit-key"),
            keyMetadata = mapOf("quota-audit-key" to ApiKeyMetadata(
                keyId = "quota-audit-key",
                createdAtUtc = Instant.now().toEpochMilli(),
                role = "admin",
                quota = ApiKeyQuota(limit = 1, windowSeconds = 60),
            )),
        ))
        application { chronoTraceModule(store) }

        // First request succeeds
        client.post("/api/v1/ingest") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "quota-audit-key")
            setBody(json.encodeToString(emptyIngest("app-quota-audit")))
        }

        // Second request is blocked by quota
        client.post("/api/v1/ingest") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "quota-audit-key")
            setBody(json.encodeToString(emptyIngest("app-quota-audit")))
        }

        val auditResponse = client.get("/api/v1/admin/audit/logs") {
            header("X-Api-Key", "quota-audit-key")
        }

        val entries = json.parseToJsonElement(auditResponse.bodyAsText()).jsonObject.getValue("entries").jsonArray
            ?: throw AssertionError("No entries in audit response")
        val quotaEntry = entries.find {
            it.jsonObject["outcome"]?.jsonPrimitive?.content == "quota_exceeded"
        }
        assertTrue(quotaEntry != null, "No audit entry for quota_exceeded outcome")
        assertEquals("quota-audit-key", quotaEntry.jsonObject["apiKeyId"]?.jsonPrimitive?.content)
    }

    // -----------------------------------------------------------------------
    // Bearer auth is also audited
    // -----------------------------------------------------------------------

    @Test
    fun `bearer auth requests are audited with key identity`() = testApplication {
        // Use apiKey auth for the store, bearer for the request
        // Bearer keyId recorded in audit log is "bearer:<token>"
        val store = ChronoStore("bearer", ChronoStoreOptions(
            bearerTokens = setOf("audit-bearer-token"),
            keyMetadata = mapOf(
                "audit-bearer-query-key" to ApiKeyMetadata(
                    keyId = "audit-bearer-query-key",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "admin",
                ),
                "bearer:audit-bearer-token" to ApiKeyMetadata(
                    keyId = "bearer:audit-bearer-token",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "admin",
                ),
            ),
        ))
        application { chronoTraceModule(store) }

        val response = client.post("/api/v1/ingest") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer audit-bearer-token")
            setBody(json.encodeToString(emptyIngest("app-bearer-audit")))
        }
        assertEquals(HttpStatusCode.OK, response.status)

        // Query audit log with bearer token (bearer mode, no apiKeys)
        val auditResponse = client.get("/api/v1/admin/audit/logs") {
            header(HttpHeaders.Authorization, "Bearer audit-bearer-token")
        }
        assertEquals(HttpStatusCode.OK, auditResponse.status)

        val entries = json.parseToJsonElement(auditResponse.bodyAsText()).jsonObject.getValue("entries").jsonArray
        val ingestEntry = entries.find {
            it.jsonObject["action"]?.jsonPrimitive?.content == "ingest"
        }
        assertTrue(ingestEntry != null)
        // Bearer token identity is recorded as "bearer:<token>"
        assertTrue(ingestEntry.jsonObject["apiKeyId"]?.jsonPrimitive?.content == "bearer:audit-bearer-token")
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
                logId = "log-audit-${System.nanoTime()}",
                appId = appId,
                environment = "test",
                sdkInstanceId = "sdk-1",
                serviceName = "test",
                traceId = "trace-audit",
                spanId = "span-audit",
                timestampUtc = Instant.now().toEpochMilli(),
                sequenceId = 1L,
                level = LogLevel.INFO,
                message = "audit test",
            ),
        ),
        spans = emptyList(),
        frameSnapshots = emptyList(),
    )
}
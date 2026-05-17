package org.chronotrace.server

import io.ktor.client.request.delete
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
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
 * Integration tests for key management endpoints on ChronoTrace server.
 *
 * Phase 6: auth hardening. Operators can create, rotate, revoke, and list API keys
 * via protected admin endpoints. Keys are stored in ChronoStore with in-memory backend
 * (for testing) and ClickHouse (production).
 *
 * These endpoints are themselves protected by auth — only a key with `role = admin`
 * can manage other keys.
 */
class KeyManagementTest {
    private val json = Json { encodeDefaults = true; prettyPrint = true }

    // -------------------------------------------------------------------------
    // Admin key identification — role field on ApiKeyMetadata
    // -------------------------------------------------------------------------

    @Test
    fun `admin key can list all keys`() = testApplication {
        val store = ChronoStore("apiKey", ChronoStoreOptions(
            apiKeys = setOf("admin-key", "client-key-1", "client-key-2"),
            keyMetadata = mapOf(
                "admin-key" to ApiKeyMetadata(
                    keyId = "admin-key",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "admin",
                ),
                "client-key-1" to ApiKeyMetadata(
                    keyId = "client-key-1",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "client",
                ),
                "client-key-2" to ApiKeyMetadata(
                    keyId = "client-key-2",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "client",
                ),
            ),
        ))
        application { chronoTraceModule(store) }

        val response = client.get("/api/v1/admin/keys") {
            header("X-Api-Key", "admin-key")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val keysJson = json.parseToJsonElement(response.bodyAsText())
        val keys = keysJson.jsonArray

        assertTrue(keys.size >= 3, "Expected at least 3 keys, got ${keys.size}")
        val keyIds = keys.map { it.jsonObject["keyId"]?.jsonPrimitive?.content }
        assertTrue(keyIds.contains("admin-key"))
        assertTrue(keyIds.contains("client-key-1"))
        assertTrue(keyIds.contains("client-key-2"))
    }

    @Test
    fun `non-admin key cannot list keys`() = testApplication {
        val store = ChronoStore("apiKey", ChronoStoreOptions(
            apiKeys = setOf("admin-key", "regular-key"),
            keyMetadata = mapOf(
                "admin-key" to ApiKeyMetadata(
                    keyId = "admin-key",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "admin",
                ),
                "regular-key" to ApiKeyMetadata(
                    keyId = "regular-key",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "client", // not admin
                ),
            ),
        ))
        application { chronoTraceModule(store) }

        val response = client.get("/api/v1/admin/keys") {
            header("X-Api-Key", "regular-key")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `unauthenticated request to admin endpoint returns 401`() = testApplication {
        val store = ChronoStore("apiKey", ChronoStoreOptions(
            apiKeys = setOf("admin-key"),
            keyMetadata = mapOf(
                "admin-key" to ApiKeyMetadata(
                    keyId = "admin-key",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "admin",
                ),
            ),
        ))
        application { chronoTraceModule(store) }

        val response = client.get("/api/v1/admin/keys")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // -------------------------------------------------------------------------
    // Create key
    // -------------------------------------------------------------------------

    @Test
    fun `admin can create a new API key`() = testApplication {
        val store = ChronoStore("apiKey", ChronoStoreOptions(
            apiKeys = setOf("admin-key"),
            keyMetadata = mapOf(
                "admin-key" to ApiKeyMetadata(
                    keyId = "admin-key",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "admin",
                ),
            ),
        ))
        application { chronoTraceModule(store) }

        val createBody = """{
            "role": "client",
            "quota": {"limit": 1000, "windowSeconds": 60},
            "appId": "new-app"
        }""".trimMargin()

        val response = client.post("/api/v1/admin/keys") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "admin-key")
            setBody(createBody)
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val created = json.parseToJsonElement(response.bodyAsText()).jsonObject

        assertNotNull(created["keyId"]?.jsonPrimitive?.content)
        assertNotNull(created["keyValue"]?.jsonPrimitive?.content, "keyValue must be returned on create")
        assertEquals("client", created["role"]?.jsonPrimitive?.content)
        assertEquals(1000, created["quota"]?.jsonObject?.get("limit")?.jsonPrimitive?.content?.toInt())
        assertEquals("new-app", created["appId"]?.jsonPrimitive?.content)
        // createdAtUtc should be set
        assertNotNull(created["createdAtUtc"]?.jsonPrimitive?.content?.toLongOrNull())
    }

    @Test
    fun `admin can create key with admin role`() = testApplication {
        val store = ChronoStore("apiKey", ChronoStoreOptions(
            apiKeys = setOf("admin-key"),
            keyMetadata = mapOf(
                "admin-key" to ApiKeyMetadata(
                    keyId = "admin-key",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "admin",
                ),
            ),
        ))
        application { chronoTraceModule(store) }

        val createBody = """{"role": "admin"}""".trimMargin()

        val response = client.post("/api/v1/admin/keys") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "admin-key")
            setBody(createBody)
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val created = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("admin", created["role"]?.jsonPrimitive?.content)
    }

    @Test
    fun `non-admin cannot create a key`() = testApplication {
        val store = ChronoStore("apiKey", ChronoStoreOptions(
            apiKeys = setOf("admin-key", "regular-key"),
            keyMetadata = mapOf(
                "admin-key" to ApiKeyMetadata(
                    keyId = "admin-key",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "admin",
                ),
                "regular-key" to ApiKeyMetadata(
                    keyId = "regular-key",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "client",
                ),
            ),
        ))
        application { chronoTraceModule(store) }

        val createBody = """{"role": "client"}""".trimMargin()

        val response = client.post("/api/v1/admin/keys") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "regular-key")
            setBody(createBody)
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `created key value can be used immediately for auth`() = testApplication {
        val store = ChronoStore("apiKey", ChronoStoreOptions(
            apiKeys = setOf("admin-key"),
            keyMetadata = mapOf(
                "admin-key" to ApiKeyMetadata(
                    keyId = "admin-key",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "admin",
                ),
            ),
        ))
        application { chronoTraceModule(store) }

        // Create a new key
        val createResponse = client.post("/api/v1/admin/keys") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "admin-key")
            setBody("""{"role":"client","appId":"immediate-use-app"}""")
        }
        assertEquals(HttpStatusCode.OK, createResponse.status)
        val newKeyValue = json.parseToJsonElement(createResponse.bodyAsText())
            .jsonObject["keyValue"]?.jsonPrimitive?.content

        // Immediately use the new key
        val ingestResponse = client.post("/api/v1/ingest") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", newKeyValue!!)
            setBody(json.encodeToString(emptyIngest("immediate-use-app")))
        }
        assertEquals(HttpStatusCode.OK, ingestResponse.status, "Newly created key should be immediately usable")
    }

    @Test
    fun `create key validates role field`() = testApplication {
        val store = ChronoStore("apiKey", ChronoStoreOptions(
            apiKeys = setOf("admin-key"),
            keyMetadata = mapOf(
                "admin-key" to ApiKeyMetadata(
                    keyId = "admin-key",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "admin",
                ),
            ),
        ))
        application { chronoTraceModule(store) }

        val createBody = """{"role": "superadmin"}""".trimMargin() // invalid role

        val response = client.post("/api/v1/admin/keys") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "admin-key")
            setBody(createBody)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // -------------------------------------------------------------------------
    // Rotate key
    // -------------------------------------------------------------------------

    @Test
    fun `admin can rotate an existing key`() = testApplication {
        val store = ChronoStore("apiKey", ChronoStoreOptions(
            apiKeys = setOf("admin-key", "rotate-me-key"),
            keyMetadata = mapOf(
                "admin-key" to ApiKeyMetadata(
                    keyId = "admin-key",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "admin",
                ),
                "rotate-me-key" to ApiKeyMetadata(
                    keyId = "rotate-me-key",
                    createdAtUtc = Instant.now().toEpochMilli() - 86_400_000,
                    role = "client",
                ),
            ),
        ))
        application { chronoTraceModule(store) }

        // Store the original key value for later verification
        val originalKeyValue = "rotate-me-key"

        val response = client.post("/api/v1/admin/keys/rotate-me-key/rotate") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "admin-key")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val rotated = json.parseToJsonElement(response.bodyAsText()).jsonObject

        val newKeyValue = rotated["keyValue"]?.jsonPrimitive?.content
        assertNotNull(newKeyValue, "New keyValue must be returned on rotate")
        assertNotEquals(originalKeyValue, newKeyValue, "Rotated key should have different value")
        assertEquals("rotate-me-key", rotated["keyId"]?.jsonPrimitive?.content)
        // rotatedAtUtc should be set
        assertNotNull(rotated["rotatedAtUtc"]?.jsonPrimitive?.content?.toLongOrNull())
        // The old key value should no longer work
        val oldKeyIngest = client.post("/api/v1/ingest") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", originalKeyValue)
            setBody(json.encodeToString(emptyIngest("rotate-test")))
        }
        assertEquals(HttpStatusCode.Unauthorized, oldKeyIngest.status)
    }

    @Test
    fun `rotated key value works immediately`() = testApplication {
        val store = ChronoStore("apiKey", ChronoStoreOptions(
            apiKeys = setOf("admin-key", "key-to-rotate"),
            keyMetadata = mapOf(
                "admin-key" to ApiKeyMetadata(
                    keyId = "admin-key",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "admin",
                ),
                "key-to-rotate" to ApiKeyMetadata(
                    keyId = "key-to-rotate",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "client",
                ),
            ),
        ))
        application { chronoTraceModule(store) }

        val rotateResponse = client.post("/api/v1/admin/keys/key-to-rotate/rotate") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "admin-key")
        }
        val newKeyValue = json.parseToJsonElement(rotateResponse.bodyAsText())
            .jsonObject["keyValue"]?.jsonPrimitive?.content

        // Use new key immediately
        val ingestResponse = client.post("/api/v1/ingest") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", newKeyValue!!)
            setBody(json.encodeToString(emptyIngest("rotate-immediate")))
        }
        assertEquals(HttpStatusCode.OK, ingestResponse.status)
    }

    @Test
    fun `non-admin cannot rotate a key`() = testApplication {
        val store = ChronoStore("apiKey", ChronoStoreOptions(
            apiKeys = setOf("admin-key", "client-key", "another-key"),
            keyMetadata = mapOf(
                "admin-key" to ApiKeyMetadata(
                    keyId = "admin-key",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "admin",
                ),
                "client-key" to ApiKeyMetadata(
                    keyId = "client-key",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "client",
                ),
            ),
        ))
        application { chronoTraceModule(store) }

        val response = client.post("/api/v1/admin/keys/another-key/rotate") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "client-key")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `rotating a non-existent key returns 404`() = testApplication {
        val store = ChronoStore("apiKey", ChronoStoreOptions(
            apiKeys = setOf("admin-key", "existing-key"),
            keyMetadata = mapOf(
                "admin-key" to ApiKeyMetadata(
                    keyId = "admin-key",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "admin",
                ),
            ),
        ))
        application { chronoTraceModule(store) }

        val response = client.post("/api/v1/admin/keys/non-existent-key/rotate") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "admin-key")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // -------------------------------------------------------------------------
    // Revoke key
    // -------------------------------------------------------------------------

    @Test
    fun `admin can revoke an existing key`() = testApplication {
        val store = ChronoStore("apiKey", ChronoStoreOptions(
            apiKeys = setOf("admin-key", "revoke-me-key"),
            keyMetadata = mapOf(
                "admin-key" to ApiKeyMetadata(
                    keyId = "admin-key",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "admin",
                ),
                "revoke-me-key" to ApiKeyMetadata(
                    keyId = "revoke-me-key",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "client",
                    revokedAtUtc = null,
                ),
            ),
        ))
        application { chronoTraceModule(store) }

        val response = client.delete("/api/v1/admin/keys/revoke-me-key") {
            header("X-Api-Key", "admin-key")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)

        // Revoked key should no longer work
        val ingestResponse = client.post("/api/v1/ingest") {
            contentType(ContentType.Application.Json)
            header("X-Api-Key", "revoke-me-key")
            setBody(json.encodeToString(emptyIngest("revoke-test")))
        }
        assertEquals(HttpStatusCode.Unauthorized, ingestResponse.status)
    }

    @Test
    fun `admin cannot revoke own key`() = testApplication {
        val store = ChronoStore("apiKey", ChronoStoreOptions(
            apiKeys = setOf("admin-key"),
            keyMetadata = mapOf(
                "admin-key" to ApiKeyMetadata(
                    keyId = "admin-key",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "admin",
                ),
            ),
        ))
        application { chronoTraceModule(store) }

        val response = client.delete("/api/v1/admin/keys/admin-key") {
            header("X-Api-Key", "admin-key")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `non-admin cannot revoke a key`() = testApplication {
        val store = ChronoStore("apiKey", ChronoStoreOptions(
            apiKeys = setOf("admin-key", "client-key", "target-key"),
            keyMetadata = mapOf(
                "admin-key" to ApiKeyMetadata(
                    keyId = "admin-key",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "admin",
                ),
                "client-key" to ApiKeyMetadata(
                    keyId = "client-key",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "client",
                ),
            ),
        ))
        application { chronoTraceModule(store) }

        val response = client.delete("/api/v1/admin/keys/target-key") {
            header("X-Api-Key", "client-key")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `revoking non-existent key returns 404`() = testApplication {
        val store = ChronoStore("apiKey", ChronoStoreOptions(
            apiKeys = setOf("admin-key"),
            keyMetadata = mapOf(
                "admin-key" to ApiKeyMetadata(
                    keyId = "admin-key",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "admin",
                ),
            ),
        ))
        application { chronoTraceModule(store) }

        val response = client.delete("/api/v1/admin/keys/non-existent-key") {
            header("X-Api-Key", "admin-key")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `revoked key appears in list as revoked`() = testApplication {
        val store = ChronoStore("apiKey", ChronoStoreOptions(
            apiKeys = setOf("admin-key", "revoke-list-key"),
            keyMetadata = mapOf(
                "admin-key" to ApiKeyMetadata(
                    keyId = "admin-key",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "admin",
                ),
                "revoke-list-key" to ApiKeyMetadata(
                    keyId = "revoke-list-key",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "client",
                ),
            ),
        ))
        application { chronoTraceModule(store) }

        client.delete("/api/v1/admin/keys/revoke-list-key") {
            header("X-Api-Key", "admin-key")
        }

        val listResponse = client.get("/api/v1/admin/keys") {
            header("X-Api-Key", "admin-key")
        }

        val keys = json.parseToJsonElement(listResponse.bodyAsText()).jsonArray
        val revokedKey = keys.find {
            it.jsonObject["keyId"]?.jsonPrimitive?.content == "revoke-list-key"
        }
        assertNotNull(revokedKey, "Revoked key should still appear in list")
        assertNotNull(revokedKey.jsonObject["revokedAtUtc"]?.jsonPrimitive?.content?.toLongOrNull(),
            "revokedAtUtc must be set")
    }

    // -------------------------------------------------------------------------
    // List keys — sensitive fields redacted
    // -------------------------------------------------------------------------

    @Test
    fun `key list does not expose key values`() = testApplication {
        val store = ChronoStore("apiKey", ChronoStoreOptions(
            apiKeys = setOf("admin-key", "client-key"),
            keyMetadata = mapOf(
                "admin-key" to ApiKeyMetadata(
                    keyId = "admin-key",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "admin",
                ),
                "client-key" to ApiKeyMetadata(
                    keyId = "client-key",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "client",
                ),
            ),
        ))
        application { chronoTraceModule(store) }

        val response = client.get("/api/v1/admin/keys") {
            header("X-Api-Key", "admin-key")
        }

        val keys = json.parseToJsonElement(response.bodyAsText()).jsonArray
        for (key in keys) {
            val keyObj = key.jsonObject
            // keyValue should NOT be included in list output (only on create/rotate)
            assertTrue(keyObj["keyValue"] == null,
                "keyValue should not appear in list output — only on create/rotate")
            // keyId, role, createdAtUtc, quota, appId should be present
            assertNotNull(keyObj["keyId"])
            assertNotNull(keyObj["role"])
            assertNotNull(keyObj["createdAtUtc"])
        }
    }

    @Test
    fun `key list can be filtered by role`() = testApplication {
        val store = ChronoStore("apiKey", ChronoStoreOptions(
            apiKeys = setOf("admin-key", "client-a", "client-b"),
            keyMetadata = mapOf(
                "admin-key" to ApiKeyMetadata(
                    keyId = "admin-key",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "admin",
                ),
                "client-a" to ApiKeyMetadata(
                    keyId = "client-a",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "client",
                ),
                "client-b" to ApiKeyMetadata(
                    keyId = "client-b",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "client",
                ),
            ),
        ))
        application { chronoTraceModule(store) }

        val response = client.get("/api/v1/admin/keys?role=client") {
            header("X-Api-Key", "admin-key")
        }

        val keys = json.parseToJsonElement(response.bodyAsText()).jsonArray
        assertTrue(keys.all {
            it.jsonObject["role"]?.jsonPrimitive?.content == "client"
        }, "Filter by role=client should only return client keys")
    }

    @Test
    fun `key list can be filtered by appId`() = testApplication {
        val store = ChronoStore("apiKey", ChronoStoreOptions(
            apiKeys = setOf("admin-key", "app-a-key", "app-b-key"),
            keyMetadata = mapOf(
                "admin-key" to ApiKeyMetadata(
                    keyId = "admin-key",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "admin",
                ),
                "app-a-key" to ApiKeyMetadata(
                    keyId = "app-a-key",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "client",
                    appId = "app-a",
                ),
                "app-b-key" to ApiKeyMetadata(
                    keyId = "app-b-key",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "client",
                    appId = "app-b",
                ),
            ),
        ))
        application { chronoTraceModule(store) }

        val response = client.get("/api/v1/admin/keys?appId=app-a") {
            header("X-Api-Key", "admin-key")
        }

        val keys = json.parseToJsonElement(response.bodyAsText()).jsonArray
        assertTrue(keys.all {
            it.jsonObject["appId"]?.jsonPrimitive?.content == "app-a"
        }, "Filter by appId=app-a should only return keys for app-a")
    }

    // -------------------------------------------------------------------------
    // Bearer auth — admin endpoints also work with bearer tokens
    // -------------------------------------------------------------------------

    @Test
    fun `bearer token with admin role can manage keys`() = testApplication {
        val store = ChronoStore("bearer", ChronoStoreOptions(
            bearerTokens = setOf("admin-bearer-token"),
            keyMetadata = mapOf(
                "bearer:admin-bearer-token" to ApiKeyMetadata(
                    keyId = "bearer:admin-bearer-token",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "admin",
                ),
            ),
        ))
        application { chronoTraceModule(store) }

        val listResponse = client.get("/api/v1/admin/keys") {
            header(HttpHeaders.Authorization, "Bearer admin-bearer-token")
        }
        assertEquals(HttpStatusCode.OK, listResponse.status)
    }

    @Test
    fun `bearer token without admin role cannot manage keys`() = testApplication {
        val store = ChronoStore("bearer", ChronoStoreOptions(
            bearerTokens = setOf("client-bearer-token"),
            keyMetadata = mapOf(
                "bearer:client-bearer-token" to ApiKeyMetadata(
                    keyId = "bearer:client-bearer-token",
                    createdAtUtc = Instant.now().toEpochMilli(),
                    role = "client",
                ),
            ),
        ))
        application { chronoTraceModule(store) }

        val response = client.get("/api/v1/admin/keys") {
            header(HttpHeaders.Authorization, "Bearer client-bearer-token")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private fun emptyIngest(appId: String = "test") = IngestBatch(
        client = ClientMetadata(
            appId = appId,
            environment = "test",
            sdkInstanceId = "sdk-1",
            serviceName = "test",
        ),
        logs = listOf(
            LogRecord(
                logId = "log-keymgmt-${System.nanoTime()}",
                appId = appId,
                environment = "test",
                sdkInstanceId = "sdk-1",
                serviceName = "test",
                traceId = "trace-keymgmt",
                spanId = "span-keymgmt",
                timestampUtc = Instant.now().toEpochMilli(),
                sequenceId = 1L,
                level = LogLevel.INFO,
                message = "key management test",
            ),
        ),
        spans = emptyList(),
        frameSnapshots = emptyList(),
    )
}
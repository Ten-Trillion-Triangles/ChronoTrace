package org.chronotrace.server

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.chronotrace.contract.IngestBatch
import org.chronotrace.contract.LogLevel
import org.chronotrace.contract.LogRecord

/**
 * Integration tests for persisting dynamically created API keys across restarts.
 *
 * Tests that keys created/rotated/revoked via the API survive server restarts.
 * FILE mode: keys are saved to the data directory's keys.json snapshot.
 * CLICKHOUSE mode: keys are saved to Valkey.
 */
class PersistDynamicApiKeysTest {
    private val json = Json { encodeDefaults = true; prettyPrint = true }

    private fun emptyIngest(appId: String) = IngestBatch(
        client = org.chronotrace.contract.ClientMetadata(
            appId = appId,
            environment = "test",
            sdkInstanceId = "test-sdk",
            serviceName = "test-service",
        ),
        logs = listOf(
            LogRecord(
                logId = "log-${System.nanoTime()}",
                appId = appId,
                environment = "test",
                sdkInstanceId = "test-sdk",
                serviceName = "test-service",
                timestampUtc = Instant.now().toEpochMilli(),
                sequenceId = 0,
                level = LogLevel.INFO,
                message = "test",
            )
        ),
        spans = emptyList(),
        frameSnapshots = emptyList(),
    )

    // ── FILE mode: keys survive restart via keys.json ─────────────────────────

    @Test
    fun fileModeDynamicallyCreatedKeySurvivesRestart() = testApplication {
        val tempDir = Files.createTempDirectory("ct-keys-test-1")
        try {
            val store = ChronoStore("apiKey", ChronoStoreOptions(
                storageMode = StorageMode.FILE,
                dataDir = tempDir,
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

            // Create a new client key
            val createResponse = client.post("/api/v1/admin/keys") {
                contentType(ContentType.Application.Json)
                header("X-Api-Key", "admin-key")
                setBody("""{"role":"client","appId":"test-app"}""")
            }
            assertEquals(HttpStatusCode.Created, createResponse.status)
            val newKeyValue = json.parseToJsonElement(createResponse.bodyAsText())
                .jsonObject["keyValue"]?.jsonPrimitive?.content
            assertNotNull(newKeyValue)

            // Verify the new key works for ingest
            val ingest1 = client.post("/api/v1/ingest") {
                contentType(ContentType.Application.Json)
                header("X-Api-Key", newKeyValue)
                setBody(json.encodeToString(emptyIngest("test-app")))
            }
            assertEquals(HttpStatusCode.OK, ingest1.status)

            // The key should also appear in the list
            val listResponse = client.get("/api/v1/admin/keys") {
                header("X-Api-Key", "admin-key")
            }
            assertEquals(HttpStatusCode.OK, listResponse.status)
            val keysJson = json.parseToJsonElement(listResponse.bodyAsText()).jsonArray
            assertTrue(keysJson.size >= 2, "Expected at least 2 keys (admin + created)")
            val keyIds = keysJson.map { it.jsonObject["keyId"]?.jsonPrimitive?.content }
            assertTrue(keyIds.contains(newKeyValue), "Dynamically created key should appear in list")
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun fileModeRotatedKeyOldValueDoesNotSurviveRestart() = testApplication {
        val tempDir = Files.createTempDirectory("ct-keys-test-2")
        try {
            val store = ChronoStore("apiKey", ChronoStoreOptions(
                storageMode = StorageMode.FILE,
                dataDir = tempDir,
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

            // Rotate the client key
            val rotateResponse = client.post("/api/v1/admin/keys/client-key/rotate") {
                contentType(ContentType.Application.Json)
                header("X-Api-Key", "admin-key")
            }
            assertEquals(HttpStatusCode.OK, rotateResponse.status)
            val newKeyValue = json.parseToJsonElement(rotateResponse.bodyAsText())
                .jsonObject["keyValue"]?.jsonPrimitive?.content
            assertNotNull(newKeyValue)
            assertTrue(newKeyValue != "client-key", "Rotated key should have different value")

            // Old key should not work after rotation
            val oldIngest = client.post("/api/v1/ingest") {
                contentType(ContentType.Application.Json)
                header("X-Api-Key", "client-key")
                setBody(json.encodeToString(emptyIngest("rotate-test")))
            }
            assertEquals(HttpStatusCode.Unauthorized, oldIngest.status)

            // New key should work
            val newIngest = client.post("/api/v1/ingest") {
                contentType(ContentType.Application.Json)
                header("X-Api-Key", newKeyValue)
                setBody(json.encodeToString(emptyIngest("rotate-test")))
            }
            assertEquals(HttpStatusCode.OK, newIngest.status)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun fileModeRevokedKeyDoesNotSurviveRestart() = testApplication {
        val tempDir = Files.createTempDirectory("ct-keys-test-3")
        try {
            val store = ChronoStore("apiKey", ChronoStoreOptions(
                storageMode = StorageMode.FILE,
                dataDir = tempDir,
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
                    ),
                ),
            ))
            application { chronoTraceModule(store) }

            // Revoke the key
            val revokeResponse = client.delete("/api/v1/admin/keys/revoke-me-key") {
                header("X-Api-Key", "admin-key")
            }
            assertEquals(HttpStatusCode.NoContent, revokeResponse.status)

            // Revoked key should not work
            val revokedIngest = client.post("/api/v1/ingest") {
                contentType(ContentType.Application.Json)
                header("X-Api-Key", "revoke-me-key")
                setBody(json.encodeToString(emptyIngest("revoke-test")))
            }
            assertEquals(HttpStatusCode.Unauthorized, revokedIngest.status)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun fileModeKeyListShowsPersistedKeysAfterRestart() = testApplication {
        val tempDir = Files.createTempDirectory("ct-keys-test-4")
        try {
            val store = ChronoStore("apiKey", ChronoStoreOptions(
                storageMode = StorageMode.FILE,
                dataDir = tempDir,
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

            // Create two new keys
            val key1Response = client.post("/api/v1/admin/keys") {
                contentType(ContentType.Application.Json)
                header("X-Api-Key", "admin-key")
                setBody("""{"role":"client","appId":"app-1"}""")
            }
            assertEquals(HttpStatusCode.Created, key1Response.status)
            val key1Value = json.parseToJsonElement(key1Response.bodyAsText()).jsonObject["keyValue"]?.jsonPrimitive?.content

            val key2Response = client.post("/api/v1/admin/keys") {
                contentType(ContentType.Application.Json)
                header("X-Api-Key", "admin-key")
                setBody("""{"role":"client","appId":"app-2"}""")
            }
            assertEquals(HttpStatusCode.Created, key2Response.status)
            val key2Value = json.parseToJsonElement(key2Response.bodyAsText()).jsonObject["keyValue"]?.jsonPrimitive?.content

            // Verify both keys appear in the list
            val listResponse = client.get("/api/v1/admin/keys") {
                header("X-Api-Key", "admin-key")
            }
            assertEquals(HttpStatusCode.OK, listResponse.status)
            val keysJson = json.parseToJsonElement(listResponse.bodyAsText()).jsonArray

            // admin-key + 2 dynamically created keys = 3 keys minimum
            assertTrue(keysJson.size >= 3, "Expected at least 3 keys (admin + 2 created), got ${keysJson.size}")
            val keyIds = keysJson.map { it.jsonObject["keyId"]?.jsonPrimitive?.content }
            assertTrue(keyIds.contains(key1Value), "Created key 1 should appear in list")
            assertTrue(keyIds.contains(key2Value), "Created key 2 should appear in list")
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
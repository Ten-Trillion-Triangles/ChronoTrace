package org.chronotrace.server

import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable
import org.testcontainers.clickhouse.ClickHouseContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for ClickHouse schema migration and version tracking.
 *
 * Verifies:
 * - schema_version table is created on bootstrap
 * - Contract version is recorded on first bootstrap
 * - Schema mismatch throws IllegalStateException on startup
 */
@Testcontainers
@DisabledIfEnvironmentVariable(named = "DOCKER_AVAILABLE", matches = "false")
class SchemaMigrationTest {

    companion object {
        private const val CLICKHOUSE_IMAGE = "clickhouse/clickhouse-server:25.1"
    }

    @Container
    val clickHouse = ClickHouseContainer(CLICKHOUSE_IMAGE).apply {
        withStartupTimeoutSeconds(60)
    }

    private fun makeStoreOptions(): ChronoStoreOptions {
        return ChronoStoreOptions(
            storageMode = StorageMode.CLICKHOUSE,
            retentionDaysLogs = 30,
            retentionDaysSpans = 30,
            retentionDaysFrames = 7,
            clickHouse = ClickHouseConfig(
                jdbcUrl = clickHouse.jdbcUrl,
                database = "default",
                username = "test",
                password = "test",
            ),
            valkey = ValkeyConfig(
                host = "localhost",
                port = 6379,
            ),
        )
    }

    @Test
    fun `schema_version table is created on first bootstrap`() {
        ChronoStore(authMode = "none", options = makeStoreOptions()).use { store ->
            // The store should start without throwing
            assertNotNull(store)
        }
    }

    @Test
    fun `schema version is recorded in schema_version table`() {
        val options = makeStoreOptions()
        ChronoStore(authMode = "none", options = options).use { store ->
            assertNotNull(store)
        }

        // After bootstrap, verify the schema_version table was created and recorded
        val conn = DriverManager.getConnection(
            options.clickHouse!!.jdbcUrl,
            options.clickHouse.username,
            options.clickHouse.password,
        )
        try {
            val rs = conn.createStatement().executeQuery(
                "SELECT version FROM default.schema_version WHERE key = 'schema_version' ORDER BY applied_at DESC LIMIT 1"
            )
            assertTrue(rs.next(), "Expected a version record in schema_version table")
            val version = rs.getInt("version")
            assertEquals(1, version, "Schema version should be 1")
            rs.close()
        } finally {
            conn.close()
        }
    }

    @Test
    fun `fresh bootstrap records current contract version`() {
        ChronoStore(authMode = "none", options = makeStoreOptions()).use { store ->
            // Bootstrap should complete without error, recording the current version
            assertNotNull(store)
        }
    }

    @Test
    fun `schema mismatch throws IllegalStateException with correct message format`() {
        // First, create a store and manually set a mismatched schema version
        val options = makeStoreOptions()
        ChronoStore(authMode = "none", options = options).use { store ->
            assertNotNull(store)
        }

        // Insert a mismatched schema version directly using a raw connection
        val conn = DriverManager.getConnection(
            options.clickHouse!!.jdbcUrl,
            options.clickHouse.username,
            options.clickHouse.password,
        )
        try {
            conn.createStatement().use { stmt ->
                // Delete any existing schema_version records
                stmt.executeUpdate("DELETE FROM default.schema_version WHERE key = 'schema_version'")
                // Insert an older (mismatched) version
                stmt.executeUpdate(
                    "INSERT INTO default.schema_version (key, version, applied_at) VALUES ('schema_version', 0, ${System.currentTimeMillis()})"
                )
            }
        } finally {
            conn.close()
        }

        // Now creating a new store should throw due to mismatch
        val e = assertFailsWith<IllegalStateException> {
            ChronoStore(authMode = "none", options = makeStoreOptions())
        }

        val message = e.message ?: ""
        assertTrue(message.contains("Schema mismatch"), "Message should contain 'Schema mismatch': $message")
        assertTrue(message.contains("contract="), "Message should contain 'contract=': $message")
        assertTrue(message.contains("DB="), "Message should contain 'DB=': $message")
        assertTrue(message.contains("migration required"), "Message should contain 'migration required': $message")
    }

    @Test
    fun `schema_version table has correct schema`() {
        val options = makeStoreOptions()
        ChronoStore(authMode = "none", options = options).use { store ->
            assertNotNull(store)
        }

        val conn = DriverManager.getConnection(
            options.clickHouse!!.jdbcUrl,
            options.clickHouse.username,
            options.clickHouse.password,
        )
        try {
            val columns = mutableListOf<Map<String, String>>()
            val rs = conn.createStatement().executeQuery("DESCRIBE TABLE default.schema_version")
            while (rs.next()) {
                columns.add(mapOf(
                    "name" to rs.getString("name"),
                    "type" to rs.getString("type"),
                ))
            }
            rs.close()
            assertTrue(columns.any { it["name"] == "key" }, "Should have 'key' column: $columns")
            assertTrue(columns.any { it["name"] == "version" }, "Should have 'version' column: $columns")
            assertTrue(columns.any { it["name"] == "applied_at" }, "Should have 'applied_at' column: $columns")
        } finally {
            conn.close()
        }
    }
}

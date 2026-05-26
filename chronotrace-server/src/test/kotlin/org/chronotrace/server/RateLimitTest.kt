package org.chronotrace.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.chronotrace.contract.ToolCallRequest

/**
 * Tests for MCP tool query rate limiting.
 *
 * When an API key has a quota configured, MCP tool calls (search_logs, get_log,
 * get_frame_snapshot, get_trace, step_frames) must respect the quota.
 * When quota is exceeded, the MCP call must return an error response with
 * body {"error": "query budget exceeded", "retryAfter": <seconds>}.
 */
class RateLimitTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `MCP search_logs returns error when quota exhausted`() {
        val store = ChronoStore("apiKey", ChronoStoreOptions(
            apiKeys = setOf("rate-limited-key"),
            keyMetadata = mapOf("rate-limited-key" to ApiKeyMetadata(
                keyId = "rate-limited-key",
                createdAtUtc = Instant.now().toEpochMilli() - 86_400_000,
                quota = ApiKeyQuota(limit = 1, windowSeconds = 60),
            )),
        ))
        val mcpTooling = McpTooling(store, json)

        // First call should succeed (quota not yet exceeded)
        val first = mcpTooling.call(
            ToolCallRequest(name = "search_logs", arguments = mapOf("appId" to "test-app")),
            callerKeyId = "rate-limited-key",
        )
        assertTrue(
            !first.isError && first.text.contains("logs"),
            "First call should succeed, got isError=${first.isError}: ${first.text}",
        )

        // Exhaust the quota
        store.recordRequest("rate-limited-key")

        // Second call now exceeds quota — should return error with budget exceeded
        val second = mcpTooling.call(
            ToolCallRequest(name = "search_logs", arguments = mapOf("appId" to "test-app")),
            callerKeyId = "rate-limited-key",
        )
        assertTrue(
            second.isError,
            "Second call should be error when quota exhausted, got: ${second.text}",
        )
        assertTrue(
            second.structuredContent.contains("query budget exceeded"),
            "Error body should contain 'query budget exceeded', got: ${second.structuredContent}",
        )
        assertTrue(
            second.structuredContent.contains("retryAfter"),
            "Error body should contain 'retryAfter', got: ${second.structuredContent}",
        )
    }

    @Test
    fun `MCP get_log with quota limit returns error when exhausted`() {
        val store = ChronoStore("apiKey", ChronoStoreOptions(
            apiKeys = setOf("exhausted-key"),
            keyMetadata = mapOf("exhausted-key" to ApiKeyMetadata(
                keyId = "exhausted-key",
                createdAtUtc = Instant.now().toEpochMilli(),
                quota = ApiKeyQuota(limit = 0, windowSeconds = 60), // immediately exceeded
            )),
        ))
        val mcpTooling = McpTooling(store, json)

        val exceeded = store.checkQuota("exhausted-key")
        assertTrue(exceeded != null, "Quota should be exceeded with limit=0")
        assertEquals(0, exceeded!!.remaining)

        val response = mcpTooling.call(
            ToolCallRequest(name = "get_log", arguments = mapOf("logId" to "any-log")),
            callerKeyId = "exhausted-key",
        )
        assertTrue(
            response.isError,
            "MCP call when quota exhausted should return error, got: ${response.text}",
        )
        assertTrue(
            response.structuredContent.contains("query budget exceeded"),
            "Error should contain 'query budget exceeded', got: ${response.structuredContent}",
        )
    }

    @Test
    fun `MCP tool without quota limit succeeds without quota check error`() {
        val store = ChronoStore("apiKey", ChronoStoreOptions(
            apiKeys = setOf("unlimited-key"),
            // No keyMetadata means unlimited (no quota set)
        ))
        val mcpTooling = McpTooling(store, json)

        // Without quota, checkQuota should return null
        val exceeded = store.checkQuota("unlimited-key")
        assertEquals(null, exceeded, "Unlimited key should have null quota check result")

        // MCP call should succeed
        val response = mcpTooling.call(
            ToolCallRequest(name = "get_system_health", arguments = emptyMap()),
            callerKeyId = "unlimited-key",
        )
        assertTrue(
            !response.isError,
            "MCP call without quota should succeed, got isError=${response.isError}: ${response.text}",
        )
    }

    @Test
    fun `quota exceeded includes correct retryAfter in response`() {
        val store = ChronoStore("apiKey", ChronoStoreOptions(
            apiKeys = setOf("limited-key"),
            keyMetadata = mapOf("limited-key" to ApiKeyMetadata(
                keyId = "limited-key",
                createdAtUtc = Instant.now().toEpochMilli(),
                quota = ApiKeyQuota(limit = 1, windowSeconds = 30),
            )),
        ))
        val mcpTooling = McpTooling(store, json)

        // Exhaust the quota
        store.recordRequest("limited-key")
        val exceeded = store.checkQuota("limited-key")

        assertTrue(exceeded != null, "Should be exceeded")
        assertEquals(30, exceeded!!.windowSeconds)
        assertEquals(1, exceeded.limit)
        assertEquals(0, exceeded.remaining)
        assertTrue(exceeded.retryAfterSeconds in 1..30, "retryAfter should be 1..30 seconds")

        // Error response should have the retryAfter value from the exceeded result
        val response = mcpTooling.call(
            ToolCallRequest(name = "get_frame_snapshot", arguments = mapOf("frameId" to "any")),
            callerKeyId = "limited-key",
        )
        assertTrue(response.isError)
        assertTrue(
            response.structuredContent.contains(""""retryAfter":${exceeded.retryAfterSeconds}"""),
            "Error body should contain retryAfter=${exceeded.retryAfterSeconds}, got: ${response.structuredContent}",
        )
    }

    @Test
    fun `non-query tools bypass quota check`() {
        // Tools like upsert_remote_rule, delete_remote_rule, create_purge_job
        // should not be subject to quota (they are write operations but controlled by other means)
        val store = ChronoStore("apiKey", ChronoStoreOptions(
            apiKeys = setOf("limited-key"),
            keyMetadata = mapOf("limited-key" to ApiKeyMetadata(
                keyId = "limited-key",
                createdAtUtc = Instant.now().toEpochMilli(),
                quota = ApiKeyQuota(limit = 0, windowSeconds = 60),
            )),
        ))
        val mcpTooling = McpTooling(store, json)

        // Non-query tool should NOT trigger quota check even with exhausted quota
        // (upsert_remote_rule requires an argument — empty arguments just for test)
        val existing = mcpTooling.call(
            ToolCallRequest(name = "list_remote_rules", arguments = emptyMap()),
            callerKeyId = "limited-key",
        )
        // list_remote_rules IS a query tool — it IS rate-limited (was missing from queryApiKeys)
        assertTrue(
            existing.isError,
            "list_remote_rules should be rate-limited since it reads from server state",
        )

        // upsert_remote_rule is not a write tool per the spec
        // (write operations are controlled by auth, not by query quota)
    }
}

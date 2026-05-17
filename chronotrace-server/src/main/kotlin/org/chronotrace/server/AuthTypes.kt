package org.chronotrace.server

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Per-key quota configuration.
 *
 * Used to rate-limit API key usage. When a key's quota is exhausted within
 * the window, subsequent requests return 429 Too Many Requests.
 *
 * @param limit Maximum number of requests allowed in the window.
 * @param windowSeconds Length of the rolling window in seconds.
 */
@Serializable
data class ApiKeyQuota(
    val limit: Int,
    val windowSeconds: Int,
)

/**
 * Metadata for an API key (or bearer token).
 *
 * Stored in ChronoStore and used for:
 * - Quota tracking and enforcement
 * - Role-based access control (admin vs client)
 * - Key lifecycle (created, rotated, revoked)
 * - App-level key isolation (appId filter)
 *
 * @param keyId Unique identifier for this key (stable across rotations).
 *              For API keys: the key value itself (first 16 chars shown in UI).
 *              For bearer tokens: "bearer:<token>" (token never exposed after create).
 * @param createdAtUtc UTC epoch millis when the key was created.
 * @param rotatedAtUtc UTC epoch millis when the key was last rotated. Null if never rotated.
 * @param revokedAtUtc UTC epoch millis when the key was revoked. Null if still active.
 * @param role Either "admin" (can manage other keys + all endpoints) or "client" (endpoints only).
 * @param quota Optional per-key rate limit. Null means no limit.
 * @param appId Optional app scope — if set, this key only works for that app's data.
 */
@Serializable
data class ApiKeyMetadata(
    val keyId: String,
    val createdAtUtc: Long,
    val rotatedAtUtc: Long? = null,
    val revokedAtUtc: Long? = null,
    val role: String = "client", // "admin" | "client"
    val quota: ApiKeyQuota? = null,
    val appId: String? = null,
) {
    val isRevoked: Boolean get() = revokedAtUtc != null
    val isAdmin: Boolean get() = role == "admin"
}

/**
 * A single audit log entry recording a request to a protected endpoint.
 *
 * Audit logs are written on every auth-protected endpoint call (ingest, search,
 * MCP, remote-rules, purge) and on auth failures (missing/wrong key).
 * They are stored in ClickHouse (shared mode) and queryable via
 * GET /api/v1/admin/audit/logs.
 *
 * @param entryId Unique identifier for this entry.
 * @param timestampUtc UTC epoch millis when the request was processed.
 * @param apiKeyId The keyId that made the request (hashed/redacted for auth failures).
 * @param action The operation performed (e.g. "ingest", "search", "mcp_tools_call").
 * @param endpoint The HTTP path (e.g. "/api/v1/ingest").
 * @param method HTTP method (e.g. "POST", "GET").
 * @param outcome One of: "success", "unauthorized", "quota_exceeded", "forbidden", "error".
 * @param statusCode HTTP status code returned to the client.
 * @param requestSizeBytes Size of the request body in bytes (0 if no body).
 * @param responseSizeBytes Size of the response body in bytes (0 if no body).
 * @param durationMs Time spent processing the request in milliseconds.
 * @param appId The appId from the request's ClientMetadata (null if not available).
 * @param sdkInstanceId The SDK instance that made the request (from ClientMetadata).
 * @param traceId The traceId from the request context (for correlated logging).
 * @param ipAddress Client IP address (extracted from X-Forwarded-For or direct connection).
 */
@Serializable
data class AuditLogEntry(
    val entryId: String,
    val timestampUtc: Long,
    val apiKeyId: String,
    val action: String,
    val endpoint: String,
    val method: String,
    val outcome: String, // "success" | "unauthorized" | "quota_exceeded" | "forbidden" | "error"
    val statusCode: Int,
    val requestSizeBytes: Long = 0,
    val responseSizeBytes: Long = 0,
    val durationMs: Long = 0,
    val appId: String? = null,
    val sdkInstanceId: String? = null,
    val traceId: String? = null,
    val ipAddress: String? = null,
)

/**
 * Request filters for querying audit log entries.
 *
 * @param apiKeyId Filter to entries made by a specific key.
 * @param action Filter to entries for a specific action (e.g. "ingest", "purge").
 * @param outcome Filter to entries with a specific outcome.
 * @param startTimeUtc Inclusive lower bound on timestamp (UTC epoch millis).
 * @param endTimeUtc Inclusive upper bound on timestamp (UTC epoch millis).
 * @param appId Filter to entries for a specific app.
 * @param limit Max entries to return (default 100, max 1000).
 * @param cursor Opaque cursor for pagination (from previous response nextCursor).
 */
@Serializable
data class AuditLogQuery(
    val apiKeyId: String? = null,
    val action: String? = null,
    val outcome: String? = null,
    val startTimeUtc: Long? = null,
    val endTimeUtc: Long? = null,
    val appId: String? = null,
    val limit: Int = 100,
    val cursor: String? = null,
)

/**
 * Paginated audit log response.
 *
 * @param entries The matching audit entries (newest first).
 * @param nextCursor Opaque cursor to fetch the next page. Null when no more results.
 */
@Serializable
data class AuditLogResponse(
    val entries: List<AuditLogEntry>,
    val nextCursor: String? = null,
)

/**
 * Thrown when an API key's quota is exceeded.
 *
 * The HTTP layer catches this and returns 429 Too Many Requests with
 * standard rate-limit headers.
 *
 * @param retryAfterSeconds Seconds the client should wait before retrying (0 = don't retry).
 * @param limit The configured limit for this key.
 * @param remaining Requests remaining in the current window.
 * @param windowSeconds The quota window length.
 */
@Serializable
data class QuotaExceeded(
    val retryAfterSeconds: Int,
    val limit: Int,
    val remaining: Int,
    val windowSeconds: Int,
)

/**
 * Sliding-window per-key quota tracker.
 *
 * Uses a lock-free approach with per-key ring buffers of timestamps.
 * Each key has its own tracked timestamps; expired timestamps are lazily pruned
 * on each check so memory stays bounded.
 *
 * Thread-safe for concurrent use across multiple request threads.
 */
class QuotaTracker(
    private val keyRegistry: ConcurrentHashMap<String, ApiKeyMetadata>,
) {
    // Per-key list of request timestamps (UTC epoch millis).
    // Timestamps are kept sorted; expired ones (> windowSeconds old) are pruned on access.
    private val windows = ConcurrentHashMap<String, MutableList<Long>>()

    /**
     * Check whether a key is within its quota.
     *
     * Returns null if the request is allowed.
     * Returns [QuotaExceeded] if the key has exceeded its quota in the current window.
     */
    @Synchronized
    fun checkQuota(keyId: String): QuotaExceeded? {
        val metadata = keyRegistry[keyId] ?: return null // unknown — caller handles auth
        val quota = metadata.quota ?: return null // no quota configured

        val now = System.currentTimeMillis()
        val window = windows.getOrPut(keyId) { mutableListOf() }

        // Prune timestamps outside the window
        val windowStart = now - TimeUnit.SECONDS.toMillis(quota.windowSeconds.toLong())
        window.removeIf { it < windowStart }

        return if (window.size >= quota.limit) {
            // Find when the oldest request in the window was made
            val oldestTimestamp = window.minOrNull() ?: now
            val retryAfter = ((oldestTimestamp + TimeUnit.SECONDS.toMillis(quota.windowSeconds.toLong()) - now) / 1000).toInt().coerceAtLeast(1)
            QuotaExceeded(
                retryAfterSeconds = retryAfter,
                limit = quota.limit,
                remaining = 0,
                windowSeconds = quota.windowSeconds,
            )
        } else {
            null
        }
    }

    /**
     * Record a successful request for quota accounting.
     * Must be called after a successful (2xx) response.
     */
    @Synchronized
    fun recordRequest(keyId: String) {
        val metadata = keyRegistry[keyId] ?: return
        val quota = metadata.quota ?: return

        val now = System.currentTimeMillis()
        val window = windows.getOrPut(keyId) { mutableListOf() }

        // Prune old timestamps before adding the new one
        val windowStart = now - TimeUnit.SECONDS.toMillis(quota.windowSeconds.toLong())
        window.removeIf { it < windowStart }
        window.add(now)
    }
}
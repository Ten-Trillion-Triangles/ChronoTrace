package org.chronotrace.server

import java.nio.file.Path

enum class StorageMode {
    FILE,
    CLICKHOUSE,
}

data class ClickHouseConfig(
    val jdbcUrl: String,
    val database: String = "chronotrace",
    val username: String? = null,
    val password: String? = null,
    val connectTimeoutMs: Int = 5_000,
    /** Size of the bounded ingest queue. 0 = disabled (sync writes). */
    val ingestQueueCapacity: Int = 0,
    /** How long to wait for queue insertion before treating it as full (ms). */
    val ingestQueueTimeoutMs: Long = 5_000,
    /** Append ?async_insert=1&wait_for_async_insert=0 to JDBC URL for lower-latency async ingest. */
    val asyncInsert: Boolean = false,
    /** If true, return HTTP 503 when the ingest queue is full. If false, drop events silently. */
    val bounceOnRejected: Boolean = true,
)

data class ValkeyConfig(
    val host: String,
    val port: Int = 6379,
    val database: Int = 0,
    val password: String? = null,
    val keyPrefix: String = "chronotrace",
)

/**
 * Runtime options controlling the ChronoTrace store behavior.
 */
data class ChronoStoreOptions(
    val storageMode: StorageMode = StorageMode.FILE,
    val dataDir: Path? = null,
    val retentionDaysLogs: Long = 30,
    val retentionDaysSpans: Long = 30,
    val retentionDaysFrames: Long = 7,
    val clickHouse: ClickHouseConfig? = null,
    val valkey: ValkeyConfig? = null,
    /** Allowed API keys for apiKey auth mode (set of key values). */
    val apiKeys: Set<String> = emptySet(),
    /** Bearer tokens for bearer auth mode (comma-separated list). */
    val bearerTokens: Set<String> = emptySet(),
    /**
     * Metadata for API keys and bearer tokens.
     * Key: keyId (for apiKey mode: the key value itself; for bearer mode: "bearer:<token>").
     * Only keys present in this map are tracked for quota and role enforcement.
     * If a key in `apiKeys` has no entry here, it is treated as unlimited and client role.
     */
    val keyMetadata: Map<String, ApiKeyMetadata> = emptyMap(),
    /**
     * WebSocket idle timeout in milliseconds. If a connection remains open without
     * any frames exchanged for this duration, the server sends a ping. If the pong
     * is not received within the timeout, the connection is closed. Set to 0 to
     * disable idle timeout (connections never timeout on the server side).
     * Default: 60_000 (60 seconds).
     */
    val wsIdleTimeoutMs: Long = 60_000,
    /**
     * Size of the bounded thread pool for async purge job execution.
     * Defaults to 1 (serial execution). Values > 1 enable concurrent purge jobs.
     * Setting this > 1 does not change the ordering guarantee of state transitions
     * within a single job, but allows multiple jobs to be in-flight simultaneously.
     */
    val purgeThreadPoolSize: Int = 1,
)

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
)

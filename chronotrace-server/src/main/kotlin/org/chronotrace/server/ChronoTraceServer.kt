package org.chronotrace.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.nio.file.Paths

fun main() {
    val host = System.getenv("CHRONOTRACE_BIND_HOST") ?: "127.0.0.1"
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val authMode = System.getenv("CHRONOTRACE_AUTH_MODE") ?: "none"
    val storageMode = System.getenv("CHRONOTRACE_STORAGE_MODE")?.uppercase()?.let(StorageMode::valueOf) ?: StorageMode.FILE
    val bearerTokens = System.getenv("CHRONOTRACE_BEARER_TOKENS")
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.toSet()
        ?: emptySet()
    val options = ChronoStoreOptions(
        storageMode = storageMode,
        dataDir = System.getenv("CHRONOTRACE_DATA_DIR")?.let(Paths::get),
        retentionDaysLogs = System.getenv("CHRONOTRACE_RETENTION_LOGS_DAYS")?.toLongOrNull() ?: 30L,
        retentionDaysSpans = System.getenv("CHRONOTRACE_RETENTION_SPANS_DAYS")?.toLongOrNull() ?: 30L,
        retentionDaysFrames = System.getenv("CHRONOTRACE_RETENTION_FRAMES_DAYS")?.toLongOrNull() ?: 7L,
        apiKeys = System.getenv("CHRONOTRACE_API_KEYS")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet(),
        bearerTokens = bearerTokens,
        clickHouse = System.getenv("CHRONOTRACE_CLICKHOUSE_JDBC_URL")?.let { jdbcUrl ->
            ClickHouseConfig(
                jdbcUrl = jdbcUrl,
                database = System.getenv("CHRONOTRACE_CLICKHOUSE_DATABASE") ?: "chronotrace",
                username = System.getenv("CHRONOTRACE_CLICKHOUSE_USERNAME"),
                password = System.getenv("CHRONOTRACE_CLICKHOUSE_PASSWORD"),
                connectTimeoutMs = System.getenv("CHRONOTRACE_CLICKHOUSE_CONNECT_TIMEOUT_MS")?.toIntOrNull() ?: 5_000,
                ingestQueueCapacity = System.getenv("CHRONOTRACE_CLICKHOUSE_INGEST_QUEUE_CAPACITY")?.toIntOrNull() ?: 0,
                ingestQueueTimeoutMs = System.getenv("CHRONOTRACE_CLICKHOUSE_INGEST_QUEUE_TIMEOUT_MS")?.toLongOrNull() ?: 5_000L,
                asyncInsert = System.getenv("CHRONOTRACE_ASYNC_INSERT")?.lowercase() == "true",
                bounceOnRejected = System.getenv("CHRONOTRACE_BOUNCE_ON_REJECTED")?.lowercase() != "false", // default true
            )
        },
        valkey = System.getenv("CHRONOTRACE_VALKEY_HOST")?.let { hostName ->
            ValkeyConfig(
                host = hostName,
                port = System.getenv("CHRONOTRACE_VALKEY_PORT")?.toIntOrNull() ?: 6379,
                database = System.getenv("CHRONOTRACE_VALKEY_DATABASE")?.toIntOrNull() ?: 0,
                password = System.getenv("CHRONOTRACE_VALKEY_PASSWORD"),
                keyPrefix = System.getenv("CHRONOTRACE_VALKEY_KEY_PREFIX") ?: "chronotrace",
            )
        },
        wsIdleTimeoutMs = System.getenv("CHRONOTRACE_WS_IDLE_TIMEOUT_MS")?.toLongOrNull() ?: 60_000L,
    )

    embeddedServer(Netty, port = port, host = host) {
        chronoTraceModule(ChronoStore(authMode = authMode, options = options))
    }.start(wait = true)
}

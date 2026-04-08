package com.chronotrace.sdk

import org.chronotrace.contract.LogLevel

enum class OverflowStrategy {
    DROP_OLDEST,
    DROP_NEWEST,
}

enum class RuntimeState {
    CONNECTED,
    DEGRADED_BUFFERING,
    RECONNECT_BACKOFF,
    LOCAL_FALLBACK,
    FATAL_FLUSH,
}

data class CaptureConfig(
    val autoCaptureLevels: Set<LogLevel> = setOf(LogLevel.ERROR, LogLevel.FATAL),
    val maxCollectionEntries: Int = 50,
    val maxStringLength: Int = 4_096,
    val maxPayloadBytes: Int = 256 * 1024,
    val maxSerializationDepth: Int = 3,
    val maskingKeys: List<Regex> = listOf(Regex("password", RegexOption.IGNORE_CASE), Regex("token", RegexOption.IGNORE_CASE)),
    val maskingValues: List<Regex> = emptyList(),
    val allowFieldPatterns: List<Regex> = emptyList(),
)

data class BufferConfig(
    val maxEntries: Int = 512,
    val overflowStrategy: OverflowStrategy = OverflowStrategy.DROP_OLDEST,
)

data class ChronoConfig(
    val appId: String,
    val serviceName: String,
    val environment: String = "local",
    val sdkInstanceId: String = ChronoIds.nextId("sdk"),
    val captureConfig: CaptureConfig = CaptureConfig(),
    val bufferConfig: BufferConfig = BufferConfig(),
    val transport: ChronoTransport = NoopTransport,
)

data class ChronoSpanContext(
    val traceId: String,
    val spanId: String,
    val parentSpanId: String? = null,
)

data class RuntimeHealth(
    val state: RuntimeState,
    val droppedLogs: Int,
    val droppedSpans: Int,
    val droppedFrames: Int,
    val bufferedLogs: Int,
    val bufferedSpans: Int,
    val bufferedFrames: Int,
    val fatalFlushes: Int,
    val lastFlushError: String? = null,
)

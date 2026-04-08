package com.chronotrace.sdk

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.chronotrace.contract.ClientMetadata
import org.chronotrace.contract.CaptureReason
import org.chronotrace.contract.FrameSnapshot
import org.chronotrace.contract.IngestBatch
import org.chronotrace.contract.LogLevel
import org.chronotrace.contract.LogRecord
import org.chronotrace.contract.SpanRecord
import org.chronotrace.contract.SpanStatus

internal class ChronoRuntime(
    private val config: ChronoConfig,
) {
    private val lock = Mutex()
    private val logBuffer = ChronoBuffer<LogRecord>(config.bufferConfig.maxEntries, config.bufferConfig.overflowStrategy)
    private val spanBuffer = ChronoBuffer<SpanRecord>(config.bufferConfig.maxEntries, config.bufferConfig.overflowStrategy)
    private val frameBuffer = ChronoBuffer<FrameSnapshot>(config.bufferConfig.maxEntries, config.bufferConfig.overflowStrategy)
    private var state = if (config.transport === NoopTransport) RuntimeState.LOCAL_FALLBACK else RuntimeState.RECONNECT_BACKOFF
    private var droppedLogs = 0
    private var droppedSpans = 0
    private var droppedFrames = 0
    private var fatalFlushes = 0
    private var lastFlushError: String? = null

    init {
        ChronoRuntimeHooks.install(this)
    }

    suspend fun log(level: LogLevel, message: String, fields: Map<String, Any?> = emptyMap()) {
        val splitFields = splitCaptureFields(fields)
        val captureReason = resolveCaptureReason(level, splitFields.logFields)
        val current = resolveContext(captureReason)
        val timestampUtc = ChronoPlatform.nowMillis()
        val sequenceId = ChronoIds.nextSequence()
        val logId = ChronoIds.nextId("log")
        val linkedFrameId = if (captureReason != null && current != null) {
            val frame = ChronoCapture.createFrameSnapshot(
                config = config,
                traceContext = current,
                timestampUtc = timestampUtc,
                sequenceId = ChronoIds.nextSequence(),
                captureReason = captureReason,
                fields = splitFields.logFields,
                captureLocals = splitFields.captureLocals,
                logId = logId,
            )
            lock.withLock {
                droppedFrames += frameBuffer.offer(frame)
            }
            frame.frameId
        } else {
            null
        }
        val event = LogRecord(
            logId = logId,
            appId = config.appId,
            environment = config.environment,
            sdkInstanceId = config.sdkInstanceId,
            serviceName = config.serviceName,
            traceId = current?.traceId,
            spanId = current?.spanId,
            parentSpanId = current?.parentSpanId,
            timestampUtc = timestampUtc,
            level = level,
            message = message,
            sequenceId = sequenceId,
            fields = ChronoCapture.sanitizeLogFields(config.captureConfig, splitFields.logFields),
            captureReason = captureReason,
            linkedFrameId = linkedFrameId,
        )
        lock.withLock {
            droppedLogs += logBuffer.offer(event)
        }
        flush()
    }

    suspend fun startSpan(name: String, captureLocals: Map<String, Any?> = emptyMap()): SpanHandle {
        val previous = ChronoContextStorage.current()
        val spanId = ChronoIds.nextId("span")
        val traceId = previous?.traceId ?: ChronoIds.nextId("trace")
        val timestampUtc = ChronoPlatform.nowMillis()
        val event = SpanRecord(
            spanId = spanId,
            traceId = traceId,
            appId = config.appId,
            environment = config.environment,
            serviceName = config.serviceName,
            operationName = name,
            parentSpanId = previous?.spanId,
            startTimeUtc = timestampUtc,
        )
        lock.withLock {
            droppedSpans += spanBuffer.offer(event)
        }
        val context = ChronoSpanContext(traceId, spanId, previous?.spanId)
        if (captureLocals.isNotEmpty()) {
            lock.withLock {
                droppedFrames += frameBuffer.offer(
                    ChronoCapture.createFrameSnapshot(
                        config = config,
                        traceContext = context,
                        timestampUtc = timestampUtc,
                        sequenceId = ChronoIds.nextSequence(),
                        captureReason = CaptureReason.MANUAL_TRACE,
                        captureLocals = captureLocals,
                    ),
                )
            }
        }
        ChronoContextStorage.set(context)
        flush()
        return SpanHandle(this, event, previous)
    }

    suspend fun endSpan(span: SpanRecord, previous: ChronoSpanContext?) {
        val closed = span.copy(
            endTimeUtc = ChronoPlatform.nowMillis(),
            status = SpanStatus.OK,
        )
        lock.withLock {
            droppedSpans += spanBuffer.offer(closed)
        }
        ChronoContextStorage.set(previous)
        flush()
    }

    suspend fun flush() {
        flushInternal(fatal = false)
    }

    suspend fun flushFatal() {
        lock.withLock {
            fatalFlushes += 1
            state = RuntimeState.FATAL_FLUSH
        }
        flushInternal(fatal = true)
    }

    suspend fun healthSnapshot(): RuntimeHealth = lock.withLock {
        RuntimeHealth(
            state = state,
            droppedLogs = droppedLogs,
            droppedSpans = droppedSpans,
            droppedFrames = droppedFrames,
            bufferedLogs = logBuffer.size(),
            bufferedSpans = spanBuffer.size(),
            bufferedFrames = frameBuffer.size(),
            fatalFlushes = fatalFlushes,
            lastFlushError = lastFlushError,
        )
    }

    private suspend fun flushInternal(fatal: Boolean) {
        val batch = lock.withLock {
            IngestBatch(
                client = ClientMetadata(
                    appId = config.appId,
                    environment = config.environment,
                    sdkInstanceId = config.sdkInstanceId,
                    serviceName = config.serviceName,
                ),
                logs = logBuffer.drain(),
                spans = spanBuffer.drain(),
                frameSnapshots = frameBuffer.drain(),
            )
        }
        if (batch.logs.isEmpty() && batch.spans.isEmpty() && batch.frameSnapshots.isEmpty()) {
            return
        }
        try {
            config.transport.send(batch)
            lock.withLock {
                lastFlushError = null
                state = if (config.transport === NoopTransport) RuntimeState.LOCAL_FALLBACK else RuntimeState.CONNECTED
            }
        } catch (error: Throwable) {
            lock.withLock {
                droppedLogs += logBuffer.prependAll(batch.logs)
                droppedSpans += spanBuffer.prependAll(batch.spans)
                droppedFrames += frameBuffer.prependAll(batch.frameSnapshots)
                lastFlushError = error.message ?: error::class.simpleName ?: "unknown"
                state = when {
                    fatal -> RuntimeState.FATAL_FLUSH
                    config.transport === NoopTransport -> RuntimeState.LOCAL_FALLBACK
                    else -> RuntimeState.DEGRADED_BUFFERING
                }
            }
        }
    }

    private fun resolveCaptureReason(level: LogLevel, fields: Map<String, Any?>): CaptureReason? {
        return if (config.captureConfig.autoCaptureLevels.contains(level)) {
            CaptureReason.AUTO_CAPTURE_LEVEL
        } else if (fields.isNotEmpty()) {
            null
        } else {
            null
        }
    }

    private fun resolveContext(captureReason: CaptureReason?): ChronoSpanContext? {
        val current = ChronoContextStorage.current()
        if (current != null || captureReason == null) {
            return current
        }
        return ChronoSpanContext(
            traceId = ChronoIds.nextId("trace"),
            spanId = ChronoIds.nextId("span"),
            parentSpanId = null,
        )
    }
}

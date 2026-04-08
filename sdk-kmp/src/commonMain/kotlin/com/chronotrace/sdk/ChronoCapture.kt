package com.chronotrace.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.chronotrace.contract.CaptureReason
import org.chronotrace.contract.FrameSnapshot
import org.chronotrace.contract.SerializationMetadata

internal const val InternalCaptureLocalsKey = "__chronotrace_locals"

private const val TruncatedMarker = "[Truncated]"
private const val CircularMarker = "[Circular]"
private const val RedactedMarker = "[REDACTED]"
private const val UndefinedMarker = "[Undefined]"

private val json = Json { encodeDefaults = true }

internal data class CaptureFieldSplit(
    val logFields: Map<String, Any?>,
    val captureLocals: Map<String, Any?>?,
)

private data class SerializationState(
    var truncated: Boolean = false,
    var maxDepthReached: Boolean = false,
    val redactedFields: MutableSet<String> = linkedSetOf(),
    val droppedFields: MutableSet<String> = linkedSetOf(),
)

internal fun splitCaptureFields(fields: Map<String, Any?>): CaptureFieldSplit {
    if (fields.isEmpty()) {
        return CaptureFieldSplit(emptyMap(), null)
    }
    val captureLocals = fields[InternalCaptureLocalsKey] as? Map<String, Any?>
    val publicFields = fields - InternalCaptureLocalsKey
    return CaptureFieldSplit(publicFields, captureLocals)
}

internal fun mergeCaptureFields(
    fields: Map<String, Any?> = emptyMap(),
    captureLocals: Map<String, Any?>,
): Map<String, Any?> {
    if (captureLocals.isEmpty()) {
        return fields
    }
    return fields + mapOf(InternalCaptureLocalsKey to captureLocals)
}

internal object ChronoCapture {
    fun sanitizeLogFields(config: CaptureConfig, fields: Map<String, Any?>): Map<String, String> {
        if (fields.isEmpty()) {
            return emptyMap()
        }
        val state = SerializationState()
        val serialized = serializeValue(config, fields, state, "", mutableSetOf(), 0)
        val objectValue = serialized as? JsonObject ?: return emptyMap()
        return objectValue.mapValues { (_, value) ->
            when (value) {
                is JsonPrimitive -> value.content
                else -> json.encodeToString(JsonElement.serializer(), value)
            }
        }
    }

    fun createFrameSnapshot(
        config: ChronoConfig,
        traceContext: ChronoSpanContext,
        timestampUtc: Long,
        sequenceId: Long,
        captureReason: CaptureReason,
        fields: Map<String, Any?> = emptyMap(),
        captureLocals: Map<String, Any?>? = null,
        logId: String? = null,
    ): FrameSnapshot {
        val state = SerializationState()
        val locals = captureLocals ?: fields
        var localsJson = json.encodeToString(
            JsonElement.serializer(),
            serializeValue(config.captureConfig, locals, state, "", mutableSetOf(), 0),
        )
        if (localsJson.encodeToByteArray().size > config.captureConfig.maxPayloadBytes) {
            state.truncated = true
            state.droppedFields += "\$payload"
            localsJson = json.encodeToString(JsonPrimitive(TruncatedMarker))
        }

        return FrameSnapshot(
            frameId = ChronoIds.nextId("frame"),
            traceId = traceContext.traceId,
            spanId = traceContext.spanId,
            appId = config.appId,
            environment = config.environment,
            sdkInstanceId = config.sdkInstanceId,
            serviceName = config.serviceName,
            timestampUtc = timestampUtc,
            sequenceId = sequenceId,
            captureReason = captureReason,
            callStack = captureCallStack(skipFrames = 1),
            localsJson = localsJson,
            serializationMetadata = SerializationMetadata(
                truncated = state.truncated,
                maxDepthReached = state.maxDepthReached,
                redactedFields = state.redactedFields.toList(),
                droppedFields = state.droppedFields.toList(),
            ),
            logId = logId,
        )
    }

    private fun serializeValue(
        config: CaptureConfig,
        value: Any?,
        state: SerializationState,
        path: String,
        seen: MutableSet<Any>,
        depth: Int,
    ): JsonElement {
        if (!matchesAllowList(config, path)) {
            state.truncated = true
            if (path.isNotEmpty()) {
                state.droppedFields += path
            }
            return JsonPrimitive(TruncatedMarker)
        }
        if (depth > config.maxSerializationDepth) {
            state.truncated = true
            state.maxDepthReached = true
            if (path.isNotEmpty()) {
                state.droppedFields += path
            }
            return JsonPrimitive(TruncatedMarker)
        }
        return when (value) {
            null -> JsonNull
            is String -> {
                if (config.maskingValues.any { it.containsMatchIn(value) }) {
                    if (path.isNotEmpty()) {
                        state.redactedFields += path
                    }
                    JsonPrimitive(RedactedMarker)
                } else {
                    JsonPrimitive(value.take(config.maxStringLength))
                }
            }
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Char -> JsonPrimitive(value.toString())
            is Map<*, *> -> serializeMap(config, value, state, path, seen, depth)
            is Iterable<*> -> serializeIterable(config, value.toList(), state, path, seen, depth)
            is Array<*> -> serializeIterable(config, value.toList(), state, path, seen, depth)
            is Throwable -> JsonObject(
                mapOf(
                    "name" to JsonPrimitive(value::class.simpleName ?: "Throwable"),
                    "message" to JsonPrimitive(value.message ?: ""),
                ),
            )
            else -> {
                if (!seen.add(value)) {
                    if (path.isNotEmpty()) {
                        state.droppedFields += path
                    }
                    JsonPrimitive(CircularMarker)
                } else {
                    JsonPrimitive(value.toString().take(config.maxStringLength).ifEmpty { UndefinedMarker })
                }
            }
        }
    }

    private fun serializeMap(
        config: CaptureConfig,
        value: Map<*, *>,
        state: SerializationState,
        path: String,
        seen: MutableSet<Any>,
        depth: Int,
    ): JsonObject {
        if (!seen.add(value)) {
            if (path.isNotEmpty()) {
                state.droppedFields += path
            }
            return JsonObject(mapOf("circular" to JsonPrimitive(CircularMarker)))
        }
        val entries = value.entries.take(config.maxCollectionEntries)
        if (value.size > config.maxCollectionEntries && path.isNotEmpty()) {
            state.truncated = true
            state.droppedFields += path
        }
        return JsonObject(
            entries.associate { (rawKey, entryValue) ->
                val key = rawKey?.toString() ?: "null"
                val nextPath = if (path.isEmpty()) key else "$path.$key"
                if (config.maskingKeys.any { it.containsMatchIn(key) }) {
                    state.redactedFields += nextPath
                    key to JsonPrimitive(RedactedMarker)
                } else {
                    key to serializeValue(config, entryValue, state, nextPath, seen, depth + 1)
                }
            },
        )
    }

    private fun serializeIterable(
        config: CaptureConfig,
        value: List<*>,
        state: SerializationState,
        path: String,
        seen: MutableSet<Any>,
        depth: Int,
    ): JsonArray {
        val limited = value.take(config.maxCollectionEntries)
        if (value.size > config.maxCollectionEntries && path.isNotEmpty()) {
            state.truncated = true
            state.droppedFields += path
        }
        return JsonArray(
            limited.mapIndexed { index, entry ->
                serializeValue(config, entry, state, "$path[$index]", seen, depth + 1)
            },
        )
    }

    private fun matchesAllowList(config: CaptureConfig, path: String): Boolean {
        if (path.isEmpty() || config.allowFieldPatterns.isEmpty()) {
            return true
        }
        return config.allowFieldPatterns.any { it.containsMatchIn(path) }
    }
}

internal expect fun captureCallStack(skipFrames: Int = 0): List<org.chronotrace.contract.CallStackItem>

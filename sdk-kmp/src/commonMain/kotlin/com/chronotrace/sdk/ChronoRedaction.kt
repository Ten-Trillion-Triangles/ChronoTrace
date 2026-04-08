package com.chronotrace.sdk

internal object ChronoRedaction {
    fun sanitize(config: CaptureConfig, fields: Map<String, Any?>): Map<String, String> {
        return fields.mapValues { (key, value) ->
            val raw = value?.toString() ?: "null"
            if (config.maskingKeys.any { it.containsMatchIn(key) } || config.maskingValues.any { it.containsMatchIn(raw) }) {
                "[REDACTED]"
            } else {
                raw
            }
        }
    }
}


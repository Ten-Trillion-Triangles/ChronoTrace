package org.chronotrace.server

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.chronotrace.contract.SearchLogsRequest
import org.chronotrace.contract.ToolCallRequest
import org.chronotrace.contract.ToolCallResponse
import org.chronotrace.contract.ToolDescriptor

class McpTooling(
    private val store: ChronoStore,
    private val json: Json,
) {
    fun descriptors(): List<ToolDescriptor> = listOf(
        ToolDescriptor(
            name = "search_logs",
            description = "Search logs with filters",
            inputSchema = """{
  "type": "object",
  "properties": {
    "appId": { "type": "string", "description": "Filter by application ID" },
    "environment": { "type": "string", "description": "Filter by environment (e.g. 'local', 'prod')" },
    "textQuery": { "type": "string", "description": "Full-text search within log messages (case-insensitive substring match)" },
    "traceId": { "type": "string", "description": "Filter logs belonging to a specific trace" },
    "spanId": { "type": "string", "description": "Filter logs belonging to a specific span" },
    "level": { "type": "string", "enum": ["TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL"], "description": "Filter by log level" },
    "startTimeUtc": { "type": "integer", "description": "Unix timestamp (ms) — logs at or after this time" },
    "endTimeUtc": { "type": "integer", "description": "Unix timestamp (ms) — logs at or before this time" },
    "hasFrame": { "type": "boolean", "description": "If true, return only logs that have an associated frame snapshot" },
    "cursor": { "type": "string", "description": "Opaque cursor for pagination — pass nextCursor from previous response" },
    "limit": { "type": "integer", "description": "Maximum number of logs to return (1-500, default 100)", "default": 100 }
  },
  "additionalProperties": false
}""",
            outputSchema = """{
  "type": "object",
  "properties": {
    "items": {
      "type": "array",
      "description": "Array of matched LogRecord objects, newest-first per timestamp+sequence",
      "items": {
        "type": "object",
        "properties": {
          "logId": { "type": "string" },
          "appId": { "type": "string" },
          "environment": { "type": "string" },
          "sdkInstanceId": { "type": "string" },
          "serviceName": { "type": "string" },
          "traceId": { "type": ["string", "null"] },
          "spanId": { "type": ["string", "null"] },
          "parentSpanId": { "type": ["string", "null"] },
          "timestampUtc": { "type": "integer", "description": "Unix timestamp in milliseconds" },
          "sequenceId": { "type": "integer" },
          "level": { "type": "string", "enum": ["TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL"] },
          "message": { "type": "string" },
          "fields": { "type": "object", "additionalProperties": { "type": "string" } },
          "captureReason": { "type": ["string", "null"], "enum": ["manual_trace", "auto_capture_level", "remote_rule", "crash_flush"] },
          "linkedFrameId": { "type": ["string", "null"] },
          "triggeredRuleId": { "type": ["string", "null"], "description": "The ruleId of the RemoteRule that triggered this log's capture. null for non-rule captures." }
        },
        "required": ["logId", "appId", "environment", "sdkInstanceId", "serviceName", "timestampUtc", "sequenceId", "level", "message"]
      }
    },
    "nextCursor": { "type": ["string", "null"], "description": "Pass this as 'cursor' to fetch the next page. null when no more results" }
  },
  "required": ["items", "nextCursor"]
}""",
        ),
        ToolDescriptor(
            name = "get_log",
            description = "Fetch a single log by its logId",
            inputSchema = """{
  "type": "object",
  "properties": {
    "logId": { "type": "string", "description": "Unique identifier of the log record" }
  },
  "required": ["logId"],
  "additionalProperties": false
}""",
            outputSchema = """{
  "type": "object",
  "properties": {
    "logId": { "type": "string" },
    "appId": { "type": "string" },
    "environment": { "type": "string" },
    "sdkInstanceId": { "type": "string" },
    "serviceName": { "type": "string" },
    "traceId": { "type": ["string", "null"] },
    "spanId": { "type": ["string", "null"] },
    "parentSpanId": { "type": ["string", "null"] },
    "timestampUtc": { "type": "integer" },
    "sequenceId": { "type": "integer" },
    "level": { "type": "string" },
    "message": { "type": "string" },
    "fields": { "type": "object", "additionalProperties": { "type": "string" } },
    "captureReason": { "type": ["string", "null"] },
    "linkedFrameId": { "type": ["string", "null"] },
    "triggeredRuleId": { "type": ["string", "null"], "description": "The ruleId of the RemoteRule that triggered this log's capture. null for non-rule captures." }
  },
  "required": ["logId", "appId", "environment", "sdkInstanceId", "serviceName", "timestampUtc", "sequenceId", "level", "message"],
  "description": "A single LogRecord, or null if not found"
}""",
        ),
        ToolDescriptor(
            name = "get_frame_snapshot",
            description = "Fetch a frame snapshot by frameId or logId",
            inputSchema = """{
  "type": "object",
  "properties": {
    "frameId": { "type": "string", "description": "Unique frame identifier. Provide frameId OR logId, not both" },
    "logId": { "type": "string", "description": "Log record ID whose linked frame snapshot to retrieve. Provide logId OR frameId, not both" }
  },
  "additionalProperties": false,
  "description": "At least one of frameId or logId must be provided"
}""",
            outputSchema = """{
  "type": "object",
  "properties": {
    "frameId": { "type": "string" },
    "traceId": { "type": "string" },
    "spanId": { "type": "string" },
    "appId": { "type": "string" },
    "environment": { "type": "string" },
    "sdkInstanceId": { "type": "string" },
    "serviceName": { "type": "string" },
    "timestampUtc": { "type": "integer" },
    "sequenceId": { "type": "integer" },
    "captureReason": { "type": "string", "enum": ["manual_trace", "auto_capture_level", "remote_rule", "crash_flush"] },
    "callStack": {
      "type": "array",
      "description": "Active call stack at capture time",
      "items": {
        "type": "object",
        "properties": {
          "functionName": { "type": "string" },
          "filePath": { "type": "string" },
          "lineNumber": { "type": "integer" },
          "columnNumber": { "type": ["integer", "null"] }
        },
        "required": ["functionName", "filePath", "lineNumber"]
      }
    },
    "localsJson": { "type": "string", "description": "JSON-encoded local variables at the capture point" },
    "serializationMetadata": {
      "type": "object",
      "properties": {
        "truncated": { "type": "boolean" },
        "maxDepthReached": { "type": "boolean" },
        "redactedFields": { "type": "array", "items": { "type": "string" } },
        "droppedFields": { "type": "array", "items": { "type": "string" } }
      }
    },
    "logId": { "type": ["string", "null"] }
  },
  "required": ["frameId", "traceId", "spanId", "appId", "environment", "sdkInstanceId", "serviceName", "timestampUtc", "sequenceId", "captureReason", "callStack", "localsJson"]
}""",
        ),
        ToolDescriptor(
            name = "get_trace",
            description = "Fetch a complete trace: all spans, logs, and frame snapshots for a traceId",
            inputSchema = """{
  "type": "object",
  "properties": {
    "traceId": { "type": "string", "description": "Unique trace identifier" }
  },
  "required": ["traceId"],
  "additionalProperties": false
}""",
            outputSchema = """{
  "type": "object",
  "properties": {
    "traceId": { "type": "string" },
    "spans": {
      "type": "array",
      "description": "All spans in this trace, sorted by startTimeUtc",
      "items": {
        "type": "object",
        "properties": {
          "spanId": { "type": "string" },
          "traceId": { "type": "string" },
          "appId": { "type": "string" },
          "environment": { "type": "string" },
          "serviceName": { "type": "string" },
          "operationName": { "type": "string" },
          "parentSpanId": { "type": ["string", "null"] },
          "startTimeUtc": { "type": "integer" },
          "endTimeUtc": { "type": ["integer", "null"] },
          "status": { "type": "string", "enum": ["OPEN", "OK", "ERROR", "CANCELLED"] },
          "attributes": { "type": "object", "additionalProperties": { "type": "string" } }
        },
        "required": ["spanId", "traceId", "appId", "environment", "serviceName", "operationName", "startTimeUtc", "status"]
      }
    },
    "logs": {
      "type": "array",
      "description": "All log records in this trace, sorted by timestampUtc+sequenceId",
      "items": {
        "type": "object",
        "properties": {
          "logId": { "type": "string" },
          "appId": { "type": "string" },
          "environment": { "type": "string" },
          "sdkInstanceId": { "type": "string" },
          "serviceName": { "type": "string" },
          "traceId": { "type": ["string", "null"] },
          "spanId": { "type": ["string", "null"] },
          "parentSpanId": { "type": ["string", "null"] },
          "timestampUtc": { "type": "integer" },
          "sequenceId": { "type": "integer" },
          "level": { "type": "string" },
          "message": { "type": "string" },
          "fields": { "type": "object", "additionalProperties": { "type": "string" } },
          "captureReason": { "type": ["string", "null"] },
          "linkedFrameId": { "type": ["string", "null"] }
        },
        "required": ["logId", "appId", "environment", "sdkInstanceId", "serviceName", "timestampUtc", "sequenceId", "level", "message"]
      }
    },
    "frameSnapshots": {
      "type": "array",
      "description": "All frame snapshots in this trace, sorted by timestampUtc+sequenceId",
      "items": {
        "type": "object",
        "properties": {
          "frameId": { "type": "string" },
          "traceId": { "type": "string" },
          "spanId": { "type": "string" },
          "appId": { "type": "string" },
          "environment": { "type": "string" },
          "sdkInstanceId": { "type": "string" },
          "serviceName": { "type": "string" },
          "timestampUtc": { "type": "integer" },
          "sequenceId": { "type": "integer" },
          "captureReason": { "type": "string" },
          "callStack": { "type": "array" },
          "localsJson": { "type": "string" },
          "serializationMetadata": { "type": "object" },
          "logId": { "type": ["string", "null"] }
        },
        "required": ["frameId", "traceId", "spanId", "appId", "environment", "sdkInstanceId", "serviceName", "timestampUtc", "sequenceId", "captureReason", "callStack", "localsJson"]
      }
    }
  },
  "required": ["traceId", "spans", "logs", "frameSnapshots"]
}""",
        ),
        ToolDescriptor(
            name = "step_frames",
            description = "Navigate to adjacent frames in temporal order",
            inputSchema = """{
  "type": "object",
  "properties": {
    "frameId": { "type": "string", "description": "The reference frame to step from" },
    "direction": { "type": "string", "enum": ["forward", "backward"], "description": "Direction of traversal (default: forward)", "default": "forward" },
    "count": { "type": "integer", "description": "Number of frames to return (1-25, default 1)", "default": 1, "minimum": 1, "maximum": 25 }
  },
  "required": ["frameId"],
  "additionalProperties": false
}""",
            outputSchema = """{
  "type": "array",
  "description": "List of adjacent FrameSnapshot objects in temporal order, up to 'count' frames",
  "items": {
    "type": "object",
    "properties": {
      "frameId": { "type": "string" },
      "traceId": { "type": "string" },
      "spanId": { "type": "string" },
      "appId": { "type": "string" },
      "environment": { "type": "string" },
      "sdkInstanceId": { "type": "string" },
      "serviceName": { "type": "string" },
      "timestampUtc": { "type": "integer" },
      "sequenceId": { "type": "integer" },
      "captureReason": { "type": "string" },
      "callStack": { "type": "array" },
      "localsJson": { "type": "string" },
      "serializationMetadata": { "type": "object" },
      "logId": { "type": ["string", "null"] }
    },
    "required": ["frameId", "traceId", "spanId", "appId", "environment", "sdkInstanceId", "serviceName", "timestampUtc", "sequenceId", "captureReason", "callStack", "localsJson"]
  }
}""",
        ),
        ToolDescriptor(
            name = "list_remote_rules",
            description = "List remote capture rules, optionally filtered by appId",
            inputSchema = """{
  "type": "object",
  "properties": {
    "appId": { "type": "string", "description": "Filter rules targeting this appId (returns rules with empty targetApps list as well)" }
  },
  "additionalProperties": false
}""",
            outputSchema = """{
  "type": "array",
  "description": "Array of RemoteRule objects sorted by priority (descending), then ruleId",
  "items": {
    "type": "object",
    "properties": {
      "ruleId": { "type": "string" },
      "enabled": { "type": "boolean" },
      "targetApps": { "type": "array", "items": { "type": "string" }, "description": "Empty list means all apps" },
      "ttlSeconds": { "type": "integer", "description": "Rule expiration in seconds from creation" },
      "priority": { "type": "integer", "description": "Higher = evaluated first" },
      "expression": { "type": "string", "description": "CEL expression evaluated against each log" },
      "captureMode": { "type": "string", "enum": ["manual_trace", "auto_capture_level", "remote_rule", "crash_flush"] },
      "sampleLimit": { "type": "integer", "description": "Max logs to capture per rule evaluation (default 1)" },
      "createdBy": { "type": "string" },
      "createdAtUtc": { "type": ["integer", "null"], "description": "UTC epoch millis — set on insert from system clock" },
      "expiresAtUtc": { "type": ["integer", "null"], "description": "UTC epoch millis — set when rule expires or is deleted" }
    },
    "required": ["ruleId", "enabled", "ttlSeconds", "priority", "expression", "captureMode", "sampleLimit", "createdBy"]
  }
}""",
        ),
        ToolDescriptor(
            name = "upsert_remote_rule",
            description = "Create or update a remote capture rule",
            inputSchema = """{
  "type": "object",
  "properties": {
    "rule": {
      "type": "object",
      "description": "The RemoteRule to upsert (full rule object required)",
      "properties": {
        "ruleId": { "type": "string", "description": "Unique identifier — use existing id to update, new id to create" },
        "enabled": { "type": "boolean", "default": true },
        "targetApps": { "type": "array", "items": { "type": "string" }, "default": [] },
        "ttlSeconds": { "type": "integer", "description": "Time-to-live in seconds from creation" },
        "priority": { "type": "integer", "default": 0 },
        "expression": { "type": "string", "description": "CEL expression evaluated against each log" },
        "captureMode": { "type": "string", "enum": ["manual_trace", "auto_capture_level", "remote_rule", "crash_flush"], "default": "remote_rule" },
        "sampleLimit": { "type": "integer", "default": 1 },
        "createdBy": { "type": "string" }
      },
      "required": ["ruleId", "ttlSeconds", "expression", "createdBy"]
    }
  },
  "required": ["rule"],
  "additionalProperties": false
}""",
            outputSchema = """{
  "type": "object",
  "description": "The saved RemoteRule (echoed back)",
  "properties": {
    "ruleId": { "type": "string" },
    "enabled": { "type": "boolean" },
    "targetApps": { "type": "array", "items": { "type": "string" } },
    "ttlSeconds": { "type": "integer" },
    "priority": { "type": "integer" },
    "expression": { "type": "string" },
    "captureMode": { "type": "string" },
    "sampleLimit": { "type": "integer" },
    "createdBy": { "type": "string" }
  },
  "required": ["ruleId", "enabled", "targetApps", "ttlSeconds", "priority", "expression", "captureMode", "sampleLimit", "createdBy"]
}""",
        ),
        ToolDescriptor(
            name = "delete_remote_rule",
            description = "Delete a remote capture rule by ruleId",
            inputSchema = """{
  "type": "object",
  "properties": {
    "ruleId": { "type": "string", "description": "Identifier of the rule to delete" }
  },
  "required": ["ruleId"],
  "additionalProperties": false
}""",
            outputSchema = """{
  "type": "object",
  "properties": {
    "deleted": { "type": "boolean", "description": "true if the rule existed and was deleted, false if it did not exist" }
  },
  "required": ["deleted"]
}""",
        ),
        ToolDescriptor(
            name = "create_purge_job",
            description = "Submit an async purge job to delete logs matching a field=value selector",
            inputSchema = """{
  "type": "object",
  "properties": {
    "requestedBy": { "type": "string", "description": "Caller identifier (default: 'mcp')", "default": "mcp" },
    "field": { "type": "string", "description": "Log field to match: 'appId', 'environment', 'traceId', or 'spanId'" },
    "value": { "type": "string", "description": "Value to match against the specified field" }
  },
  "required": ["field", "value"],
  "additionalProperties": false
}""",
            outputSchema = """{
  "type": "object",
  "description": "The created PurgeJob descriptor",
  "properties": {
    "purgeJobId": { "type": "string" },
    "requestedAtUtc": { "type": "integer", "description": "Unix timestamp (ms) when the job was created" },
    "requestedBy": { "type": "string" },
    "selector": {
      "type": "object",
      "properties": {
        "field": { "type": "string" },
        "value": { "type": "string" }
      },
      "required": ["field", "value"]
    },
    "status": { "type": "string", "enum": ["ACCEPTED", "RUNNING", "COMPLETED", "FAILED"] },
    "clickhouseMutationId": { "type": ["string", "null"] },
    "completedAtUtc": { "type": ["integer", "null"] },
    "stats": { "type": "object", "additionalProperties": { "type": "string" }, "description": "Counts of removed records, e.g. {\"logsRemoved\": \"42\"}" }
  },
  "required": ["purgeJobId", "requestedAtUtc", "requestedBy", "selector", "status"]
}""",
        ),
        ToolDescriptor(
            name = "get_purge_job",
            description = "Fetch the status and result of a purge job",
            inputSchema = """{
  "type": "object",
  "properties": {
    "purgeJobId": { "type": "string", "description": "Identifier of the purge job returned by create_purge_job" }
  },
  "required": ["purgeJobId"],
  "additionalProperties": false
}""",
            outputSchema = """{
  "type": "object",
  "description": "The PurgeJob state at query time, or null if not found",
  "properties": {
    "purgeJobId": { "type": "string" },
    "requestedAtUtc": { "type": "integer" },
    "requestedBy": { "type": "string" },
    "selector": {
      "type": "object",
      "properties": {
        "field": { "type": "string" },
        "value": { "type": "string" }
      },
      "required": ["field", "value"]
    },
    "status": { "type": "string", "enum": ["ACCEPTED", "RUNNING", "COMPLETED", "FAILED"] },
    "clickhouseMutationId": { "type": ["string", "null"] },
    "completedAtUtc": { "type": ["integer", "null"] },
    "stats": { "type": "object", "additionalProperties": { "type": "string" } }
  },
  "required": ["purgeJobId", "requestedAtUtc", "requestedBy", "selector", "status"]
}""",
        ),
        ToolDescriptor(
            name = "get_system_health",
            description = "Get aggregated system health and storage counters",
            inputSchema = """{
  "type": "object",
  "description": "No input arguments required",
  "properties": {},
  "additionalProperties": false
}""",
            outputSchema = """{
  "type": "object",
  "properties": {
    "authMode": { "type": "string", "description": "Active authentication mode ('none', 'apiKey', 'bearer', etc.)" },
    "totalLogs": { "type": "integer", "description": "Total log records stored" },
    "totalSpans": { "type": "integer", "description": "Total span records stored" },
    "totalFrames": { "type": "integer", "description": "Total frame snapshots stored" },
    "totalRules": { "type": "integer", "description": "Total active remote rules" },
    "totalPurgeJobs": { "type": "integer", "description": "Total purge jobs (all statuses)" },
    "storageMode": { "type": "string", "enum": ["file", "memory", "clickhouse"] },
    "clickhouseHealthy": { "type": ["boolean", "null"], "description": "ClickHouse health, present only in clickhouse storage mode" },
    "valkeyHealthy": { "type": ["boolean", "null"], "description": "Valkey health, present only in clickhouse storage mode" }
  },
  "required": ["authMode", "totalLogs", "totalSpans", "totalFrames", "totalRules", "totalPurgeJobs", "storageMode"]
}""",
        ),
    )

    fun call(request: ToolCallRequest): ToolCallResponse {
        return try {
            when (request.name) {
                "search_logs" -> {
                    val response = store.searchLogs(
                        SearchLogsRequest(
                            appId = request.arguments["appId"],
                            environment = request.arguments["environment"],
                            textQuery = request.arguments["textQuery"],
                            traceId = request.arguments["traceId"],
                            spanId = request.arguments["spanId"],
                            limit = request.arguments["limit"]?.toIntOrNull() ?: 100,
                        ),
                    )
                    ToolCallResponse(json.encodeToString(response), "Found ${response.items.size} logs")
                }

                "get_log" -> {
                    val record = store.getLog(request.arguments.getValue("logId"))
                    ToolCallResponse(json.encodeToString(record), if (record == null) "Log not found" else "Log fetched", record == null)
                }

                "get_frame_snapshot" -> {
                    val record = request.arguments["frameId"]?.let(store::getFrame)
                        ?: request.arguments["logId"]?.let(store::getFrameByLog)
                    ToolCallResponse(json.encodeToString(record), if (record == null) "Frame not found" else "Frame fetched", record == null)
                }

                "get_trace" -> {
                    val record = store.getTrace(request.arguments.getValue("traceId"))
                    ToolCallResponse(json.encodeToString(record), "Trace ${record.traceId} loaded")
                }

                "step_frames" -> {
                    val items = store.stepFrame(
                        frameId = request.arguments.getValue("frameId"),
                        direction = request.arguments["direction"] ?: "forward",
                        count = request.arguments["count"]?.toIntOrNull() ?: 1,
                    )
                    ToolCallResponse(json.encodeToString(items), "Returned ${items.size} adjacent frame(s)")
                }

                "list_remote_rules" -> {
                    val rules = store.listRules(request.arguments["appId"])
                    ToolCallResponse(json.encodeToString(rules), "Returned ${rules.size} rule(s)")
                }

                "upsert_remote_rule" -> {
                    val rule = json.decodeFromString(org.chronotrace.contract.RemoteRule.serializer(), request.arguments.getValue("rule"))
                    ToolCallResponse(json.encodeToString(store.upsertRule(rule)), "Rule saved")
                }

                "delete_remote_rule" -> {
                    val deleted = store.deleteRule(request.arguments.getValue("ruleId"))
                    ToolCallResponse("""{"deleted":$deleted}""", if (deleted) "Rule deleted" else "Rule not found", !deleted)
                }

                "create_purge_job" -> {
                    val job = store.createPurgeJob(
                        requestedBy = request.arguments["requestedBy"] ?: "mcp",
                        field = request.arguments.getValue("field"),
                        value = request.arguments.getValue("value"),
                    )
                    ToolCallResponse(json.encodeToString(job), "Purge job created")
                }

                "get_purge_job" -> {
                    val job = store.getPurgeJob(request.arguments.getValue("purgeJobId"))
                    ToolCallResponse(json.encodeToString(job), if (job == null) "Purge job not found" else "Purge job fetched", job == null)
                }

                "get_system_health" -> {
                    val health = store.health()
                    ToolCallResponse(json.encodeToString(health), "System health fetched")
                }

                else -> ToolCallResponse("""{"error":"unknown tool"}""", "Unknown tool ${request.name}", true)
            }
        } catch (error: Exception) {
            ToolCallResponse("""{"error":${json.encodeToString(error.message ?: "unknown error")}}""", error.message ?: "unknown error", true)
        }
    }
}


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
        ToolDescriptor("search_logs", "Search logs with filters", """{"type":"object"}""", """{"type":"object"}"""),
        ToolDescriptor("get_log", "Fetch a single log", """{"type":"object"}""", """{"type":"object"}"""),
        ToolDescriptor("get_frame_snapshot", "Fetch a frame snapshot", """{"type":"object"}""", """{"type":"object"}"""),
        ToolDescriptor("get_trace", "Fetch trace graph", """{"type":"object"}""", """{"type":"object"}"""),
        ToolDescriptor("step_frames", "Step adjacent frames", """{"type":"object"}""", """{"type":"object"}"""),
        ToolDescriptor("list_remote_rules", "List remote rules", """{"type":"object"}""", """{"type":"object"}"""),
        ToolDescriptor("upsert_remote_rule", "Create or update a remote rule", """{"type":"object"}""", """{"type":"object"}"""),
        ToolDescriptor("delete_remote_rule", "Delete a remote rule", """{"type":"object"}""", """{"type":"object"}"""),
        ToolDescriptor("create_purge_job", "Create purge job", """{"type":"object"}""", """{"type":"object"}"""),
        ToolDescriptor("get_purge_job", "Fetch purge job", """{"type":"object"}""", """{"type":"object"}"""),
        ToolDescriptor("get_system_health", "Get system health", """{"type":"object"}""", """{"type":"object"}"""),
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


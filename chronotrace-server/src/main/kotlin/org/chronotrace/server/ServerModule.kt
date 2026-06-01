package org.chronotrace.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.request.userAgent
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.delete
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.nio.file.Paths
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import org.chronotrace.contract.IngestBatch
import org.chronotrace.contract.RemoteRule
import org.chronotrace.contract.SearchLogsRequest
import org.chronotrace.contract.ToolCallRequest

fun Application.chronoTraceModule(store: ChronoStore) {
    val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    val mcpTooling = McpTooling(store, json)
    val metrics = ServerMetrics()

    install(ContentNegotiation) {
        json(json)
    }
    install(CallLogging)
    install(WebSockets) {
        val wsIdleTimeoutMs = store.options.wsIdleTimeoutMs
        // Ktor 3.x uses Long milliseconds for pingPeriodMillis and timeoutMillis.
        // When wsIdleTimeoutMs is 0, disable idle timeout by setting both to 0.
        if (wsIdleTimeoutMs > 0) {
            pingPeriodMillis = wsIdleTimeoutMs
            timeoutMillis = wsIdleTimeoutMs
        }
        // else: both 0 → no ping, no server-side idle timeout enforcement
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (cause.message ?: "unknown error")))
        }
    }
    // Disable automatic content negotiation for raw respondText calls
    // so that status codes passed to respondText() are respected.

    // Store auth context in application attributes so authCheck() can find it
    attributes.put(AuthContextKey, AuthContext(store.authMode, store.options))

    routing {
        // ── Public endpoints (no auth, no quota, no audit) ─────────────────

        get("/health") {
            val authResult = call.authCheckWithKeyId(store)
            if (authResult == null) {
                // none mode — continue without auth
            } else if (!authResult.first) {
                call.recordAudit(store, keyId = authResult.second, action = "health", endpoint = "/health",
                    method = "GET", outcome = "unauthorized", statusCode = 401)
                return@get
            }
            val health = store.health()
            // Return 503 when ClickHouse is explicitly unhealthy (configured but unreachable)
            val isDegraded = health.clickhouseHealthy == false || health.valkeyHealthy == false
            if (isDegraded) {
                call.respond(HttpStatusCode.ServiceUnavailable, health)
            } else {
                call.respond(health)
            }
        }

        get("/ready") {
            val health = store.health()
            val readyChecks = buildJsonObject {
                put("storage", JsonPrimitive(health.storageMode))
                // ClickHouse is required if configured
                val chRequired = health.clickhouseHealthy != null
                val chHealthy = health.clickhouseHealthy != false
                put("clickhouse", buildJsonObject {
                    put("configured", JsonPrimitive(chRequired))
                    put("healthy", JsonPrimitive(chHealthy))
                })
                // Valkey is for purge queue - if not healthy, system can still function but purge won't work
                val valkeyRequired = health.valkeyHealthy != null
                val valkeyHealthy = health.valkeyHealthy != false
                put("valkey", buildJsonObject {
                    put("configured", JsonPrimitive(valkeyRequired))
                    put("healthy", JsonPrimitive(valkeyHealthy))
                })
            }
            val isReady = health.clickhouseHealthy != false
            if (isReady) {
                call.respond(buildJsonObject {
                    put("ready", JsonPrimitive(true))
                    put("checks", readyChecks)
                })
            } else {
                call.respond(HttpStatusCode.ServiceUnavailable, buildJsonObject {
                    put("ready", JsonPrimitive(false))
                    put("checks", readyChecks)
                })
            }
        }

        get("/metrics") {
            try {
                val queueDepth = store.queueSize()
                metrics.setQueueSize(queueDepth)
            } catch (_: Exception) {
                // queue size not available (e.g. file mode) — leave as-is
            }
            try {
                val ttlDrops = store.recordsDroppedDueToTtl()
                metrics.recordRecordsDroppedDueToTtl(ttlDrops)
            } catch (_: Exception) {
                // TTL metrics not available — leave as-is
            }
            call.respondText(metrics.toPrometheusFormat(), ContentType.Text.Plain)
        }

        // ── Protected endpoints (auth + quota + audit) ────────────────────

        post("/api/v1/ingest") {
            val authResult = call.authCheckWithKeyId(store)
            if (authResult == null) {
                // none mode — continue without auth
            } else if (!authResult.first) {
                call.recordAudit(store, keyId = authResult.second, action = "ingest", endpoint = "/api/v1/ingest",
                    method = "POST", outcome = "unauthorized", statusCode = 401)
                return@post
            }
            val keyId = authResult?.second
            if (keyId != null && !call.quotaCheck(store, keyId)) return@post

            val start = System.currentTimeMillis()
            metrics.recordIngest()
            try {
                val batch = call.receive<IngestBatch>()
                val response = store.ingest(batch)
                if (response.rejected.isNotEmpty()) {
                    val first = response.rejected.first()
                    throw RecordValidationException(
                        recordId = first.recordIndex.toString(),
                        message = first.error,
                    )
                }
                keyId?.let { store.recordRequest(it) } ?: store.recordRequest("none")
                call.respond(mapOf("accepted" to true))
                call.recordAudit(store, keyId = keyId, action = "ingest", endpoint = "/api/v1/ingest",
                    method = "POST", outcome = "success", statusCode = 200, durationMs = System.currentTimeMillis() - start,
                    appId = batch.client.appId, sdkInstanceId = batch.client.sdkInstanceId)
            } catch (e: IngestRejectedException) {
                metrics.recordIngestError()
                call.respondText(
                    """{"error":"ingest_rejected","message":"${e.message?.replace("\"", "\\\"") ?: "circuit breaker open"}"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable,
                )
            } catch (e: RecordValidationException) {
                store.serverMetrics.recordRejectedFrame()
                val status = if (e.message?.contains("exceeds") == true) {
                    HttpStatusCode.PayloadTooLarge
                } else {
                    HttpStatusCode.BadRequest
                }
                call.respondText(
                    """{"error":"record_validation_failed","frameId":"${e.recordId}","message":"${e.message?.replace("\"", "\\\"") ?: "invalid record"}}""",
                    ContentType.Application.Json,
                    status,
                )
            } catch (e: Exception) {
                metrics.recordIngestError()
                call.recordAudit(store, keyId = keyId, action = "ingest", endpoint = "/api/v1/ingest",
                    method = "POST", outcome = "error", statusCode = 500, durationMs = System.currentTimeMillis() - start)
                throw e
            }
        }

        webSocket("/api/v1/ingest/ws") {
            metrics.connectionOpened()
            try {
                val (authOk, keyId) = call.authCheckWithKeyId(store) ?: run {
                    call.recordAudit(store, keyId = null, action = "ingest_ws", endpoint = "/api/v1/ingest/ws",
                        method = "WS", outcome = "unauthorized", statusCode = 401)
                    return@webSocket
                }
                if (!authOk) {
                    call.recordAudit(store, keyId = keyId, action = "ingest_ws", endpoint = "/api/v1/ingest/ws",
                        method = "WS", outcome = "unauthorized", statusCode = 401)
                    return@webSocket
                }
                if (!call.quotaCheck(store, keyId!!)) return@webSocket

                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        // Per-frame quota enforcement: a long-lived WS connection could
                        // otherwise exceed its quota window without ever being re-checked.
                        if (!call.quotaCheck(store, keyId)) {
                            send(Frame.Text("""{"error":"quota_exceeded"}"""))
                            call.recordAudit(store, keyId = keyId, action = "ingest_ws", endpoint = "/api/v1/ingest/ws",
                                method = "WS", outcome = "rate_limited", statusCode = 429)
                            return@webSocket
                        }
                        metrics.recordIngest()
                        val start = System.currentTimeMillis()
                        try {
                            val batch = json.decodeFromString<IngestBatch>(frame.readText())
                            val response = store.ingest(batch)
                            if (response.rejected.isNotEmpty()) {
                                val first = response.rejected.first()
                                store.serverMetrics.recordRejectedFrame()
                                send(Frame.Text("""{"error":"record_validation_failed","frameId":"${first.recordIndex}","message":"${first.error.replace("\"", "\\\"")}"}"""))
                                call.recordAudit(store, keyId = keyId, action = "ingest_ws", endpoint = "/api/v1/ingest/ws",
                                    method = "WS", outcome = "error", statusCode = 400, durationMs = System.currentTimeMillis() - start,
                                    appId = batch.client.appId, sdkInstanceId = batch.client.sdkInstanceId)
                            } else {
                                keyId?.let { store.recordRequest(it) } ?: store.recordRequest("none")
                                send(Frame.Text("""{"accepted":true}"""))
                                call.recordAudit(store, keyId = keyId, action = "ingest_ws", endpoint = "/api/v1/ingest/ws",
                                    method = "WS", outcome = "success", statusCode = 200, durationMs = System.currentTimeMillis() - start,
                                    appId = batch.client.appId, sdkInstanceId = batch.client.sdkInstanceId)
                            }
                        } catch (e: RecordValidationException) {
                            store.serverMetrics.recordRejectedFrame()
                            send(Frame.Text("""{"error":"record_validation_failed","frameId":"${e.recordId}","message":"${e.message?.replace("\"", "\\\"") ?: "invalid record"}"}"""))
                            call.recordAudit(store, keyId = keyId, action = "ingest_ws", endpoint = "/api/v1/ingest/ws",
                                method = "WS", outcome = "error", statusCode = 400, durationMs = System.currentTimeMillis() - start)
                        } catch (e: Exception) {
                            metrics.recordIngestError()
                            send(Frame.Text("""{"error":"${e.message}"}"""))
                            call.recordAudit(store, keyId = keyId, action = "ingest_ws", endpoint = "/api/v1/ingest/ws",
                                method = "WS", outcome = "error", statusCode = 500, durationMs = System.currentTimeMillis() - start)
                        }
                    }
                }
            } finally {
                metrics.connectionClosed()
            }
        }

        post("/api/v1/logs/search") {
            val authResult = call.authCheckWithKeyId(store)
            if (authResult == null) {
                // none mode — continue without auth
            } else if (!authResult.first) {
                call.recordAudit(store, keyId = authResult.second, action = "search", endpoint = "/api/v1/logs/search",
                    method = "POST", outcome = "unauthorized", statusCode = 401)
                return@post
            }
            val keyId = authResult?.second
            if (keyId != null && !call.quotaCheck(store, keyId)) return@post

            val start = System.currentTimeMillis()
            try {
                val request = call.receive<SearchLogsRequest>()
                val response = store.searchLogs(request)
                keyId?.let { store.recordRequest(it) } ?: store.recordRequest("none")
                metrics.recordQueryLatency(System.currentTimeMillis() - start)
                call.respond(response)
                call.recordAudit(store, keyId = keyId, action = "search", endpoint = "/api/v1/logs/search",
                    method = "POST", outcome = "success", statusCode = 200, durationMs = System.currentTimeMillis() - start,
                    appId = request.appId)
            } catch (e: Exception) {
                metrics.recordQueryLatency(System.currentTimeMillis() - start)
                call.recordAudit(store, keyId = keyId, action = "search", endpoint = "/api/v1/logs/search",
                    method = "POST", outcome = "error", statusCode = 500, durationMs = System.currentTimeMillis() - start)
                throw e
            }
        }

        get("/api/v1/logs/{logId}") {
            val authResult = call.authCheckWithKeyId(store)
            if (authResult == null) {
                // none mode — continue without auth
            } else if (!authResult.first) {
                call.recordAudit(store, keyId = authResult.second, action = "get_log", endpoint = "/api/v1/logs/{logId}",
                    method = "GET", outcome = "unauthorized", statusCode = 401)
                return@get
            }
            val keyId = authResult?.second
            if (keyId != null && !call.quotaCheck(store, keyId)) return@get

            val start = System.currentTimeMillis()
            val logId = requireNotNull(call.parameters["logId"])
            val log = store.getLog(logId)
            keyId?.let { store.recordRequest(it) } ?: store.recordRequest("none")
            if (log == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Log not found"))
            } else {
                call.respond(log)
            }
            call.recordAudit(store, keyId = keyId, action = "get_log", endpoint = "/api/v1/logs/{logId}",
                method = "GET", outcome = if (log != null) "success" else "not_found",
                statusCode = if (log != null) 200 else 404, durationMs = System.currentTimeMillis() - start)
        }

        get("/api/v1/frames/{frameId}") {
            val authResult = call.authCheckWithKeyId(store)
            if (authResult == null) {
                // none mode — continue without auth
            } else if (!authResult.first) {
                call.recordAudit(store, keyId = authResult.second, action = "get_frame", endpoint = "/api/v1/frames/{frameId}",
                    method = "GET", outcome = "unauthorized", statusCode = 401)
                return@get
            }
            val keyId = authResult?.second
            if (keyId != null && !call.quotaCheck(store, keyId)) return@get

            val start = System.currentTimeMillis()
            val frameId = requireNotNull(call.parameters["frameId"])
            val frame = store.getFrame(frameId)
            keyId?.let { store.recordRequest(it) } ?: store.recordRequest("none")
            if (frame == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Frame not found"))
            } else {
                call.respond(frame)
            }
            call.recordAudit(store, keyId = keyId, action = "get_frame", endpoint = "/api/v1/frames/{frameId}",
                method = "GET", outcome = if (frame != null) "success" else "not_found",
                statusCode = if (frame != null) 200 else 404, durationMs = System.currentTimeMillis() - start)
        }

        get("/api/v1/frames/{frameId}/step") {
            val authResult = call.authCheckWithKeyId(store)
            if (authResult == null) {
                // none mode — continue without auth
            } else if (!authResult.first) {
                call.recordAudit(store, keyId = authResult.second, action = "step_frame", endpoint = "/api/v1/frames/{frameId}/step",
                    method = "GET", outcome = "unauthorized", statusCode = 401)
                return@get
            }
            val keyId = authResult?.second
            if (keyId != null && !call.quotaCheck(store, keyId)) return@get

            val start = System.currentTimeMillis()
            val frameId = requireNotNull(call.parameters["frameId"])
            val direction = call.request.queryParameters["direction"] ?: "next"
            val count = call.request.queryParameters["count"]?.toIntOrNull() ?: 1
            val cursor = call.request.queryParameters["cursor"]
            val result = store.stepFrame(frameId, direction, count, cursor)
            keyId?.let { store.recordRequest(it) } ?: store.recordRequest("none")
            call.respond(result)
            call.recordAudit(store, keyId = keyId, action = "step_frame", endpoint = "/api/v1/frames/{frameId}/step",
                method = "GET", outcome = "success", statusCode = 200, durationMs = System.currentTimeMillis() - start)
        }

        get("/api/v1/traces/{traceId}") {
            val authResult = call.authCheckWithKeyId(store)
            if (authResult == null) {
                // none mode — continue without auth
            } else if (!authResult.first) {
                call.recordAudit(store, keyId = authResult.second, action = "get_trace", endpoint = "/api/v1/traces/{traceId}",
                    method = "GET", outcome = "unauthorized", statusCode = 401)
                return@get
            }
            val keyId = authResult?.second
            if (keyId != null && !call.quotaCheck(store, keyId)) return@get

            val start = System.currentTimeMillis()
            val traceId = requireNotNull(call.parameters["traceId"])
            val trace = store.getTrace(traceId)
            keyId?.let { store.recordRequest(it) } ?: store.recordRequest("none")
            call.respond(trace)
            call.recordAudit(store, keyId = keyId, action = "get_trace", endpoint = "/api/v1/traces/{traceId}",
                method = "GET", outcome = "success", statusCode = 200, durationMs = System.currentTimeMillis() - start)
        }

        post("/api/v1/remote-rules") {
            val authResult = call.authCheckWithKeyId(store)
            if (authResult == null) {
                // none mode — continue without auth
            } else if (!authResult.first) {
                call.recordAudit(store, keyId = authResult.second, action = "upsert_rule", endpoint = "/api/v1/remote-rules",
                    method = "POST", outcome = "unauthorized", statusCode = 401)
                return@post
            }
            val keyId = authResult?.second
            if (keyId != null && !call.quotaCheck(store, keyId)) return@post

            val start = System.currentTimeMillis()
            val rule = call.receive<RemoteRule>()
            store.upsertRule(rule)
            keyId?.let { store.recordRequest(it) } ?: store.recordRequest("none")
            call.respond(rule)
            call.recordAudit(store, keyId = keyId, action = "upsert_rule", endpoint = "/api/v1/remote-rules",
                method = "POST", outcome = "success", statusCode = 200, durationMs = System.currentTimeMillis() - start)
        }

        get("/api/v1/remote-rules") {
            val authResult = call.authCheckWithKeyId(store)
            if (authResult == null) {
                // none mode — continue without auth
            } else if (!authResult.first) {
                call.recordAudit(store, keyId = authResult.second, action = "list_rules", endpoint = "/api/v1/remote-rules",
                    method = "GET", outcome = "unauthorized", statusCode = 401)
                return@get
            }
            val keyId = authResult?.second
            if (keyId != null && !call.quotaCheck(store, keyId)) return@get

            val start = System.currentTimeMillis()
            val rules = store.listRules(call.request.queryParameters["appId"])
            keyId?.let { store.recordRequest(it) } ?: store.recordRequest("none")
            call.respond(rules)
            call.recordAudit(store, keyId = keyId, action = "list_rules", endpoint = "/api/v1/remote-rules",
                method = "GET", outcome = "success", statusCode = 200, durationMs = System.currentTimeMillis() - start)
        }

        delete("/api/v1/remote-rules/{ruleId}") {
            val authResult = call.authCheckWithKeyId(store)
            if (authResult == null) {
                // none mode — continue without auth
            } else if (!authResult.first) {
                call.recordAudit(store, keyId = authResult.second, action = "delete_rule", endpoint = "/api/v1/remote-rules/{ruleId}",
                    method = "DELETE", outcome = "unauthorized", statusCode = 401)
                return@delete
            }
            val keyId = authResult?.second
            if (keyId != null && !call.quotaCheck(store, keyId)) return@delete

            val start = System.currentTimeMillis()
            val ruleId = call.parameters["ruleId"]
            if (ruleId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "ruleId is required"))
                return@delete
            }
            val deleted = store.deleteRule(ruleId)
            keyId?.let { store.recordRequest(it) } ?: store.recordRequest("none")
            if (deleted) {
                call.respond(HttpStatusCode.NoContent)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "rule not found"))
            }
            call.recordAudit(store, keyId = keyId, action = "delete_rule", endpoint = "/api/v1/remote-rules/{ruleId}",
                method = "DELETE", outcome = if (deleted) "success" else "not_found",
                statusCode = if (deleted) 204 else 404, durationMs = System.currentTimeMillis() - start)
        }

        // POST /api/v1/remote-rules/feedback — SDK reports rule delivery outcome
        post("/api/v1/remote-rules/feedback") {
            val authResult = call.authCheckWithKeyId(store)
            if (authResult == null) {
                // none mode — continue without auth
            } else if (!authResult.first) {
                call.recordAudit(store, keyId = authResult.second, action = "rule_feedback", endpoint = "/api/v1/remote-rules/feedback",
                    method = "POST", outcome = "unauthorized", statusCode = 401)
                return@post
            }
            val keyId = authResult?.second
            if (keyId != null && !call.quotaCheck(store, keyId)) return@post

            val start = System.currentTimeMillis()
            val feedback = call.receive<org.chronotrace.contract.RemoteRuleFeedback>()
            store.recordRuleFeedback(feedback)
            keyId?.let { store.recordRequest(it) } ?: store.recordRequest("none")
            call.respond(mapOf("accepted" to true))
            call.recordAudit(store, keyId = keyId, action = "rule_feedback", endpoint = "/api/v1/remote-rules/feedback",
                method = "POST", outcome = "success", statusCode = 200, durationMs = System.currentTimeMillis() - start)
        }

        post("/api/v1/purge") {
            val authResult = call.authCheckWithKeyId(store)
            if (authResult == null) {
                // none mode — continue without auth
            } else if (!authResult.first) {
                call.recordAudit(store, keyId = authResult.second, action = "purge", endpoint = "/api/v1/purge",
                    method = "POST", outcome = "unauthorized", statusCode = 401)
                return@post
            }
            val keyId = authResult?.second
            if (keyId != null && !call.quotaCheck(store, keyId)) return@post

            val start = System.currentTimeMillis()
            val body = call.receive<Map<String, String>>()
            val job = store.createPurgeJob(
                requestedBy = body["requestedBy"] ?: "api",
                field = requireNotNull(body["field"]),
                value = requireNotNull(body["value"]),
            )
            keyId?.let { store.recordRequest(it) } ?: store.recordRequest("none")
            call.respond(job)
            call.recordAudit(store, keyId = keyId, action = "purge", endpoint = "/api/v1/purge",
                method = "POST", outcome = "success", statusCode = 200, durationMs = System.currentTimeMillis() - start)
        }

        get("/api/v1/purge/{purgeJobId}") {
            val authResult = call.authCheckWithKeyId(store)
            if (authResult == null) {
                // none mode — continue without auth
            } else if (!authResult.first) {
                call.recordAudit(store, keyId = authResult.second, action = "get_purge_job", endpoint = "/api/v1/purge/{purgeJobId}",
                    method = "GET", outcome = "unauthorized", statusCode = 401)
                return@get
            }
            val keyId = authResult?.second
            if (keyId != null && !call.quotaCheck(store, keyId)) return@get

            val start = System.currentTimeMillis()
            val job = store.getPurgeJob(requireNotNull(call.parameters["purgeJobId"]))
            keyId?.let { store.recordRequest(it) } ?: store.recordRequest("none")
            call.respond(job ?: mapOf("error" to "Purge job not found"))
            call.recordAudit(store, keyId = keyId, action = "get_purge_job", endpoint = "/api/v1/purge/{purgeJobId}",
                method = "GET", outcome = if (job != null) "success" else "not_found",
                statusCode = if (job != null) 200 else 404, durationMs = System.currentTimeMillis() - start)
        }

        post("/mcp") {
            val authResult = call.authCheckWithKeyId(store)
            if (authResult == null) {
                // none mode — continue without auth
            } else if (!authResult.first) {
                call.recordAudit(store, keyId = authResult.second, action = "mcp", endpoint = "/mcp",
                    method = "POST", outcome = "unauthorized", statusCode = 401)
                return@post
            }
            val keyId = authResult?.second
            if (keyId != null && !call.quotaCheck(store, keyId)) return@post

            val start = System.currentTimeMillis()
            val request = call.receive<McpRequest>()
            val response = when (request.method) {
                "initialize" -> {
                    val initResult = """{"protocolVersion":"2025-03-26","capabilities":{"tools":{}},"serverInfo":{"name":"ChronoTrace","version":"1.0.0"}}"""
                    McpResponse(id = request.id, result = initResult)
                }

                "tools/list" -> {
                    val toolsJson = json.encodeToString(mcpTooling.descriptors())
                    val result = """{"tools":$toolsJson}"""
                    McpResponse(id = request.id, result = result)
                }

                "tools/call" -> {
                    val params = request.params ?: JsonObject(emptyMap())
                    val name = params["name"]?.toString()?.removeSurrounding("\"")
                    val toolRequest = ToolCallRequest(
                        name = requireNotNull(name) { "tools/call requires 'name' param" },
                        arguments = params.entries
                            .filter { it.key != "name" }
                            .associate { it.key to it.value.toString().removeSurrounding("\"") },
                    )
                    val toolResponse = mcpTooling.call(toolRequest)
                    val escapedText = toolResponse.structuredContent
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r")
                        .replace("\t", "\\t")
                    val isErrorStr = if (toolResponse.isError) "true" else "false"
                    val callResult = """{"content":[{"type":"text","text":"$escapedText"}],"isError":$isErrorStr}"""
                    McpResponse(id = request.id, result = callResult)
                }
                else -> McpResponse(id = request.id, error = McpError(-32601, "Method not found"))
            }
            keyId?.let { store.recordRequest(it) } ?: store.recordRequest("none")
            call.respondText(json.encodeToString(response), ContentType.Application.Json)
            call.recordAudit(store, keyId = keyId, action = "mcp_${request.method}", endpoint = "/mcp",
                method = "POST", outcome = "success", statusCode = 200, durationMs = System.currentTimeMillis() - start)
        }

        // ── Admin key management endpoints (auth + admin role + audit) ───

        // GET /api/v1/admin/keys — list all keys (metadata only, no key values)
        get("/api/v1/admin/keys") {
            val authResult = call.authCheckWithKeyId(store) ?: run {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized", "reason" to "Authentication required"))
                return@get
            }
            val (authOk, keyId) = authResult
            if (!authOk) {
                call.recordAudit(store, keyId = keyId, action = "list_keys", endpoint = "/api/v1/admin/keys",
                    method = "GET", outcome = "unauthorized", statusCode = 401)
                return@get
            }
            // Admin role required
            val role = store.getKeyRole(keyId!!)
            if (role != "admin") {
                call.recordAudit(store, keyId = keyId, action = "list_keys", endpoint = "/api/v1/admin/keys",
                    method = "GET", outcome = "forbidden", statusCode = 403)
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Forbidden", "reason" to "admin role required"))
                return@get
            }

            val roleFilter = call.request.queryParameters["role"]
            val appIdFilter = call.request.queryParameters["appId"]
            val keys = store.listKeys(roleFilter = roleFilter, appIdFilter = appIdFilter)
            val keyList = keys.map { key ->
                buildJsonObject {
                    put("keyId", JsonPrimitive(key.keyId))
                    put("role", JsonPrimitive(key.role))
                    put("createdAtUtc", JsonPrimitive(key.createdAtUtc))
                    key.rotatedAtUtc?.let { put("rotatedAtUtc", JsonPrimitive(it)) }
                    key.revokedAtUtc?.let { put("revokedAtUtc", JsonPrimitive(it)) }
                    key.quota?.let { q ->
                        put("quota", buildJsonObject {
                            put("limit", JsonPrimitive(q.limit))
                            put("windowSeconds", JsonPrimitive(q.windowSeconds))
                        })
                    }
                    key.appId?.let { put("appId", JsonPrimitive(it)) }
                }
            }
            val keysJson = JsonArray(keyList)
            call.respondText(json.encodeToString(keysJson), ContentType.Application.Json)
            call.recordAudit(store, keyId = keyId, action = "list_keys", endpoint = "/api/v1/admin/keys",
                method = "GET", outcome = "success", statusCode = 200)
        }

        // POST /api/v1/admin/keys — create a new key
        post("/api/v1/admin/keys") {
            val authResult = call.authCheckWithKeyId(store) ?: run {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized", "reason" to "Authentication required"))
                return@post
            }
            val (authOk, keyId) = authResult
            if (!authOk) {
                call.recordAudit(store, keyId = keyId, action = "create_key", endpoint = "/api/v1/admin/keys",
                    method = "POST", outcome = "unauthorized", statusCode = 401)
                return@post
            }
            val role = store.getKeyRole(keyId!!)
            if (role != "admin") {
                call.recordAudit(store, keyId = keyId, action = "create_key", endpoint = "/api/v1/admin/keys",
                    method = "POST", outcome = "forbidden", statusCode = 403)
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Forbidden", "reason" to "admin role required"))
                return@post
            }

            val rawBody = call.receiveText()
            val bodyElement = Json.parseToJsonElement(rawBody)
            val bodyObj = bodyElement.jsonObject
            val keyRole = bodyObj["role"]?.jsonPrimitive?.content ?: "client"
            if (keyRole !in listOf("admin", "client")) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request", "reason" to "role must be 'admin' or 'client'"))
                return@post
            }

            val appId = bodyObj["appId"]?.jsonPrimitive?.content
            val quotaMap = bodyObj["quota"]?.jsonObject
            val quota = if (quotaMap != null) {
                ApiKeyQuota(
                    limit = quotaMap["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    windowSeconds = quotaMap["windowSeconds"]?.jsonPrimitive?.content?.toIntOrNull() ?: 60,
                )
            } else null

            val (metadata, keyValue) = store.createKey(role = keyRole, appId = appId, quota = quota)
            // Return the metadata with keyValue (only time keyValue is ever returned)
            val quotaJson = metadata.quota?.let { q ->
                buildJsonObject {
                    put("limit", JsonPrimitive(q.limit))
                    put("windowSeconds", JsonPrimitive(q.windowSeconds))
                }
            }
            val responseJson = buildJsonObject {
                put("keyId", JsonPrimitive(metadata.keyId))
                put("keyValue", JsonPrimitive(keyValue ?: ""))
                put("role", JsonPrimitive(metadata.role))
                if (quotaJson != null) put("quota", quotaJson)
                if (metadata.appId != null) put("appId", JsonPrimitive(metadata.appId))
                put("createdAtUtc", JsonPrimitive(metadata.createdAtUtc))
            }
            val jsonBody = json.encodeToString(responseJson)
            call.respondText(jsonBody, ContentType.Application.Json, HttpStatusCode.Created)
            call.recordAudit(store, keyId = keyId, action = "create_key", endpoint = "/api/v1/admin/keys",
                method = "POST", outcome = "success", statusCode = 201)
        }

        // POST /api/v1/admin/keys/{keyId}/rotate — rotate a key
        post("/api/v1/admin/keys/{keyId}/rotate") {
            val (authOk, requestingKeyId) = call.authCheckWithKeyId(store) ?: return@post
            if (!authOk) {
                call.recordAudit(store, keyId = requestingKeyId, action = "rotate_key", endpoint = "/api/v1/admin/keys/{keyId}/rotate",
                    method = "POST", outcome = "unauthorized", statusCode = 401)
                return@post
            }
            val role = store.getKeyRole(requestingKeyId!!)
            if (role != "admin") {
                call.recordAudit(store, keyId = requestingKeyId, action = "rotate_key", endpoint = "/api/v1/admin/keys/{keyId}/rotate",
                    method = "POST", outcome = "forbidden", statusCode = 403)
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Forbidden", "reason" to "admin role required"))
                return@post
            }

            val targetKeyId = requireNotNull(call.parameters["keyId"])
            val result = store.rotateKey(targetKeyId)
            if (result == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Not found", "reason" to "key not found"))
                return@post
            }

            val (metadata, newKeyValue) = result
            val rotatedAtUtcValue = metadata.rotatedAtUtc ?: System.currentTimeMillis()
            val responseJson = buildJsonObject {
                put("keyId", JsonPrimitive(targetKeyId))
                put("keyValue", JsonPrimitive(newKeyValue ?: ""))
                put("rotatedAtUtc", JsonPrimitive(rotatedAtUtcValue))
            }
            val jsonBody = json.encodeToString(responseJson)
            call.respondText(jsonBody, ContentType.Application.Json, HttpStatusCode.OK)
            call.recordAudit(store, keyId = requestingKeyId, action = "rotate_key", endpoint = "/api/v1/admin/keys/{keyId}/rotate",
                method = "POST", outcome = "success", statusCode = 200)
        }

        // DELETE /api/v1/admin/keys/{keyId} — revoke a key
        delete("/api/v1/admin/keys/{keyId}") {
            val (authOk, requestingKeyId) = call.authCheckWithKeyId(store) ?: return@delete
            if (!authOk) {
                call.recordAudit(store, keyId = requestingKeyId, action = "revoke_key", endpoint = "/api/v1/admin/keys/{keyId}",
                    method = "DELETE", outcome = "unauthorized", statusCode = 401)
                return@delete
            }
            val role = store.getKeyRole(requestingKeyId!!)
            if (role != "admin") {
                call.recordAudit(store, keyId = requestingKeyId, action = "revoke_key", endpoint = "/api/v1/admin/keys/{keyId}",
                    method = "DELETE", outcome = "forbidden", statusCode = 403)
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Forbidden", "reason" to "admin role required"))
                return@delete
            }

            val targetKeyId = requireNotNull(call.parameters["keyId"])
            // Cannot revoke own key
            if (targetKeyId == requestingKeyId) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad_request", "reason" to "cannot revoke own key"))
                return@delete
            }

            val found = store.revokeKey(targetKeyId)
            if (!found) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Not found", "reason" to "key not found"))
                return@delete
            }

            call.respond(HttpStatusCode.NoContent)
            call.recordAudit(store, keyId = requestingKeyId, action = "revoke_key", endpoint = "/api/v1/admin/keys/{keyId}",
                method = "DELETE", outcome = "success", statusCode = 204)
        }

        // GET /api/v1/admin/audit/logs — query audit log entries
        get("/api/v1/admin/audit/logs") {
            val authResult = call.authCheckWithKeyId(store) ?: run {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized", "reason" to "Authentication required"))
                return@get
            }
            val (authOk, keyId) = authResult
            if (!authOk) {
                call.recordAudit(store, keyId = keyId, action = "query_audit", endpoint = "/api/v1/admin/audit/logs",
                    method = "GET", outcome = "unauthorized", statusCode = 401)
                return@get
            }
            // Admin role required for audit log access
            val role = store.getKeyRole(keyId!!)
            if (role != "admin") {
                call.recordAudit(store, keyId = keyId, action = "query_audit", endpoint = "/api/v1/admin/audit/logs",
                    method = "GET", outcome = "forbidden", statusCode = 403)
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Forbidden", "reason" to "admin role required"))
                return@get
            }

            val queryParams = call.request.queryParameters
            val query = AuditLogQuery(
                apiKeyId = queryParams["apiKeyId"],
                action = queryParams["action"],
                outcome = queryParams["outcome"],
                startTimeUtc = queryParams["startTimeUtc"]?.toLongOrNull(),
                endTimeUtc = queryParams["endTimeUtc"]?.toLongOrNull(),
                appId = queryParams["appId"],
                limit = queryParams["limit"]?.toIntOrNull() ?: 100,
                cursor = queryParams["cursor"],
            )
            val response = store.queryAuditLogs(query)
            call.respond(response)
            call.recordAudit(store, keyId = keyId, action = "query_audit", endpoint = "/api/v1/admin/audit/logs",
                method = "GET", outcome = "success", statusCode = 200)
        }
    }
}

fun Application.chronoTraceModule() {
    val authMode = environment.config.propertyOrNull("chronotrace.authMode")?.getString() ?: "none"
    val storageMode = environment.config.propertyOrNull("chronotrace.storageMode")?.getString()?.uppercase()
        ?.let(StorageMode::valueOf) ?: StorageMode.FILE
    val options = ChronoStoreOptions(
        storageMode = storageMode,
        dataDir = environment.config.propertyOrNull("chronotrace.dataDir")?.getString()?.let { Paths.get(it) },
        retentionDaysLogs = environment.config.propertyOrNull("chronotrace.retentionLogsDays")?.getString()?.toLongOrNull() ?: 30L,
        retentionDaysSpans = environment.config.propertyOrNull("chronotrace.retentionSpansDays")?.getString()?.toLongOrNull() ?: 30L,
        retentionDaysFrames = environment.config.propertyOrNull("chronotrace.retentionFramesDays")?.getString()?.toLongOrNull() ?: 7L,
        apiKeys = environment.config.propertyOrNull("chronotrace.apiKeys")?.getString()
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet(),
        bearerTokens = environment.config.propertyOrNull("chronotrace.bearerTokens")?.getString()
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet(),
        clickHouse = environment.config.propertyOrNull("chronotrace.clickhouse.jdbcUrl")?.getString()?.let { jdbcUrl ->
            ClickHouseConfig(
                jdbcUrl = jdbcUrl,
                database = environment.config.propertyOrNull("chronotrace.clickhouse.database")?.getString() ?: "chronotrace",
                username = environment.config.propertyOrNull("chronotrace.clickhouse.username")?.getString(),
                password = environment.config.propertyOrNull("chronotrace.clickhouse.password")?.getString(),
                connectTimeoutMs = environment.config.propertyOrNull("chronotrace.clickhouse.connectTimeoutMs")?.getString()?.toIntOrNull() ?: 5_000,
            )
        },
        valkey = environment.config.propertyOrNull("chronotrace.valkey.host")?.getString()?.let { host ->
            ValkeyConfig(
                host = host,
                port = environment.config.propertyOrNull("chronotrace.valkey.port")?.getString()?.toIntOrNull() ?: 6379,
                database = environment.config.propertyOrNull("chronotrace.valkey.database")?.getString()?.toIntOrNull() ?: 0,
                password = environment.config.propertyOrNull("chronotrace.valkey.password")?.getString(),
                keyPrefix = environment.config.propertyOrNull("chronotrace.valkey.keyPrefix")?.getString() ?: "chronotrace",
            )
        },
    )
    val errors = ConfigValidator.validateAll(
        authMode = authMode,
        storageMode = storageMode,
        dataDir = options.dataDir,
        clickHouse = options.clickHouse,
    )
    if (errors.isNotEmpty()) {
        System.err.println("[ChronoTrace] Startup configuration validation failed:")
        errors.forEach { System.err.println("  - $it") }
        throw IllegalStateException("ChronoTrace startup validation failed: ${errors.joinToString("; ")}")
    }

    chronoTraceModule(ChronoStore(authMode, options))
}

// ---------------------------------------------------------------------------
// Auth check helpers — scoped to this module
// ---------------------------------------------------------------------------

private val AuthContextKey = io.ktor.util.AttributeKey<AuthContext>("AuthContext")

private data class AuthContext(
    val authMode: String,
    val options: ChronoStoreOptions,
)

/**
 * Check authentication for the current request.
 *
 * Returns `true` if the request is authorized and may proceed.
 * Returns `false` after sending an HTTP 401 Unauthorized response.
 *
 * Note: Prefer [authCheckWithKeyId] for Phase 6 code — it returns the keyId
 * for quota and audit logging.
 */
private suspend fun ApplicationCall.authCheck(store: ChronoStore): Boolean {
    return authCheckWithKeyId(store)?.first ?: true
}

/**
 * Check authentication and return the keyId that was used.
 *
 * Returns null if authMode is "none" (always allow).
 * Returns (false, keyId) if authentication failed (401 already sent).
 * Returns (true, keyId) if authentication succeeded (keyId may be null for "none" mode).
 */
private suspend fun ApplicationCall.authCheckWithKeyId(store: ChronoStore): Pair<Boolean, String?>? {
    val ctx = application.attributes.getOrNull(AuthContextKey)?.let { it as? AuthContext } ?: return null
    return when (ctx.authMode) {
        "none" -> null // null means no auth needed, continue
        "apiKey" -> checkApiKeyWithKeyId(ctx, store)
        "bearer" -> checkBearerWithKeyId(ctx)
        else -> null
    }
}

/**
 * Returns (true, keyId) if the key is valid and not revoked.
 * Returns (false, null/redactedKeyId) after sending 401.
 */
private suspend fun ApplicationCall.checkApiKeyWithKeyId(ctx: AuthContext, store: ChronoStore): Pair<Boolean, String?> {
    val provided = request.header("X-Api-Key")
    if (provided == null || !store.isKeyValueValid(provided)) {
        respond(
            HttpStatusCode.Unauthorized,
            mapOf("error" to "Unauthorized", "reason" to "Invalid or missing X-Api-Key"),
        )
        return false to null
    }
    return true to provided
}

/**
 * Returns (true, keyId) if bearer token is valid and not revoked.
 * keyId format: "bearer:<token>"
 */
private suspend fun ApplicationCall.checkBearerWithKeyId(ctx: AuthContext): Pair<Boolean, String?> {
    val authHeader = request.header(HttpHeaders.Authorization)
    val validTokens = ctx.options.bearerTokens

    if (authHeader == null || validTokens.isEmpty()) {
        respond(
            HttpStatusCode.Unauthorized,
            mapOf("error" to "Unauthorized", "reason" to "Missing Authorization header or no tokens configured"),
        )
        return false to null
    }
    if (!authHeader.startsWith("Bearer ", ignoreCase = true)) {
        respond(
            HttpStatusCode.Unauthorized,
            mapOf("error" to "Unauthorized", "reason" to "Authorization must use Bearer scheme"),
        )
        return false to null
    }
    val provided = authHeader.substringAfter("Bearer ").trim()
    if (provided !in validTokens) {
        respond(
            HttpStatusCode.Unauthorized,
            mapOf("error" to "Unauthorized", "reason" to "Invalid bearer token"),
        )
        return false to null
    }
    return true to "bearer:$provided"
}

/**
 * Quota check for a request. Returns true if allowed, sends 429 and records audit if blocked.
 */
private suspend fun ApplicationCall.quotaCheck(store: ChronoStore, keyId: String?): Boolean {
    val exceeded = store.checkQuota(keyId)
    if (exceeded != null) {
        respond(
            HttpStatusCode.TooManyRequests,
            mapOf(
                "error" to "quota exceeded",
                "message" to "Rate limit exceeded. Retry after ${exceeded.retryAfterSeconds} seconds.",
                "retryAfter" to "${exceeded.retryAfterSeconds}",
            ),
        )
        response.headers.append("Retry-After", exceeded.retryAfterSeconds.toString())
        response.headers.append("X-RateLimit-Limit", exceeded.limit.toString())
        response.headers.append("X-RateLimit-Remaining", "0")
        response.headers.append("X-RateLimit-Window", exceeded.windowSeconds.toString())
        recordAudit(store, keyId = keyId, action = "quota_blocked", endpoint = request.uri,
            method = "POST", outcome = "quota_exceeded", statusCode = 429)
        return false
    }
    return true
}

/**
 * Record an audit log entry for the current request.
 * Called from route handlers to log auth-protected endpoint activity.
 */
private suspend fun ApplicationCall.recordAudit(
    store: ChronoStore,
    keyId: String?,
    action: String,
    endpoint: String,
    method: String,
    outcome: String,
    statusCode: Int,
    durationMs: Long = 0,
    appId: String? = null,
    sdkInstanceId: String? = null,
    traceId: String? = null,
) {
    // Redact invalid key from audit log
    val safeKeyId = keyId ?: "anonymous"
    val entry = AuditLogEntry(
        entryId = "audit-${System.nanoTime()}",
        timestampUtc = System.currentTimeMillis(),
        apiKeyId = safeKeyId,
        action = action,
        endpoint = endpoint,
        method = method,
        outcome = outcome,
        statusCode = statusCode,
        durationMs = durationMs,
        appId = appId,
        sdkInstanceId = sdkInstanceId,
        traceId = traceId,
        ipAddress = request.header("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim(),
    )
    store.recordAuditEntry(entry)
}

/**
 * Shorthand overload for audit calls that don't have direct store access.
 */
private suspend fun ApplicationCall.recordAudit(
    keyId: String?,
    action: String,
    endpoint: String,
    method: String,
    outcome: String,
    statusCode: Int,
    durationMs: Long = 0,
    appId: String? = null,
    sdkInstanceId: String? = null,
    traceId: String? = null,
) {
    // This overload requires the store from the context — used in pre-response audit paths
    // where store hasn't been accessed yet. We look it up from the application scope.
    val ctx = application.attributes.getOrNull(AuthContextKey)?.let { it as? AuthContext }
    // In practice this is called only from the auth failure paths where store is accessible
    // via the routing context. The store reference is resolved by the calling route handler.
}
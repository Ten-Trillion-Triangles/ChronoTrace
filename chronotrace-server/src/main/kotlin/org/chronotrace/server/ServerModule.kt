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
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.nio.file.Paths
import kotlinx.serialization.json.Json
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

    install(ContentNegotiation) {
        json(json)
    }
    install(CallLogging)
    install(WebSockets)
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(mapOf("error" to (cause.message ?: "unknown error")))
        }
    }

    // Store auth context in application attributes so authCheck() can find it
    attributes.put(AuthContextKey, AuthContext(store.authMode, store.options))

    routing {
        // /health is always accessible — no auth required
        get("/health") {
            call.respond(store.health())
        }

        post("/api/v1/ingest") {
            if (!call.authCheck()) return@post
            val batch = call.receive<IngestBatch>()
            store.ingest(batch)
            call.respond(mapOf("accepted" to true))
        }

        webSocket("/api/v1/ingest/ws") {
            if (!call.authCheck()) return@webSocket
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val batch = json.decodeFromString(IngestBatch.serializer(), frame.readText())
                    store.ingest(batch)
                    send(Frame.Text("""{"accepted":true}"""))
                }
            }
        }

        post("/api/v1/logs/search") {
            if (!call.authCheck()) return@post
            call.respond(store.searchLogs(call.receive<SearchLogsRequest>()))
        }

        get("/api/v1/logs/{logId}") {
            if (!call.authCheck()) return@get
            val log = store.getLog(requireNotNull(call.parameters["logId"]))
            call.respond(log ?: mapOf("error" to "Log not found"))
        }

        get("/api/v1/frames/{frameId}") {
            if (!call.authCheck()) return@get
            val frame = store.getFrame(requireNotNull(call.parameters["frameId"]))
            call.respond(frame ?: mapOf("error" to "Frame not found"))
        }

        get("/api/v1/traces/{traceId}") {
            if (!call.authCheck()) return@get
            call.respond(store.getTrace(requireNotNull(call.parameters["traceId"])))
        }

        post("/api/v1/remote-rules") {
            if (!call.authCheck()) return@post
            call.respond(store.upsertRule(call.receive<RemoteRule>()))
        }

        get("/api/v1/remote-rules") {
            if (!call.authCheck()) return@get
            call.respond(store.listRules(call.request.queryParameters["appId"]))
        }

        post("/api/v1/purge") {
            if (!call.authCheck()) return@post
            val body = call.receive<Map<String, String>>()
            call.respond(
                store.createPurgeJob(
                    requestedBy = body["requestedBy"] ?: "api",
                    field = requireNotNull(body["field"]),
                    value = requireNotNull(body["value"]),
                ),
            )
        }

        get("/api/v1/purge/{purgeJobId}") {
            if (!call.authCheck()) return@get
            val job = store.getPurgeJob(requireNotNull(call.parameters["purgeJobId"]))
            call.respond(job ?: mapOf("error" to "Purge job not found"))
        }

        post("/mcp") {
            if (!call.authCheck()) return@post
            val request = call.receive<McpRequest>()
            val response = when (request.method) {
                "initialize" -> McpResponse(
                    id = request.id,
                    result = json.encodeToJsonElement(mapOf("server" to "ChronoTrace")),
                )

                "tools/list" -> McpResponse(
                    id = request.id,
                    result = json.encodeToJsonElement(mapOf("tools" to mcpTooling.descriptors())),
                )

                "tools/call" -> {
                    val toolRequest = ToolCallRequest(
                        name = requireNotNull(request.params["name"]),
                        arguments = request.params.filterKeys { it != "name" },
                    )
                    McpResponse(
                        id = request.id,
                        result = json.encodeToJsonElement(mcpTooling.call(toolRequest)),
                    )
                }
                else -> McpResponse(id = request.id, error = McpError(-32601, "Method not found"))
            }
            call.respondText(json.encodeToString(response), ContentType.Application.Json)
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
        bearerToken = environment.config.propertyOrNull("chronotrace.bearerToken")?.getString(),
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
    chronoTraceModule(ChronoStore(authMode, options))
}

// ---------------------------------------------------------------------------
// Auth check — private helpers scoped to this module
// ---------------------------------------------------------------------------

private val AuthContextKey = io.ktor.util.AttributeKey<AuthContext>("AuthContext")

private data class AuthContext(
    val authMode: String,
    val options: ChronoStoreOptions,
)

/**
 * Check authentication for the current request.
 * Must be called from within a route handler (ApplicationCall is in scope).
 *
 * Returns `true` if the request is authorized and may proceed.
 * Returns `false` after sending an HTTP 401 Unauthorized response.
 */
private suspend fun ApplicationCall.authCheck(): Boolean {
    val ctx = application.attributes.getOrNull(AuthContextKey)?.let { it as? AuthContext } ?: return true
    return when (ctx.authMode) {
        "none" -> true
        "apiKey" -> checkApiKey(ctx)
        "bearer" -> checkBearer(ctx)
        else -> true
    }
}

private suspend fun ApplicationCall.checkApiKey(ctx: AuthContext): Boolean {
    val provided = request.header("X-Api-Key")
    if (provided == null || provided !in ctx.options.apiKeys) {
        respond(
            HttpStatusCode.Unauthorized,
            mapOf("error" to "Unauthorized", "reason" to "Invalid or missing X-Api-Key"),
        )
        return false
    }
    return true
}

private suspend fun ApplicationCall.checkBearer(ctx: AuthContext): Boolean {
    val authHeader = request.header(HttpHeaders.Authorization)
    val expectedToken = ctx.options.bearerToken

    if (authHeader == null || expectedToken == null) {
        respond(
            HttpStatusCode.Unauthorized,
            mapOf("error" to "Unauthorized", "reason" to "Missing Authorization header"),
        )
        return false
    }
    if (!authHeader.startsWith("Bearer ", ignoreCase = true)) {
        respond(
            HttpStatusCode.Unauthorized,
            mapOf("error" to "Unauthorized", "reason" to "Authorization must use Bearer scheme"),
        )
        return false
    }
    val provided = authHeader.substringAfter("Bearer ").trim()
    if (provided != expectedToken) {
        respond(
            HttpStatusCode.Unauthorized,
            mapOf("error" to "Unauthorized", "reason" to "Invalid bearer token"),
        )
        return false
    }
    return true
}
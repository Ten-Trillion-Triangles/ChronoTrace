package org.chronotrace.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class McpRequest(
    val jsonrpc: String = "2.0",
    val id: String? = null,
    val method: String,
    val params: Map<String, String> = emptyMap(),
)

@Serializable
data class McpResponse(
    val jsonrpc: String = "2.0",
    val id: String? = null,
    val result: JsonElement? = null,
    val error: McpError? = null,
)

@Serializable
data class McpError(
    val code: Int,
    val message: String,
)

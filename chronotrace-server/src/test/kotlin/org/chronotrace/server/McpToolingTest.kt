package org.chronotrace.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.chronotrace.contract.ToolCallRequest

class McpToolingTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val tooling = McpTooling(ChronoStore("none"), json)

    // ---------------------------------------------------------------------------
    // Schema structure tests — each descriptor must have real JSON Schema content
    // ---------------------------------------------------------------------------

    @Test
    fun `search_logs input schema has real properties not placeholder`() {
        val descriptor = tooling.descriptors().first { it.name == "search_logs" }
        val schema = json.parseToJsonElement(descriptor.inputSchema).jsonObject
        assertHasProperties(schema, setOf("appId", "environment", "textQuery", "traceId", "spanId", "limit"))
        assertHasType(schema, "object")
    }

    @Test
    fun `search_logs output schema documents result shape`() {
        val descriptor = tooling.descriptors().first { it.name == "search_logs" }
        val schema = json.parseToJsonElement(descriptor.outputSchema).jsonObject
        assertHasProperties(schema, setOf("items", "nextCursor"))
        assertHasType(schema, "object")
    }

    @Test
    fun `get_log input schema requires logId`() {
        val descriptor = tooling.descriptors().first { it.name == "get_log" }
        val schema = json.parseToJsonElement(descriptor.inputSchema).jsonObject
        assertHasProperties(schema, setOf("logId"))
        assertRequired(schema, setOf("logId"))
        assertPropertyType(schema, "logId", "string")
    }

    @Test
    fun `get_log output schema describes LogRecord shape`() {
        val descriptor = tooling.descriptors().first { it.name == "get_log" }
        val schema = json.parseToJsonElement(descriptor.outputSchema).jsonObject
        assertHasProperties(schema, setOf("logId", "appId", "environment", "level", "message", "timestampUtc"))
        assertPropertyType(schema, "logId", "string")
        assertPropertyType(schema, "level", "string")
        assertPropertyType(schema, "timestampUtc", "integer")
    }

    @Test
    fun `get_frame_snapshot input schema defines frameId and logId`() {
        val descriptor = tooling.descriptors().first { it.name == "get_frame_snapshot" }
        val schema = json.parseToJsonElement(descriptor.inputSchema).jsonObject
        val props = schema["properties"]?.jsonObject
        assertNotNull(props?.get("frameId"), "frameId property must be defined")
        assertNotNull(props?.get("logId"), "logId property must be defined")
    }

    @Test
    fun `get_frame_snapshot output schema describes FrameSnapshot shape`() {
        val descriptor = tooling.descriptors().first { it.name == "get_frame_snapshot" }
        val schema = json.parseToJsonElement(descriptor.outputSchema).jsonObject
        assertHasProperties(schema, setOf("frameId", "traceId", "spanId", "callStack", "localsJson", "serializationMetadata"))
    }

    @Test
    fun `get_trace input schema requires traceId`() {
        val descriptor = tooling.descriptors().first { it.name == "get_trace" }
        val schema = json.parseToJsonElement(descriptor.inputSchema).jsonObject
        assertHasProperties(schema, setOf("traceId"))
        assertRequired(schema, setOf("traceId"))
        assertPropertyType(schema, "traceId", "string")
    }

    @Test
    fun `get_trace output schema describes TraceView shape`() {
        val descriptor = tooling.descriptors().first { it.name == "get_trace" }
        val schema = json.parseToJsonElement(descriptor.outputSchema).jsonObject
        assertHasProperties(schema, setOf("traceId", "spans", "logs", "frameSnapshots"))
        assertPropertyType(schema, "traceId", "string")
        assertPropertyType(schema, "spans", "array")
        assertPropertyType(schema, "logs", "array")
        assertPropertyType(schema, "frameSnapshots", "array")
    }

    @Test
    fun `step_frames input schema documents frameId direction and count`() {
        val descriptor = tooling.descriptors().first { it.name == "step_frames" }
        val schema = json.parseToJsonElement(descriptor.inputSchema).jsonObject
        assertHasProperties(schema, setOf("frameId", "direction", "count"))
        assertRequired(schema, setOf("frameId"))
        assertPropertyType(schema, "frameId", "string")
        assertPropertyType(schema, "direction", "string")
        assertPropertyType(schema, "count", "integer")
    }

    @Test
    fun `step_frames output schema is array of FrameSnapshot`() {
        val descriptor = tooling.descriptors().first { it.name == "step_frames" }
        val schema = json.parseToJsonElement(descriptor.outputSchema).jsonObject
        assertHasType(schema, "array")
    }

    @Test
    fun `list_remote_rules input schema accepts optional appId filter`() {
        val descriptor = tooling.descriptors().first { it.name == "list_remote_rules" }
        val schema = json.parseToJsonElement(descriptor.inputSchema).jsonObject
        val props = schema["properties"]?.jsonObject
        assertNotNull(props?.get("appId"), "appId property must be defined")
        assertPropertyType(schema, "appId", "string")
    }

    @Test
    fun `list_remote_rules output schema is array of RemoteRule`() {
        val descriptor = tooling.descriptors().first { it.name == "list_remote_rules" }
        val schema = json.parseToJsonElement(descriptor.outputSchema).jsonObject
        assertHasType(schema, "array")
    }

    @Test
    fun `upsert_remote_rule input schema requires rule field with RemoteRule shape`() {
        val descriptor = tooling.descriptors().first { it.name == "upsert_remote_rule" }
        val schema = json.parseToJsonElement(descriptor.inputSchema).jsonObject
        assertHasProperties(schema, setOf("rule"))
        assertRequired(schema, setOf("rule"))
        val ruleProps = schema["properties"]?.jsonObject?.get("rule")?.jsonObject
        assertNotNull(ruleProps, "rule property must be a schema object")
        assertHasProperties(ruleProps!!, setOf("ruleId", "expression", "ttlSeconds", "createdBy"))
    }

    @Test
    fun `upsert_remote_rule output schema returns RemoteRule`() {
        val descriptor = tooling.descriptors().first { it.name == "upsert_remote_rule" }
        val schema = json.parseToJsonElement(descriptor.outputSchema).jsonObject
        assertHasProperties(schema, setOf("ruleId", "enabled", "targetApps", "ttlSeconds", "priority", "expression", "captureMode", "sampleLimit", "createdBy"))
    }

    @Test
    fun `delete_remote_rule input schema requires ruleId`() {
        val descriptor = tooling.descriptors().first { it.name == "delete_remote_rule" }
        val schema = json.parseToJsonElement(descriptor.inputSchema).jsonObject
        assertHasProperties(schema, setOf("ruleId"))
        assertRequired(schema, setOf("ruleId"))
        assertPropertyType(schema, "ruleId", "string")
    }

    @Test
    fun `delete_remote_rule output schema describes deleted boolean`() {
        val descriptor = tooling.descriptors().first { it.name == "delete_remote_rule" }
        val schema = json.parseToJsonElement(descriptor.outputSchema).jsonObject
        assertHasProperties(schema, setOf("deleted"))
        assertPropertyType(schema, "deleted", "boolean")
    }

    @Test
    fun `create_purge_job input schema requires field and value`() {
        val descriptor = tooling.descriptors().first { it.name == "create_purge_job" }
        val schema = json.parseToJsonElement(descriptor.inputSchema).jsonObject
        assertHasProperties(schema, setOf("requestedBy", "field", "value"))
        assertRequired(schema, setOf("field", "value"))
        assertPropertyType(schema, "field", "string")
        assertPropertyType(schema, "value", "string")
        assertPropertyType(schema, "requestedBy", "string")
    }

    @Test
    fun `create_purge_job output schema describes PurgeJob shape`() {
        val descriptor = tooling.descriptors().first { it.name == "create_purge_job" }
        val schema = json.parseToJsonElement(descriptor.outputSchema).jsonObject
        assertHasProperties(schema, setOf("purgeJobId", "requestedAtUtc", "requestedBy", "selector", "status"))
        assertPropertyType(schema, "status", "string")
    }

    @Test
    fun `get_purge_job input schema requires purgeJobId`() {
        val descriptor = tooling.descriptors().first { it.name == "get_purge_job" }
        val schema = json.parseToJsonElement(descriptor.inputSchema).jsonObject
        assertHasProperties(schema, setOf("purgeJobId"))
        assertRequired(schema, setOf("purgeJobId"))
        assertPropertyType(schema, "purgeJobId", "string")
    }

    @Test
    fun `get_purge_job output schema describes PurgeJob shape with nullable status`() {
        val descriptor = tooling.descriptors().first { it.name == "get_purge_job" }
        val schema = json.parseToJsonElement(descriptor.outputSchema).jsonObject
        assertHasProperties(schema, setOf("purgeJobId", "requestedAtUtc", "requestedBy", "selector", "status", "completedAtUtc", "stats"))
    }

    @Test
    fun `get_system_health input schema has empty or no required arguments`() {
        val descriptor = tooling.descriptors().first { it.name == "get_system_health" }
        val schema = json.parseToJsonElement(descriptor.inputSchema).jsonObject
        // System health takes no arguments — properties may be empty or absent
        val hasProps = schema["properties"]?.jsonObject?.isNotEmpty() ?: false
        if (hasProps) {
            // if properties exist they should all be optional
            val required = schema["required"]?.jsonArray
            assertTrue(required == null || required.isEmpty(), "get_system_health should not require arguments")
        }
    }

    @Test
    fun `get_system_health output schema describes SystemHealth shape`() {
        val descriptor = tooling.descriptors().first { it.name == "get_system_health" }
        val schema = json.parseToJsonElement(descriptor.outputSchema).jsonObject
        assertHasProperties(schema, setOf(
            "authMode", "totalLogs", "totalSpans", "totalFrames",
            "totalRules", "totalPurgeJobs", "storageMode"
        ))
        assertPropertyType(schema, "totalLogs", "integer")
        assertPropertyType(schema, "storageMode", "string")
    }

    // ---------------------------------------------------------------------------
    // All 11 tools must be present
    // ---------------------------------------------------------------------------

    @Test
    fun `all 11 tools are registered`() {
        val names = tooling.descriptors().map { it.name }.toSet()
        val expected = setOf(
            "search_logs", "get_log", "get_frame_snapshot", "get_trace",
            "step_frames", "list_remote_rules", "upsert_remote_rule",
            "delete_remote_rule", "create_purge_job", "get_purge_job",
            "get_system_health"
        )
        assertEquals(expected, names)
    }

    // ---------------------------------------------------------------------------
    // No placeholder schemas — every schema must have properties or be array type
    // ---------------------------------------------------------------------------

    @Test
    fun `no tool uses placeholder type-object-only schema for input`() {
        val placeholder = """{"type":"object"}"""
        for (descriptor in tooling.descriptors()) {
            assertFalse(
                descriptor.inputSchema == placeholder,
                "Tool '${descriptor.name}' inputSchema is still the placeholder $placeholder"
            )
            val schema = json.parseToJsonElement(descriptor.inputSchema).jsonObject
            val hasProps = schema["properties"]?.jsonObject?.isNotEmpty() ?: false
            val isArray = schema["type"]?.jsonPrimitive?.content == "array"
            // get_system_health takes no arguments — an empty properties object is valid
            val hasEmptyProperties = schema["properties"]?.jsonObject?.isEmpty() == true
            assertTrue(
                hasProps || isArray || hasEmptyProperties,
                "Tool '${descriptor.name}' inputSchema lacks properties: ${descriptor.inputSchema}"
            )
        }
    }

    // ---------------------------------------------------------------------------
    // Functional test: call search_logs and verify structured response
    // ---------------------------------------------------------------------------

    @Test
    fun `search_logs call returns structured JSON with items`() {
        val request = ToolCallRequest(
            name = "search_logs",
            arguments = mapOf("appId" to "payments", "limit" to "10"),
        )
        val response = tooling.call(request)
        assertFalse(response.isError, "Expected successful call, got error: ${response.text}")
        val body = json.parseToJsonElement(response.structuredContent).jsonObject
        assertNotNull(body["items"], "response must contain 'items' key")
    }

    @Test
    fun `get_log call with unknown id returns isError true`() {
        val request = ToolCallRequest(
            name = "get_log",
            arguments = mapOf("logId" to "nonexistent-log"),
        )
        val response = tooling.call(request)
        assertTrue(response.isError, "Expected isError=true for missing log")
    }

    @Test
    fun `get_system_health call returns health counters`() {
        val request = ToolCallRequest(name = "get_system_health", arguments = emptyMap())
        val response = tooling.call(request)
        assertFalse(response.isError, "Expected successful call")
        val body = json.parseToJsonElement(response.structuredContent).jsonObject
        assertNotNull(body["totalLogs"], "health must contain totalLogs")
        assertNotNull(body["storageMode"], "health must contain storageMode")
    }

    // ---------------------------------------------------------------------------
    // Helper assertion functions
    // ---------------------------------------------------------------------------

    private fun assertHasProperties(schema: JsonObject, expectedProps: Set<String>) {
        val props = schema["properties"]?.jsonObject?.keys ?: emptySet()
        for (prop in expectedProps) {
            assertTrue(
                prop in props,
                "Schema missing property '$prop'. Has: $props. Schema: $schema"
            )
        }
    }

    private fun assertHasType(schema: JsonObject, expectedType: String) {
        val actualType = schema["type"]?.jsonPrimitive?.content
        assertEquals(
            expectedType, actualType,
            "Expected type '$expectedType' but got '$actualType'. Schema: $schema"
        )
    }

    private fun assertRequired(schema: JsonObject, expectedRequired: Set<String>) {
        val requiredArray = schema["required"]?.jsonArray
        assertNotNull(requiredArray, "Schema must define 'required' field. Schema: $schema")
        val required = requiredArray.map { it.jsonPrimitive.content }.toSet()
        for (req in expectedRequired) {
            assertTrue(
                req in required,
                "Property '$req' must be in required. Required fields: $required. Schema: $schema"
            )
        }
    }

    private fun assertPropertyType(schema: JsonObject, propName: String, expectedType: String) {
        val propType = schema["properties"]
            ?.jsonObject
            ?.get(propName)
            ?.jsonObject
            ?.get("type")
            ?.jsonPrimitive
            ?.content
        assertEquals(
            expectedType, propType,
            "Property '$propName' should have type '$expectedType' but got '$propType'. Schema: $schema"
        )
    }
}
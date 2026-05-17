# MCP Transport Contract

**Status:** Final
**Applies to:** ChronoTrace Server `POST /mcp` endpoint
**Supersedes:** `05-phase-mcp-and-agent-interfaces.md` compatibility section

---

## 1. Transport

The `/mcp` endpoint is a **JSON-RPC 2.0** interface over **HTTPS POST**. It is the sole, final AI-facing protocol — not a compatibility shim.

```
POST /mcp
Content-Type: application/json
Authorization: <per auth mode>   (applied before MCP handler, same as other /api/v1/* routes)
```

### 1.1 Request Shape

```json
{
  "jsonrpc": "2.0",
  "id": "<opaque string, copy this into response>",
  "method": "<method name>",
  "params": { ... }
}
```

`id` may be `null` for notifications (caller does not expect a response), but every request that expects a reply must include a non-null `id`.

### 1.2 Response Shape

```json
{
  "jsonrpc": "2.0",
  "id": "<copied from request>",
  "result": { ... },   // present on success
  "error": null
}
// OR
{
  "jsonrpc": "2.0",
  "id": "<copied from request>",
  "result": null,
  "error": { "code": <integer>, "message": "<string>" }
}
```

HTTP status is always `200 OK` unless Ktor itself throws (e.g., deserialization failure before reaching the handler). Errors follow JSON-RPC conventions — the application error code is inside the `error` object.

### 1.3 Auth Handling

`/mcp` is protected by the same `authCheck()` guard as all other `/api/v1/*` routes. Auth is evaluated before the JSON-RPC method is dispatched. An unauthenticated request receives an HTTP 401 and never reaches the MCP handler.

---

## 2. Supported Methods

### 2.1 `initialize`

Returns server identity. Agents should call this once per session.

**Request params:** `{}` (empty)

**Response:**
```json
{
  "server": "ChronoTrace"
}
```

Future versions may expand this response. Agents must not assume the response is limited to the above fields.

### 2.2 `tools/list`

Returns the tool catalog — one entry per available tool.

**Request params:** `{}` (empty)

**Response:**
```json
{
  "tools": [
    {
      "name": "search_logs",
      "description": "...",
      "inputSchema": "<JSON schema string>",
      "outputSchema": "<JSON schema string>"
    },
    ...
  ]
}
```

The catalog reflects the current server state (e.g., a newly created remote rule does not appear until the next `tools/list` call).

### 2.3 `tools/call`

Invoke a named tool with arguments.

**Request params:**
```json
{
  "name": "<tool name>",
  "<arg1>": "<value1>",
  "<arg2>": "<value2>"
}
```

Every tool argument is a `string`. The server coerces to the target type internally. Coercion failures produce a JSON-RPC error response (code `-32602`).

**Response** — a `ToolCallResponse` JSON object:
```json
{
  "structuredContent": "<JSON string — parse this for machine-readable results>",
  "text": "<human-readable one-line summary>",
  "isError": false
}
```

On tool-level errors (resource not found, etc.), `isError` is `true` but the HTTP status is still `200`. Only transport-level failures produce non-200 responses.

---

## 3. Tool Catalog

All 11 tools are listed with their input arguments and output shapes. `→` denotes required fields.

### 3.1 `search_logs`

Search logs with filters and cursor pagination.

| Arg | Type | Notes |
|-----|------|-------|
| `appId` | string | Filter by application ID |
| `environment` | string | e.g. `local`, `prod` |
| `textQuery` | string | Case-insensitive substring within log messages |
| `traceId` | string | Logs belonging to this trace |
| `spanId` | string | Logs belonging to this span |
| `level` | string | One of: `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`, `FATAL` |
| `startTimeUtc` | integer | Unix timestamp ms — logs at or after this time |
| `endTimeUtc` | integer | Unix timestamp ms — logs at or before this time |
| `hasFrame` | boolean | If `true`, return only logs with an associated frame snapshot |
| `cursor` | string | Opaque pagination cursor from previous `nextCursor` |
| `limit` | integer | 1–500, default 100 |

**Output:**
```json
{
  "items": [ LogRecord, ... ],
  "nextCursor": "<string or null>"
}
```

Results are ordered newest-first by `timestampUtc + sequenceId`. When `nextCursor` is non-null, pass it as the `cursor` argument to fetch the next page. When `nextCursor` is `null`, there are no more results.

**Truncation:** `fields` maps are capped server-side to 50 entries; overflowing entries are dropped silently.

---

### 3.2 `get_log`

Fetch a single log by ID.

| Arg | Type | Notes |
|-----|------|-------|
| `logId` | string | → Required |

**Output:** A `LogRecord` object, or the response `isError` is `true` with `text: "Log not found"` when absent.

---

### 3.3 `get_frame_snapshot`

Fetch a frame snapshot by frame ID or via a linked log ID.

| Arg | Type | Notes |
|-----|------|-------|
| `frameId` | string | Provide frameId OR logId, not both |
| `logId` | string | Retrieve frame linked to this log. Provide logId OR frameId, not both |

Exactly one of `frameId` or `logId` must be present — passing both, or neither, produces a JSON-RPC error.

**Output:** A `FrameSnapshot` object, or `isError: true` when absent.

---

### 3.4 `get_trace`

Fetch a complete trace: all spans, logs, and frame snapshots for a trace.

| Arg | Type | Notes |
|-----|------|-------|
| `traceId` | string | → Required |

**Output:**
```json
{
  "traceId": "<string>",
  "spans": [ SpanRecord, ... ],
  "logs": [ LogRecord, ... ],
  "frameSnapshots": [ FrameSnapshot, ... ]
}
```

Spans are sorted by `startTimeUtc`. Logs and frame snapshots are sorted by `timestampUtc + sequenceId`.

**Truncation:** Large traces are returned in full. Agents should paginate with `search_logs` first if only specific log subsets are needed.

---

### 3.5 `step_frames`

Navigate temporally adjacent frames from a reference frame.

| Arg | Type | Notes |
|-----|------|-------|
| `frameId` | string | → Required. The reference frame |
| `direction` | string | `forward` (default) or `backward` |
| `count` | integer | 1–25, default 1 |

**Output:** Array of `FrameSnapshot` objects in traversal order, up to `count` frames.

Traversal is ordered by `(timestampUtc, sequenceId)`. A frame that has been deleted from storage is silently skipped — the traversal returns the next available frame instead.

---

### 3.6 `list_remote_rules`

List active remote capture rules.

| Arg | Type | Notes |
|-----|------|-------|
| `appId` | string | Filter rules targeting this appId. Returns all rules with empty `targetApps` list as well. |

**Output:** Array of `RemoteRule` objects sorted by `priority` descending, then `ruleId`.

---

### 3.7 `upsert_remote_rule`

Create or update a remote capture rule.

| Arg | Type | Notes |
|-----|------|-------|
| `rule` | string (JSON) | → Required. Full `RemoteRule` object as a JSON string |

To update an existing rule, include its `ruleId`. To create a new rule, use a new `ruleId`.

**Output:** The saved `RemoteRule` (echoed back with server-applied defaults filled in).

---

### 3.8 `delete_remote_rule`

Delete a rule by ID.

| Arg | Type | Notes |
|-----|------|-------|
| `ruleId` | string | → Required |

**Output:**
```json
{ "deleted": true }
```
`deleted` is `true` if the rule existed and was deleted, `false` if it did not exist.

---

### 3.9 `create_purge_job`

Submit an asynchronous purge job.

| Arg | Type | Notes |
|-----|------|-------|
| `requestedBy` | string | Caller identifier (default: `"mcp"`) |
| `field` | string | → Required. One of: `appId`, `environment`, `traceId`, `spanId` |
| `value` | string | → Required. Value to match |

**Output:** A `PurgeJob` descriptor in `ACCEPTED` status.

Purge execution is asynchronous. Poll `get_purge_job` to observe completion.

---

### 3.10 `get_purge_job`

Poll purge job status.

| Arg | Type | Notes |
|-----|------|-------|
| `purgeJobId` | string | → Required |

**Output:** The `PurgeJob` at query time, or `isError: true` when the job ID is not found.

---

### 3.11 `get_system_health`

Get storage counters and health indicators.

| Arg | Type | Notes |
|-----|------|-------|
| — | — | No arguments |

**Output:**
```json
{
  "authMode": "none" | "apiKey" | "bearer",
  "totalLogs": <integer>,
  "totalSpans": <integer>,
  "totalFrames": <integer>,
  "totalRules": <integer>,
  "totalPurgeJobs": <integer>,
  "storageMode": "file" | "memory" | "clickhouse",
  "clickhouseHealthy": <boolean or null>,
  "valkeyHealthy": <boolean or null>
}
```

`clickhouseHealthy` and `valkeyHealthy` are present only when `storageMode` is `clickhouse`.

---

## 4. Agent Usage Contract

### 4.1 Calling Convention

Every tool call takes a flat map of string keys to string values. The agent should:

1. Build arguments as key-value pairs.
2. Call `tools/call` with `name` set to the tool name and all other params as the argument map.
3. Parse `structuredContent` as JSON for machine-readable data.
4. Read `text` for a human-readable summary.
5. Check `isError` — if `true`, the tool executed but found no result or hit a handled error.

### 4.2 Error Taxonomy

| HTTP Status | `isError` | Meaning |
|-------------|-----------|---------|
| 200 | `false` | Success |
| 200 | `true` | Tool ran but returned no data (not found, empty result) |
| 401 | N/A | Unauthenticated — fix auth headers |
| 400 | N/A | Malformed JSON-RPC request (Ktor deserialization failed) |
| 500 | N/A | Server error — retry with backoff |

### 4.3 Pagination Pattern

For `search_logs`:

```
1. Call search_logs with limit=N
2. If nextCursor != null, call search_logs again with cursor=<nextCursor>
3. Repeat until nextCursor is null
```

Do not assume a fixed page size. The server may return fewer results than `limit`.

### 4.4 Truncation Behavior

| Field | Trigger | Behavior |
|-------|---------|----------|
| `LogRecord.fields` | > 50 entries | Dropped silently, no marker |
| `FrameSnapshot.localsJson` | `serializationMetadata.truncated == true` | Payload was truncated by the SDK before ingestion |
| `FrameSnapshot.localsJson` | `serializationMetadata.maxDepthReached == true` | Object nesting exceeded capture depth limit |
| `FrameSnapshot.localsJson` | `serializationMetadata.redactedFields` non-empty | Fields redacted by SDK policy — inspect the list |
| `FrameSnapshot.localsJson` | `serializationMetadata.droppedFields` non-empty | Fields dropped for size — inspect the list |

Agents must always check `serializationMetadata` on every `FrameSnapshot`, not just when `truncated` is `true`.

### 4.5 Source Correlation Guarantees

`FrameSnapshot.callStack[].filePath` is a **logical path** recorded by the SDK at capture time. It reflects the file path as it existed on the instrumented host. The following are NOT guaranteed:

- The file exists at that path on the agent's machine.
- The path is relative to the agent's working directory.
- The file content matches what was captured (source may have changed since capture).

When the agent needs to open a source file:

1. Attempt to resolve `filePath` relative to the agent's known source roots.
2. If the file is absent, do not retry indefinitely — surface the gap to the user with the frame's `frameId`, `filePath`, and `lineNumber`.

ChronoTrace does not perform source lookup or file existence checks. This is the agent's responsibility.

### 4.6 Idempotency

| Tool | Idempotent? | Notes |
|------|-------------|-------|
| `search_logs` | Yes | Read-only |
| `get_log` | Yes | Read-only |
| `get_frame_snapshot` | Yes | Read-only |
| `get_trace` | Yes | Read-only |
| `step_frames` | Yes | Read-only |
| `list_remote_rules` | Yes | Read-only |
| `upsert_remote_rule` | Yes (update) | Repeated calls with same `ruleId` overwrite |
| `delete_remote_rule` | Yes | `deleted: false` on non-existent rule — safe to retry |
| `create_purge_job` | No | Each call creates a new job |
| `get_purge_job` | Yes | Read-only |
| `get_system_health` | Yes | Read-only |

---

## 5. Compatibility Notes

The current `/mcp` endpoint **is the final shape**. It is not a shim.

The implementation deviates from the official MCP spec in the following ways — these are intentional design choices, not limitations:

1. **No capability negotiation.** `initialize` returns only `{"server": "ChronoTrace"}`. Future capability announcements will be additive and backwards-compatible.

2. **`params` is `Map<String, String>`.** The MCP spec permits arbitrary JSON values in `params`. ChronoTrace restricts tool call arguments to string values only. Complex structures (e.g., `upsert_remote_rule`'s `rule` argument) are serialized as JSON strings. This simplifies agent prompting.

3. **`tools/list` returns string schemas, not objects.** The `inputSchema` and `outputSchema` fields are JSON strings, not parsed objects. Agents should treat them as `application/json` schema documents.

4. **No streaming.** The endpoint does not support server-side streaming. Long-running operations (purge jobs) must be polled.

5. **Auth is HTTP-level, not MCP-level.** Authentication is handled by the surrounding HTTP server, not inside the JSON-RPC protocol. This means `/mcp` inherits whatever auth mode the server is configured with (`none`, `apiKey`, or `bearer`).

---

## 6. Data Type Reference

### LogRecord

| Field | Type | Nullable |
|-------|------|----------|
| `logId` | string | No |
| `appId` | string | No |
| `environment` | string | No |
| `sdkInstanceId` | string | No |
| `serviceName` | string | No |
| `traceId` | string | Yes |
| `spanId` | string | Yes |
| `parentSpanId` | string | Yes |
| `timestampUtc` | integer (ms) | No |
| `sequenceId` | integer | No |
| `level` | enum | No |
| `message` | string | No |
| `fields` | Map\<string, string\> | No (empty = `{}`) |
| `captureReason` | enum | Yes |
| `linkedFrameId` | string | Yes |

### SpanRecord

| Field | Type | Nullable |
|-------|------|----------|
| `spanId` | string | No |
| `traceId` | string | No |
| `appId` | string | No |
| `environment` | string | No |
| `serviceName` | string | No |
| `operationName` | string | No |
| `parentSpanId` | string | Yes |
| `startTimeUtc` | integer (ms) | No |
| `endTimeUtc` | integer (ms) | Yes |
| `status` | enum | No |
| `attributes` | Map\<string, string\> | No |

### FrameSnapshot

| Field | Type | Nullable |
|-------|------|----------|
| `frameId` | string | No |
| `traceId` | string | No |
| `spanId` | string | No |
| `appId` | string | No |
| `environment` | string | No |
| `sdkInstanceId` | string | No |
| `serviceName` | string | No |
| `timestampUtc` | integer (ms) | No |
| `sequenceId` | integer | No |
| `captureReason` | enum | No |
| `callStack` | array | No (may be empty) |
| `localsJson` | string | No |
| `serializationMetadata` | object | No |
| `logId` | string | Yes |

### CallStackItem

| Field | Type | Nullable |
|-------|------|----------|
| `functionName` | string | No |
| `filePath` | string | No |
| `lineNumber` | integer | No |
| `columnNumber` | integer | Yes |

### SerializationMetadata

| Field | Type | Notes |
|-------|------|-------|
| `truncated` | boolean | Payload exceeded size limit |
| `maxDepthReached` | boolean | Object nesting limit hit |
| `redactedFields` | array of string | Fields removed by SDK policy |
| `droppedFields` | array of string | Fields dropped for size |

### RemoteRule

| Field | Type | Nullable |
|-------|------|----------|
| `ruleId` | string | No |
| `enabled` | boolean | No |
| `targetApps` | array of string | No (empty = all apps) |
| `ttlSeconds` | integer | No |
| `priority` | integer | No |
| `expression` | string | No (CEL) |
| `captureMode` | enum | No |
| `sampleLimit` | integer | No |
| `createdBy` | string | No |

### PurgeJob

| Field | Type | Nullable |
|-------|------|----------|
| `purgeJobId` | string | No |
| `requestedAtUtc` | integer (ms) | No |
| `requestedBy` | string | No |
| `selector` | object | No |
| `status` | enum | No |
| `clickhouseMutationId` | string | Yes |
| `completedAtUtc` | integer (ms) | Yes |
| `stats` | Map\<string, string\> | No |

---

*Document version 1.0 — 2026-05-16*
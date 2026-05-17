# ChronoTrace API Reference

Complete documentation for the ChronoTrace server HTTP API.

**Base URL:** `http://<host>:<port>` (default: `http://127.0.0.1:8080`)

**Auth modes:** `none` (default), `apiKey`, `bearer`

When `authMode` is `apiKey`, all endpoints below require the header `X-Api-Key: <key>`.
When `authMode` is `bearer`, all endpoints require `Authorization: Bearer <token>`.
Admin endpoints additionally require the calling key to have `role: admin`.

**Rate limiting (apiKey mode):** Per-key sliding-window quotas. Exceeding the limit returns `429 Too Many Requests` with headers `Retry-After`, `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Window`.

**Audit logging:** Auth-protected endpoints log all requests (success, failure, quota-exceeded) to an internal audit trail queryable at `GET /api/v1/admin/audit/logs`.

---

## Table of Contents

1. [Ingest](#1-ingest)
2. [Search & Lookup](#2-search--lookup)
3. [Frame Stepping](#3-frame-stepping)
4. [Remote Rules](#4-remote-rules)
5. [MCP Tools](#5-mcp-tools)
6. [Metrics](#6-metrics)
7. [Key Management](#7-key-management)
8. [Purge](#8-purge)

---

## 1. Ingest

### `POST /api/v1/ingest`

Ingest a batch of spans, logs, and frame snapshots from a client SDK.

**Auth:** Required (apiKey or bearer)

**Request body:**
```json
{
  "client": {
    "appId": "string",
    "sdkInstanceId": "string",
    "environment": "string",
    "serviceName": "string"
  },
  "spans": [
    {
      "spanId": "string",
      "traceId": "string",
      "appId": "string",
      "environment": "string",
      "serviceName": "string",
      "operationName": "string",
      "parentSpanId": "string | null",
      "startTimeUtc": 1234567890000,
      "endTimeUtc": 1234567891000,
      "status": "OPEN | OK | ERROR | CANCELLED",
      "attributes": { "key": "value" }
    }
  ],
  "logs": [
    {
      "logId": "string",
      "appId": "string",
      "environment": "string",
      "sdkInstanceId": "string",
      "serviceName": "string",
      "traceId": "string | null",
      "spanId": "string | null",
      "parentSpanId": "string | null",
      "timestampUtc": 1234567890000,
      "sequenceId": 1,
      "level": "TRACE | DEBUG | INFO | WARN | ERROR | FATAL",
      "message": "string",
      "fields": { "key": "value" },
      "captureReason": "manual_trace | auto_capture_level | remote_rule | crash_flush | null",
      "linkedFrameId": "string | null",
      "triggeredRuleId": "string | null"
    }
  ],
  "frames": [
    {
      "frameId": "string",
      "traceId": "string",
      "spanId": "string",
      "appId": "string",
      "environment": "string",
      "sdkInstanceId": "string",
      "serviceName": "string",
      "timestampUtc": 1234567890000,
      "sequenceId": 1,
      "captureReason": "manual_trace | auto_capture_level | remote_rule | crash_flush",
      "callStack": [
        {
          "functionName": "string",
          "filePath": "string",
          "lineNumber": 42,
          "columnNumber": 3
        }
      ],
      "localsJson": "{ \"x\": 1 }",
      "serializationMetadata": {
        "truncated": false,
        "maxDepthReached": false,
        "redactedFields": [],
        "droppedFields": []
      },
      "logId": "string | null"
    }
  ]
}
```

**Response `200 OK`:**
```json
{ "accepted": true }
```

**Response `503 Service Unavailable`** (circuit breaker open):
```json
{ "error": "ingest_rejected", "message": "circuit breaker open" }
```

**Errors:** `401 Unauthorized`, `429 Too Many Requests`, `500 Internal Server Error`

---

### `WS /api/v1/ingest/ws`

WebSocket endpoint for streaming ingest. Auth and quota checked once at connect time. Each incoming text frame is parsed as an `IngestBatch` (same schema as POST body). Server responds with `{"accepted":true}` or `{"error":"..."}` per frame.

**Auth:** Required at connection time (apiKey or bearer)

**Incoming frame:** `IngestBatch` JSON (same as POST body)
**Outgoing frame:** `{"accepted": true}` or `{"error": "<message>"}`

---

## 2. Search & Lookup

### `POST /api/v1/logs/search`

Full-text and filtered log search.

**Auth:** Required (apiKey or bearer)

**Request body:**
```json
{
  "appId": "string",
  "environment": "string",
  "textQuery": "string",
  "traceId": "string",
  "spanId": "string",
  "level": "TRACE | DEBUG | INFO | WARN | ERROR | FATAL",
  "startTimeUtc": 1234567890000,
  "endTimeUtc": 1234567890000,
  "hasFrame": true,
  "cursor": "opaque-pagination-token",
  "limit": 100
}
```
All fields optional. `limit` defaults to 100, max 500.

**Response `200 OK`:**
```json
{
  "items": [
    {
      "logId": "string",
      "appId": "string",
      "environment": "string",
      "sdkInstanceId": "string",
      "serviceName": "string",
      "traceId": "string | null",
      "spanId": "string | null",
      "parentSpanId": "string | null",
      "timestampUtc": 1234567890000,
      "sequenceId": 1,
      "level": "INFO",
      "message": "string",
      "fields": { "key": "value" },
      "captureReason": "manual_trace | auto_capture_level | remote_rule | crash_flush | null",
      "linkedFrameId": "string | null",
      "triggeredRuleId": "string | null"
    }
  ],
  "nextCursor": "opaque-token | null"
}
```
Items are sorted newest-first by `timestampUtc + sequenceId`.

**Errors:** `401 Unauthorized`, `429 Too Many Requests`, `500 Internal Server Error`

---

### `GET /api/v1/logs/{logId}`

Fetch a single log record by ID.

**Auth:** Required (apiKey or bearer)

**Path params:** `logId` — the log identifier

**Response `200 OK`:** LogRecord object (same shape as items in search response)

**Response `404 Not Found`:** `{ "error": "Log not found" }`

**Errors:** `401 Unauthorized`, `429 Too Many Requests`

---

### `GET /api/v1/traces/{traceId}`

Fetch a complete trace: all spans, logs, and frame snapshots for a trace ID.

**Auth:** Required (apiKey or bearer)

**Path params:** `traceId` — the trace identifier

**Response `200 OK`:**
```json
{
  "traceId": "string",
  "spans": [ /* array of SpanRecord */ ],
  "logs": [ /* array of LogRecord */ ],
  "frameSnapshots": [ /* array of FrameSnapshot */ ]
}
```
Spans sorted by `startTimeUtc`. Logs and frames sorted by `timestampUtc + sequenceId`.

**Errors:** `401 Unauthorized`, `429 Too Many Requests`

---

## 3. Frame Stepping

### `GET /api/v1/frames/{frameId}`

Fetch a single frame snapshot by ID.

**Auth:** Required (apiKey or bearer)

**Path params:** `frameId` — the frame identifier

**Response `200 OK`:**
```json
{
  "frameId": "string",
  "traceId": "string",
  "spanId": "string",
  "appId": "string",
  "environment": "string",
  "sdkInstanceId": "string",
  "serviceName": "string",
  "timestampUtc": 1234567890000,
  "sequenceId": 1,
  "captureReason": "manual_trace | auto_capture_level | remote_rule | crash_flush",
  "callStack": [
    {
      "functionName": "string",
      "filePath": "string",
      "lineNumber": 42,
      "columnNumber": 3
    }
  ],
  "localsJson": "{ \"x\": 1 }",
  "serializationMetadata": {
    "truncated": false,
    "maxDepthReached": false,
    "redactedFields": [],
    "droppedFields": []
  },
  "logId": "string | null"
}
```

**Response `404 Not Found`:** `{ "error": "Frame not found" }`

**Errors:** `401 Unauthorized`, `429 Too Many Requests`

---

## 4. Remote Rules

Remote capture rules evaluated server-side against each ingested log.

### `GET /api/v1/remote-rules`

List all active remote rules.

**Auth:** Required (apiKey or bearer)

**Query params:**
- `appId` (optional) — filter rules targeting this appId (returns rules with empty `targetApps` as well)

**Response `200 OK`:**
```json
[
  {
    "ruleId": "string",
    "enabled": true,
    "targetApps": ["app-a", "app-b"],
    "ttlSeconds": 3600,
    "priority": 10,
    "expression": "log.level == 'ERROR'",
    "captureMode": "remote_rule",
    "sampleLimit": 1,
    "createdBy": "admin",
    "createdAtUtc": 1234567890000,
    "expiresAtUtc": 1234567903600
  }
]
```
Sorted by `priority` descending, then `ruleId`.

**Errors:** `401 Unauthorized`, `429 Too Many Requests`

---

### `POST /api/v1/remote-rules`

Create or update a remote capture rule.

**Auth:** Required (apiKey or bearer)

**Request body:** `RemoteRule` object (same as list item, full object required)

```json
{
  "ruleId": "my-error-rule",
  "enabled": true,
  "targetApps": [],
  "ttlSeconds": 3600,
  "priority": 10,
  "expression": "log.level == 'ERROR'",
  "captureMode": "remote_rule",
  "sampleLimit": 1,
  "createdBy": "admin"
}
```

**Response `200 OK`:** The saved rule (echoed back)

**Errors:** `401 Unauthorized`, `429 Too Many Requests`, `500 Internal Server Error`

---

## 5. MCP Tools

Model Context Protocol (MCP) interface. Single `POST /mcp` endpoint dispatches `initialize`, `tools/list`, and `tools/call`.

### `POST /mcp`

**Auth:** Required (apiKey or bearer)

**Request body (JSON-RPC 2.0 style):**
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize | tools/list | tools/call",
  "params": { ... }
}
```

#### `initialize`
**Params:** none

**Response:**
```json
{
  "protocolVersion": "2025-03-26",
  "capabilities": { "tools": {} },
  "serverInfo": { "name": "ChronoTrace", "version": "1.0.0" }
}
```

#### `tools/list`
**Params:** `{}`

**Response:**
```json
{
  "tools": [
    { "name": "search_logs", "description": "...", "inputSchema": "{ ... }", "outputSchema": "{ ... }" },
    ...
  ]
}
```

#### `tools/call`
**Params:**
```json
{
  "name": "search_logs",
  "arguments": { "appId": "my-app", "limit": 50 }
}
```

**Available tools:**

| Tool | Description |
|------|-------------|
| `search_logs` | Search logs with filters |
| `get_log` | Fetch a single log by logId |
| `get_frame_snapshot` | Fetch a frame by frameId or logId |
| `get_trace` | Fetch a complete trace by traceId |
| `step_frames` | Navigate adjacent frames in time |
| `list_remote_rules` | List remote capture rules |
| `upsert_remote_rule` | Create or update a rule |
| `delete_remote_rule` | Delete a rule by ruleId |
| `create_purge_job` | Submit an async purge job |
| `get_purge_job` | Poll purge job status |
| `get_system_health` | Aggregated health and storage counters |

Each tool's full input/output schema is defined in `McpTooling.kt`.

**Errors:** `401 Unauthorized`, `429 Too Many Requests`

---

## 6. Metrics

### `GET /health`

Health check. No auth.

**Response `200 OK`:**
```json
{
  "authMode": "none | apiKey | bearer",
  "totalLogs": 12345,
  "totalSpans": 6789,
  "totalFrames": 234,
  "totalRules": 5,
  "totalPurgeJobs": 2,
  "storageMode": "file | memory | clickhouse",
  "clickhouseHealthy": true,
  "valkeyHealthy": true
}
```
`clickhouseHealthy` and `valkeyHealthy` only present in `clickhouse` storage mode.

---

### `GET /metrics`

Prometheus-compatible text format metrics. No auth.

**Response `200 OK`** — `Content-Type: text/plain; charset=UTF-8`

```
# HELP chronotrace_ingest_total Total ingest calls (IngestBatch) received by the server
# TYPE chronotrace_ingest_total counter
chronotrace_ingest_total 12345 1747500000000000

# HELP chronotrace_ingest_errors_total Ingest calls that threw an exception
# TYPE chronotrace_ingest_errors_total counter
chronotrace_ingest_errors_total 7 1747500000000000

# HELP chronotrace_dropped_events_total Events explicitly dropped by the server
# TYPE chronotrace_dropped_events_total counter
chronotrace_dropped_events_total 0 1747500000000000

# HELP chronotrace_active_connections Current active HTTP/WebSocket connections
# TYPE chronotrace_active_connections gauge
chronotrace_active_connections 3 1747500000000000

# HELP chronotrace_queue_size Estimated queue depth (Valkey-backed purge jobs in ACCEPTED or RUNNING)
# TYPE chronotrace_queue_size gauge
chronotrace_queue_size 0 1747500000000000

# HELP chronotrace_query_latency_seconds Latency of query/search operations in seconds
# TYPE chronotrace_query_latency_seconds histogram
chronotrace_query_latency_seconds_bucket{le="0.001"} 0 1747500000000000
...
chronotrace_query_latency_seconds_bucket{le="+Inf"} 100 1747500000000000
chronotrace_query_latency_seconds_sum 1.234 1747500000000000
chronotrace_query_latency_seconds_count 100 1747500000000000
```

---

## 7. Key Management

All admin key endpoints require `role: admin` on the calling key.

### `GET /api/v1/admin/keys`

List all API keys (metadata only, no key values returned).

**Auth:** Required — admin role

**Query params:**
- `role` (optional) — filter by `admin` or `client`
- `appId` (optional) — filter by appId

**Response `200 OK`:** JSON array
```json
[
  {
    "keyId": "ktx-abc123",
    "role": "client",
    "appId": "my-app",
    "createdAtUtc": 1234567890000,
    "rotatedAtUtc": null,
    "revokedAtUtc": null,
    "quota": {
      "limit": 1000,
      "windowSeconds": 60
    }
  }
]
```

**Response `403 Forbidden`:** `{ "error": "Forbidden", "reason": "admin role required" }`

**Errors:** `401 Unauthorized`, `403 Forbidden`

---

### `POST /api/v1/admin/keys`

Create a new API key.

**Auth:** Required — admin role

**Request body:**
```json
{
  "role": "client",
  "appId": "my-app",
  "quota": {
    "limit": 1000,
    "windowSeconds": 60
  }
}
```
`role` defaults to `client`. `quota` optional.

**Response `201 Created`:**
```json
{
  "keyId": "ktx-abc123",
  "keyValue": "ct_key_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
  "role": "client",
  "appId": "my-app",
  "createdAtUtc": 1234567890000,
  "quota": {
    "limit": 1000,
    "windowSeconds": 60
  }
}
```
`keyValue` is returned only on creation.

**Errors:** `400 Bad Request`, `401 Unauthorized`, `403 Forbidden`

---

### `POST /api/v1/admin/keys/{keyId}/rotate`

Rotate an existing key (generates a new `keyValue`, invalidates old).

**Auth:** Required — admin role

**Path params:** `keyId` — the key to rotate

**Response `200 OK`:**
```json
{
  "keyId": "ktx-abc123",
  "keyValue": "ct_key_yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy",
  "rotatedAtUtc": 1234567895000
}
```

**Response `404 Not Found`:** `{ "error": "Not found", "reason": "key not found" }`

**Errors:** `401 Unauthorized`, `403 Forbidden`

---

### `DELETE /api/v1/admin/keys/{keyId}`

Revoke a key immediately.

**Auth:** Required — admin role

**Path params:** `keyId` — the key to revoke

**Response `204 No Content`**

**Response `400 Bad Request`:** `{ "error": "bad_request", "reason": "cannot revoke own key" }`

**Response `404 Not Found`:** `{ "error": "Not found", "reason": "key not found" }`

**Errors:** `401 Unauthorized`, `403 Forbidden`

---

### `GET /api/v1/admin/audit/logs`

Query the audit log.

**Auth:** Required — admin role

**Query params:**
- `apiKeyId` (optional) — filter by key
- `action` (optional) — filter by action name (e.g. `ingest`, `search`)
- `outcome` (optional) — filter by outcome (e.g. `success`, `unauthorized`, `error`)
- `startTimeUtc` (optional) — Unix timestamp ms, inclusive
- `endTimeUtc` (optional) — Unix timestamp ms, inclusive
- `appId` (optional) — filter by appId
- `limit` (optional) — default 100
- `cursor` (optional) — pagination cursor

**Response `200 OK`:**
```json
{
  "items": [
    {
      "entryId": "audit-1234567890",
      "timestampUtc": 1234567890000,
      "apiKeyId": "ktx-abc123",
      "action": "ingest",
      "endpoint": "/api/v1/ingest",
      "method": "POST",
      "outcome": "success",
      "statusCode": 200,
      "durationMs": 12,
      "appId": "my-app",
      "sdkInstanceId": "sdk-001",
      "traceId": null,
      "ipAddress": "192.168.1.1"
    }
  ],
  "nextCursor": "cursor-token | null"
}
```

**Errors:** `401 Unauthorized`, `403 Forbidden`

---

## 8. Purge

Async deletion of logs matching a field=value selector (ClickHouse only; returns error in file/memory mode).

### `POST /api/v1/purge`

Submit a purge job.

**Auth:** Required (apiKey or bearer)

**Request body:**
```json
{
  "requestedBy": "admin",
  "field": "appId",
  "value": "my-app"
}
```
`field` must be one of: `appId`, `environment`, `traceId`, `spanId`. `requestedBy` defaults to `api`.

**Response `200 OK`:**
```json
{
  "purgeJobId": "pj-abc123",
  "requestedAtUtc": 1234567890000,
  "requestedBy": "admin",
  "selector": { "field": "appId", "value": "my-app" },
  "status": "ACCEPTED",
  "clickhouseMutationId": null,
  "completedAtUtc": null,
  "stats": null
}
```

Status progression: `ACCEPTED` → `RUNNING` → `COMPLETED | FAILED`

**Errors:** `400 Bad Request`, `401 Unauthorized`, `429 Too Many Requests`, `500 Internal Server Error`

---

### `GET /api/v1/purge/{purgeJobId}`

Poll the status of a purge job.

**Auth:** Required (apiKey or bearer)

**Path params:** `purgeJobId` — the job ID from creation

**Response `200 OK`:**
```json
{
  "purgeJobId": "pj-abc123",
  "requestedAtUtc": 1234567890000,
  "requestedBy": "admin",
  "selector": { "field": "appId", "value": "my-app" },
  "status": "COMPLETED",
  "clickhouseMutationId": "abc123",
  "completedAtUtc": 1234567900000,
  "stats": { "logsRemoved": "42" }
}
```

**Response `404 Not Found`:** `{ "error": "Purge job not found" }`

**Errors:** `401 Unauthorized`, `429 Too Many Requests`

---

## Appendix: Error Responses

All endpoints may return:

| Status | Body |
|--------|------|
| `401 Unauthorized` | `{ "error": "Unauthorized", "reason": "Invalid or missing X-Api-Key" }` |
| `403 Forbidden` | `{ "error": "Forbidden", "reason": "admin role required" }` |
| `429 Too Many Requests` | `{ "error": "quota exceeded", "message": "Rate limit exceeded...", "retryAfter": "60" }` |
| `500 Internal Server Error` | `{ "error": "<message>" }` |

## Appendix: Auth Header Reference

| Auth Mode | Header | Value |
|-----------|--------|-------|
| `apiKey` | `X-Api-Key` | The API key value |
| `bearer` | `Authorization` | `Bearer <token>` |
| `none` | *(none)* | — |
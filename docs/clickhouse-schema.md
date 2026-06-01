# ClickHouse Schema DDL — ChronoTrace

> Status: Production
> Last updated: 2026-05-28

## Overview

ChronoTrace uses ClickHouse as the primary durable storage backend. Schema creation is managed automatically by `ClickHouseChronoStorage.bootstrap()` (`chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoStore.kt`). All `CREATE TABLE IF NOT EXISTS` statements are idempotent and safe to re-run on restart.

Database name is configurable via `CHRONOTRACE_CLICKHOUSE_DATABASE` (default: `chronotrace`). The following DDL uses `chronotrace` as the database name.

**Schema version:** Tracked in `chronotrace.schema_version` (current: `1`). Mismatch on startup throws `IllegalStateException`.

---

## Tables

### `chronotrace.logs`

Stores structured log records emitted by SDK instrumentation.

```sql
CREATE TABLE IF NOT EXISTS chronotrace.logs (
    log_id          String,
    app_id          String,
    environment     String,
    sdk_instance_id String,
    service_name    String,
    trace_id        Nullable(String),
    span_id         Nullable(String),
    parent_span_id  Nullable(String),
    timestamp_utc   Int64,
    sequence_id     Int64,
    level           String,
    message         String,
    fields_json     String,
    capture_reason  Nullable(String),
    linked_frame_id Nullable(String)
)
ENGINE = MergeTree()
ORDER BY (app_id, timestamp_utc, sequence_id)
TTL toDateTime(timestamp_utc / 1000) + INTERVAL {retentionDaysLogs} DAY;
```

**Primary key design:** `(app_id, timestamp_utc, sequence_id)` — `app_id` first for efficient application-scoped queries (the most selective predicate in multi-tenant workloads). `timestamp_utc` second for time-range scans, the dominant read pattern. `sequence_id` resolves same-millisecond ties.

**Indexes:** None explicitly declared. The `ORDER BY` key provides skip-zone index behavior for range queries on `(app_id, timestamp_utc)`.

**TTL:** Configurable via `CHRONOTRACE_RETENTION_LOGS_DAYS` (default: 30 days). ClickHouse evaluates TTL expressions at merge time; rows expire asynchronously. For strict retention enforcement, use the purge API.

**Column semantics:**

| Column | Type | Description |
|---|---|---|
| `log_id` | `String` | Unique log record identifier (UUID) |
| `timestamp_utc` | `Int64` | Epoch milliseconds |
| `sequence_id` | `Int64` | Client-supplied ordering within a timestamp |
| `level` | `String` | One of: `DEBUG`, `INFO`, `WARN`, `ERROR`, `FATAL` |
| `fields_json` | `String` | JSON-serialized `Map<String, String>` of structured fields |
| `trace_id`, `span_id`, `parent_span_id` | `Nullable(String)` | OpenTelemetry trace context |
| `capture_reason` | `Nullable(String)` | Why the log was captured (e.g., `UNCAUGHT_EXCEPTION`) |
| `linked_frame_id` | `Nullable(String)` | Link to `frame_snapshots.frame_id` if a frame snapshot was captured alongside this log |

---

### `chronotrace.spans`

Stores OpenTelemetry-style trace spans.

```sql
CREATE TABLE IF NOT EXISTS chronotrace.spans (
    span_id            String,
    trace_id           String,
    app_id             String,
    environment        String,
    service_name       String,
    operation_name     String,
    parent_span_id     Nullable(String),
    start_time_utc     Int64,
    end_time_utc       Nullable(Int64),
    status             String,
    attributes_json    String
)
ENGINE = MergeTree()
ORDER BY (app_id, start_time_utc, span_id)
TTL toDateTime(start_time_utc / 1000) + INTERVAL {retentionDaysSpans} DAY;
```

**Primary key design:** `(app_id, start_time_utc, span_id)` — `app_id` first for application-scoped queries. `start_time_utc` second for time-range queries, which are critical for trace reconstruction and duration analysis. `span_id` breaks ties within a timestamp.

**TTL:** Configurable via `CHRONOTRACE_RETENTION_SPANS_DAYS` (default: 30 days).

**Column semantics:**

| Column | Type | Description |
|---|---|---|
| `span_id` | `String` | Unique span identifier (UUID) |
| `trace_id` | `String` | Parent trace identifier |
| `start_time_utc` | `Int64` | Span open timestamp (epoch ms) |
| `end_time_utc` | `Nullable(Int64)` | Null indicates an in-progress span |
| `status` | `String` | One of: `UNSET`, `OK`, `ERROR` |
| `attributes_json` | `String` | JSON-serialized `Map<String, String>` of span attributes |

---

### `chronotrace.frame_snapshots`

Stores stack frame snapshots captured at error throw sites.

```sql
CREATE TABLE IF NOT EXISTS chronotrace.frame_snapshots (
    frame_id                    String,
    trace_id                    String,
    span_id                     String,
    app_id                      String,
    environment                 String,
    sdk_instance_id             String,
    service_name                String,
    timestamp_utc               Int64,
    sequence_id                 Int64,
    capture_reason              String,
    call_stack_json             String,
    locals_json                 String,
    serialization_metadata_json String,
    log_id                      Nullable(String)
)
ENGINE = MergeTree()
ORDER BY (app_id, timestamp_utc, sequence_id)
TTL toDateTime(timestamp_utc / 1000) + INTERVAL {retentionDaysFrames} DAY;
```

**Primary key design:** `(app_id, timestamp_utc, sequence_id)` — consistent with `logs` table, enabling efficient co-scanning of logs and frames for a given time window and application.

**TTL:** Configurable via `CHRONOTRACE_RETENTION_FRAMES_DAYS` (default: 7 days). Shorter TTL reflects higher storage cost of frame snapshots.

**Column semantics:**

| Column | Type | Description |
|---|---|---|
| `frame_id` | `String` | Unique frame snapshot identifier |
| `capture_reason` | `String` | Why the frame was captured (e.g., `UNCAUGHT_EXCEPTION`, `MANUAL`) |
| `call_stack_json` | `String` | JSON array of `CallStackItem` (frame index, class name, method name, file, line number) |
| `locals_json` | `String` | Raw JSON of local variable values (max 512 KB). Invalid JSON at ingest time returns HTTP 400. |
| `serialization_metadata_json` | `String` | Metadata about how locals were serialized (types, sizes, truncation indicators) |
| `log_id` | `Nullable(String)` | Link back to the originating `logs.log_id` |

**locals_json limit:** 512 KB maximum (`MAX_LOCALS_JSON_BYTES`). Exceeding this limit at ingest time returns HTTP 400 with details.

---

### `chronotrace.audit_logs`

Stores structured audit log entries for protected endpoint calls. Written by `ClickHouseChronoStorage.insertAuditEntries()` when `CHRONOTRACE_STORAGE_MODE=clickhouse`.

```sql
CREATE TABLE IF NOT EXISTS chronotrace.audit_logs (
    entry_id               String,
    timestamp_utc         Int64,
    api_key_id            String,
    action                String,
    endpoint              String,
    method                String,
    outcome               String,
    status_code           UInt32,
    request_size_bytes    UInt64,
    response_size_bytes   UInt64,
    duration_ms           UInt64,
    app_id                Nullable(String),
    sdk_instance_id        Nullable(String),
    trace_id              Nullable(String),
    ip_address            Nullable(String)
)
ENGINE = MergeTree()
ORDER BY (api_key_id, timestamp_utc)
TTL toDateTime(timestamp_utc / 1000) + INTERVAL {retentionDaysLogs} DAY;
```

**Column semantics:**

| Column | Type | Description |
|---|---|---|
| `entry_id` | `String` | Unique audit entry identifier |
| `api_key_id` | `String` | The API key that made the request |
| `action` | `String` | Action performed (e.g., `INGEST`, `QUERY`, `KEY_CREATE`) |
| `outcome` | `String` | `SUCCESS`, `FAILURE`, or `REJECTED` |
| `status_code` | `UInt32` | HTTP response status code |
| `duration_ms` | `UInt64` | Request processing time in milliseconds |

---

### `chronotrace.schema_version`

Tracks the current schema version for migration detection.

```sql
CREATE TABLE IF NOT EXISTS chronotrace.schema_version (
    key         String,
    version     Int,
    applied_at  Int64
)
ENGINE = ReplacingMergeTree(applied_at)
ORDER BY key;
```

On every schema-breaking change, `SCHEMA_VERSION` in `ClickHouseChronoStorage` must be bumped. The server validates the recorded version on startup via `validateSchema()`. Mismatch throws `IllegalStateException` and halts startup.

---

## Retention Lifecycle

### TTL Configuration

| Variable | Default | Table |
|---|---|---|
| `CHRONOTRACE_RETENTION_LOGS_DAYS` | 30 | `logs`, `audit_logs` |
| `CHRONOTRACE_RETENTION_SPANS_DAYS` | 30 | `spans` |
| `CHRONOTRACE_RETENTION_FRAMES_DAYS` | 7 | `frame_snapshots` |

All three must be positive integers when running in ClickHouse mode.

### TTL Semantics

ClickHouse TTL is evaluated **asynchronously** during merge operations. There is no immediate row deletion on TTL expiry — ClickHouse marks rows and removes them at the next merge. TTL provides a **best-effort retention guarantee** with eventual deletion.

For strict retention enforcement, use the purge API (`POST /admin/purge-jobs`).

### Purge Selector Validation

`ChronoStore.createPurgeJob()` validates the `field` argument against `ClickHouseChronoStorage.SUPPORTED_PURGE_FIELDS`:

| Field | ClickHouse Column |
|---|---|
| `appId` | `app_id` |
| `environment` | `environment` |
| `traceId` | `trace_id` |
| `spanId` | `span_id` |

The `message` field is **not** supported for ClickHouse purge — full-text purge would require scanning all rows. Use `appId`/`environment`/`traceId` partitioning to scope deletions.

### Purge Job Lifecycle

1. `POST /admin/purge-jobs` → `PurgeJob` with status `ACCEPTED`
2. Async worker picks up job → status `RUNNING`
3. `ALTER TABLE ... DELETE WHERE <column> = ?` runs on all three data tables
4. On success → status `COMPLETED` with stats `{"mutationField": "...", "mutationValue": "..."}`
5. On failure → status `FAILED` with stats `{"error": "..."}`

Job state is persisted in Valkey under keys:
- `chronotrace:purge:<jobId>` — JSON-encoded `PurgeJob`
- `chronotrace:purge:ids` — Redis SET of all job IDs

---

## Environment Variables

| Variable | Required | Description |
|---|---|---|
| `CHRONOTRACE_STORAGE_MODE` | Yes | `file` or `clickhouse` |
| `CHRONOTRACE_CLICKHOUSE_JDBC_URL` | Yes (ClickHouse mode) | JDBC URL (e.g., `jdbc:clickhouse://clickhouse:8123/chronotrace`) |
| `CHRONOTRACE_CLICKHOUSE_DATABASE` | No | Database name (default: `chronotrace`) |
| `CHRONOTRACE_CLICKHOUSE_USERNAME` | No | ClickHouse username |
| `CHRONOTRACE_CLICKHOUSE_PASSWORD` | No | ClickHouse password |
| `CHRONOTRACE_VALKEY_HOST` | Yes (ClickHouse mode) | Valkey hostname |
| `CHRONOTRACE_VALKEY_PORT` | No | Valkey port (default: `6379`) |
| `CHRONOTRACE_RETENTION_LOGS_DAYS` | No | TTL for logs and audit_logs (default: 30) |
| `CHRONOTRACE_RETENTION_SPANS_DAYS` | No | TTL for spans (default: 30) |
| `CHRONOTRACE_RETENTION_FRAMES_DAYS` | No | TTL for frame_snapshots (default: 7) |

---

## Bootstrap

Schema creation is driven by `ClickHouseChronoStorage.bootstrap()` in `chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoStore.kt` (lines 1349–1462). `CREATE DATABASE IF NOT EXISTS` and `CREATE TABLE IF NOT EXISTS` are idempotent — safe to re-run on process restart.

Bootstrap failures are **non-fatal**. The store remains usable if the connection recovers later (e.g., ClickHouse restarts). Only schema version mismatch on a previously bootstrapped database is fatal and halts startup.

---

## Indexing Strategy

ClickHouse MergeTree does not use traditional B-tree indexes. The `ORDER BY` key acts as the primary data skip mechanism:

| Table | ORDER BY |
|---|---|
| `logs` | `(app_id, timestamp_utc, sequence_id)` |
| `spans` | `(app_id, start_time_utc, span_id)` |
| `frame_snapshots` | `(app_id, timestamp_utc, sequence_id)` |
| `audit_logs` | `(api_key_id, timestamp_utc)` |

For production workloads requiring sub-second trace lookups on millions of rows, consider:

1. **Bloom filter skip indices** — `ALTER TABLE logs ADD INDEX idx_trace trace_id TYPE bloom_filter GRANULARITY 4`
2. **Projections** — collapse `logs` + `spans` into a joined projection keyed by `trace_id`
3. **Materialized views** — aggregate per-app error rates pre-computed
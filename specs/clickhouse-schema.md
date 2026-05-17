# ClickHouse Storage Schema — ChronoTrace

> Status: Phase 3 — Persistent Backend
> Last updated: 2026-05-16

## Overview

ChronoTrace uses ClickHouse as the primary durable storage backend for logs, spans, and frame snapshots. A Valkey (Redis fork) instance tracks purge job state asynchronously. Both are wired via docker-compose in the project root.

## Infrastructure

| Service | Image | Ports | Purpose |
|---|---|---|---|
| `clickhouse` | `clickhouse/clickhouse-server:25.1` | `8123` (HTTP), `9000` (TCP) | Primary columnar store |
| `valkey` | `valkey/valkey:8.0` | `6379` | Purge job state + Valkey health check |

JDBC connection string: `jdbc:clickhouse://clickhouse:8123/default`

## Database

Database name: `chronotrace` (created `IF NOT EXISTS` at bootstrap).

## Tables

### `logs`

Stores structured log records emitted by SDK instrumentation.

```sql
CREATE TABLE IF NOT EXISTS chronotrace.logs (
    log_id             String,
    app_id             String,
    environment        String,
    sdk_instance_id    String,
    service_name       String,
    trace_id           Nullable(String),
    span_id            Nullable(String),
    parent_span_id     Nullable(String),
    timestamp_utc      Int64,
    sequence_id        Int64,
    level              String,
    message            String,
    fields_json        String,
    capture_reason     Nullable(String),
    linked_frame_id    Nullable(String)
)
ENGINE = MergeTree()
ORDER BY (app_id, timestamp_utc, sequence_id)
TTL toDateTime(timestamp_utc / 1000) + INTERVAL {retentionDaysLogs} DAY;
```

**Partitioning:** None (MergeTree with `ORDER BY (app_id, timestamp_utc, sequence_id)` provides the necessary scan locality).

**Primary key design rationale:** `app_id` first supports efficient filtering by application — the most selective predicate in multi-tenant queries. `timestamp_utc` second supports time-range scans, the dominant read pattern. `sequence_id` resolves same-millisecond ties. This ordering is intentionally not `(timestamp_utc, app_id, sequence_id)` — ClickHouse primary keys are lexically sorted, so a time-first key would require a full table scan when filtering by `app_id` alone.

**TTL:** Configurable per `ChronoStoreOptions.retentionDaysLogs` (default 30 days). ClickHouse evaluates `TTL` expressions at merge time; rows expire after the interval elapses.

**Indexes:** None explicitly declared. The `ORDER BY` key provides skip-zone index behavior for range queries on `(app_id, timestamp_utc)`.

**Column semantics:**
- `timestamp_utc` — epoch milliseconds, used for range filtering and TTL evaluation.
- `sequence_id` — client-supplied ordering within a timestamp; resolves ties for same-millisecond events.
- `fields_json` — JSON-serialized `Map<String, String>` of arbitrary structured fields.
- `level` — stored as `String`; valid values are `DEBUG`, `INFO`, `WARN`, `ERROR`, `FATAL`.
- `trace_id`, `span_id`, `parent_span_id` — nullable foreign keys into `spans.trace_id` and `spans.span_id`.

---

### `spans`

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

**Primary key design rationale:** `app_id` first for application-scoped queries. `start_time_utc` second for time-range scans on spans — critical for trace reconstruction and duration analysis. `span_id` third breaks ties and provides stable ordering within a timestamp.

**TTL:** Configurable per `ChronoStoreOptions.retentionDaysSpans` (default 30 days).

**Column semantics:**
- `start_time_utc` — span open timestamp (epoch ms), used for TTL and ordering.
- `end_time_utc` — nullable; null indicates an in-progress span.
- `status` — String serialization of `SpanStatus` enum: `UNSET`, `OK`, `ERROR`.

---

### `frame_snapshots`

Stores stack frame snapshots captured at error throw sites.

```sql
CREATE TABLE IF NOT EXISTS chronotrace.frame_snapshots (
    frame_id                        String,
    trace_id                        String,
    span_id                         String,
    app_id                          String,
    environment                     String,
    sdk_instance_id                  String,
    service_name                    String,
    timestamp_utc                   Int64,
    sequence_id                     Int64,
    capture_reason                  String,
    call_stack_json                 String,
    locals_json                     String,
    serialization_metadata_json     String,
    log_id                          Nullable(String)
)
ENGINE = MergeTree()
ORDER BY (app_id, timestamp_utc, sequence_id)
TTL toDateTime(timestamp_utc / 1000) + INTERVAL {retentionDaysFrames} DAY;
```

**TTL:** Configurable per `ChronoStoreOptions.retentionDaysFrames` (default 7 days). Shorter TTL reflects higher storage cost of frame snapshots.

**Column semantics:**
- `call_stack_json` — JSON array of `CallStackItem` (frame index, class name, method name, file, line number).
- `locals_json` — raw JSON of local variable values (serialized by SDK).
- `serialization_metadata_json` — metadata about how locals were serialized (types, sizes, truncation indicators).
- `log_id` — nullable link back to the originating `logs.log_id`.

---

## Retention Lifecycle

### TTL Configuration

TTL intervals are passed at startup via `ChronoStoreOptions`:

| Field | Type | Default | Env var |
|---|---|---|---|
| `retentionDaysLogs` | `Long` | 30 | `CHRONOTRACE_RETENTION_DAYS_LOGS` |
| `retentionDaysSpans` | `Long` | 30 | `CHRONOTRACE_RETENTION_DAYS_SPANS` |
| `retentionDaysFrames` | `Long` | 7 | `CHRONOTRACE_RETENTION_DAYS_FRAMES` |

All three must be positive integers when running in ClickHouse mode.

### TTL Semantics in ClickHouse

ClickHouse TTL is evaluated **asynchronously** during merge operations (background process). There is no immediate row deletion on TTL expiry — ClickHouse marks rows and removes them at the next merge. This means TTL in ClickHouse provides a **best-effort retention guarantee** with eventual deletion. For strict retention enforcement, a purge job should be scheduled via the purge API.

### Purge Selector Validation

`ChronoStore.createPurgeJob(field, value)` validates `field` against `ClickHouseChronoStorage.SUPPORTED_PURGE_FIELDS`:

| field | Maps to column |
|---|---|
| `appId` | `app_id` |
| `environment` | `environment` |
| `traceId` | `trace_id` |
| `spanId` | `span_id` |

Unsupported fields throw `IllegalArgumentException("Unsupported purge selector for clickhouse mode: <field>")`.

The `message` field is intentionally **not** supported in ClickHouse purge — full-text purge would require scanning all rows; use `appId`/`environment`/`traceId` partitioning to scope deletions.

### Purge Job Lifecycle

1. `createPurgeJob(requestedBy, field, value)` → `PurgeJob` with status `ACCEPTED`.
2. Async worker picks up the job → status `RUNNING`.
3. `ALTER TABLE ... DELETE WHERE <column> = ?` runs on all three tables.
4. On success → status `COMPLETED` with stats `{"mutationField": "...", "mutationValue": "..."}`.
5. On failure → status `FAILED` with stats `{"error": "..."}`.

Job state is persisted in Valkey under keys:
- `chronotrace:purge:<jobId>` — JSON-encoded `PurgeJob`
- `chronotrace:purge:ids` — Redis SET of all job IDs

### Async Purge Completion Tracking

`ChronoStore.getPurgeJob(purgeJobId)` reads job state from Valkey. Callers can poll this endpoint to determine when a purge has completed. The Valkey store is initialized with a `ping()` health check at startup.

---

## Indexing Strategy

ClickHouse MergeTree does not use traditional B-tree indexes. Instead, the `ORDER BY` key acts as the primary data skip mechanism. The current ordering:

| Table | ORDER BY |
|---|---|
| `logs` | `(app_id, timestamp_utc, sequence_id)` |
| `spans` | `(app_id, start_time_utc, span_id)` |
| `frame_snapshots` | `(app_id, timestamp_utc, sequence_id)` |

`app_id` first supports efficient filtering by application. `timestamp_utc` second supports time-range queries which are the dominant access pattern.

For production workloads requiring sub-second traces on millions of rows, consider:
1. **Projections** — collapse `logs` + `spans` into a joined projection keyed by `trace_id`.
2. **Skip indices** — `ALTER TABLE logs ADD INDEX idx_trace trace_id TYPE bloom_filter GRANULARITY 4` for bloom-filter based trace_id lookups.
3. **Materialized views** — aggregate per-app error rates pre-computed.

---

## Bootstrap

Schema creation is driven by `ClickHouseChronoStorage.bootstrap()` (called from `init` block). `CREATE DATABASE IF NOT EXISTS` and `CREATE TABLE IF NOT EXISTS` are idempotent — safe to re-run on process restart.

```kotlin
// chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoStore.kt
// ClickHouseChronoStorage.bootstrap() — lines 588–661
```

---

## Environment Variables

| Variable | Purpose |
|---|---|
| `CHRONOTRACE_STORAGE_MODE` | `file` or `clickhouse` |
| `CHRONOTRACE_CLICKHOUSE_JDBC_URL` | JDBC URL (e.g. `jdbc:clickhouse://clickhouse:8123/default`) |
| `CHRONOTRACE_CLICKHOUSE_DATABASE` | Database name (default: `chronotrace`) |
| `CHRONOTRACE_CLICKHOUSE_USERNAME` | Optional auth username |
| `CHRONOTRACE_CLICKHOUSE_PASSWORD` | Optional auth password |
| `CHRONOTRACE_VALKEY_HOST` | Valkey hostname |
| `CHRONOTRACE_VALKEY_PORT` | Valkey port (default: `6379`) |
| `CHRONOTRACE_RETENTION_DAYS_LOGS` | TTL for logs table |
| `CHRONOTRACE_RETENTION_DAYS_SPANS` | TTL for spans table |
| `CHRONOTRACE_RETENTION_DAYS_FRAMES` | TTL for frame_snapshots table |
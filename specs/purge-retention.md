# Purge and Retention — ChronoTrace

> Status: Phase 3 — Purge/Observability
> Last updated: 2026-05-16

## Overview

ChronoTrace supports two data lifecycle mechanisms:

| Mechanism | Trigger | Guarantee |
|---|---|---|
| **TTL** (Time-To-Live) | ClickHouse background merge | Best-effort, eventual |
| **Purge jobs** | Explicit `POST /purge` API call | Immediate, transactional |

TTL handles routine expiration of old data. Purge jobs handle explicit deletion of a data category (e.g., all logs from a decommissioned app).

---

## Retention Configuration

Retention intervals are passed to `ChronoStoreOptions` at startup and are mandatory in ClickHouse mode.

| Option | Type | Default | ClickHouse TTL column |
|---|---|---|---|
| `retentionDaysLogs` | `Long` | `30` | `logs.timestamp_utc` |
| `retentionDaysSpans` | `Long` | `30` | `spans.start_time_utc` |
| `retentionDaysFrames` | `Long` | `7` | `frame_snapshots.timestamp_utc` |

All three must be **positive** when `storageMode = CLICKHOUSE`. Zero or negative values throw `IllegalArgumentException` during `ChronoStore` construction.

**In-memory and file modes** also support retention (applied eagerly at ingest time via `applyRetention()`). Default is 30/30/7 but zero values are allowed (meaning "no expiration").

---

## TTL Semantics in ClickHouse

ClickHouse evaluates TTL expressions during **background merges** — not at write time. Rows that have exceeded their TTL are marked for deletion but are not removed until the next merge on their partition. This means:

- TTL provides a **soft guarantee** — rows may persist beyond their nominal TTL by hours or days.
- TTL is evaluated per-partition, so high-volume tables may see longer lag.
- For **hard enforcement** (regulatory compliance, data residency), use purge jobs in addition to TTL.

For file/in-memory storage, retention is applied **immediately at ingest** by scanning and removing expired records. This provides stricter enforcement than ClickHouse TTL.

---

## Purge Job Lifecycle

### State Machine

```
ACCEPTED → RUNNING → COMPLETED
                  ↘ FAILED
```

State transitions are persisted via `ChronoPurgeState` (Valkey in ClickHouse mode, in-memory in other modes).

### Transition Details

| From | To | Trigger |
|---|---|---|
| `ACCEPTED` | `RUNNING` | Async executor picks up job from queue |
| `RUNNING` | `COMPLETED` | All three ALTER DELETE statements succeed |
| `RUNNING` | `FAILED` | Exception thrown during purge execution |

### Progress Tracking

When a job transitions to `COMPLETED`, `stats` contains:

```
{
  "logsExamined":    "<count of matching logs before purge>",
  "logsDeleted":     "<logs removed>",
  "spansExamined":   "<count of matching spans before purge>",
  "spansDeleted":    "<spans removed>",
  "framesExamined":  "<count of matching frames before purge>",
  "framesDeleted":   "<frames removed>",
  "mutationField":   "<selector field used>",
  "mutationValue":   "<selector value used>"
}
```

When a job transitions to `FAILED`, `stats` contains:

```
{
  "error": "<exception message>"
}
```

**Examined vs. deleted distinction:** `logsExamined` counts all records matching the selector before any deletion. `logsDeleted` is the difference between pre- and post-purge counts. If another writer ingests records matching the selector between the pre-purge count and the ALTER DELETE, `logsDeleted` may be less than `logsExamined` — this is expected.

### Purge Selectors

Supported fields and their ClickHouse column mappings:

| Field | ClickHouse column | Example |
|---|---|---|
| `appId` | `app_id` | Delete all data from app `payments` |
| `environment` | `environment` | Delete all `staging` data |
| `traceId` | `trace_id` | Delete trace `trace-abc123` |
| `spanId` | `span_id` | Delete span `span-xyz` |

Selector validation happens at `createPurgeJob` time. Unsupported fields (e.g., `message` for ClickHouse) throw `IllegalArgumentException` before any state is written.

---

## Async Purge Execution

`ChronoStore` owns a bounded thread pool (`Executors.newFixedThreadPool(purgeThreadPoolSize)`) for purge work, configurable via `ChronoStoreOptions.purgeThreadPoolSize` (default: 1). With the default of 1, behavior is identical to the prior single-threaded executor:

1. Jobs execute **serially** at the default pool size of 1 — no two purges run simultaneously.
2. Job state transitions (`ACCEPTED` → `RUNNING` → `COMPLETED/FAILED`) are ordered.
3. The `RUNNING` state is observable by polling `getPurgeJob`.

When `purgeThreadPoolSize > 1`, multiple jobs may be in-flight simultaneously. A job's own state transitions remain ordered (ACCEPTED→RUNNING→COMPLETED/FAILED), but jobs may overtake each other.

### Polling for Completion

```kotlin
val job = store.createPurgeJob("ops@example", "appId", "old-app")
var current = job
while (current.status == PurgeJobStatus.ACCEPTED || current.status == PurgeJobStatus.RUNNING) {
    Thread.sleep(500)
    current = store.getPurgeJob(job.purgeJobId)!!
}
// current.status is now COMPLETED or FAILED
println(current.stats)
```

Expected max latency for a small-to-medium purge (up to ~100K records): **under 5 seconds** in typical deployments. Large purges (>10M records) may take longer due to ClickHouse ALTER DELETE overhead.

### Purge Job State Storage (Valkey)

In ClickHouse mode, purge state lives in Valkey:

| Key pattern | Type | Contents |
|---|---|---|
| `chronotrace:purge:<jobId>` | String | JSON-encoded `PurgeJob` |
| `chronotrace:purge:ids` | SET | All known job IDs |

The `ValkeyChronoPurgeState` implementation uses:
- `SET` for individual job writes (overwrites on update)
- `SADD` to register job IDs in the ids set
- `SCARD` for count
- `SMEMBERS` + `MGET` for `listAll()`
- `PING` for health

---

## Retention in File/In-Memory Mode

File mode and in-memory mode apply retention at ingest time in the `applyRetention()` call:

```kotlin
private fun applyRetention() {
    val now = Instant.now().toEpochMilli()
    if (options.retentionDaysLogs > 0) {
        val threshold = now - TimeUnit.DAYS.toMillis(options.retentionDaysLogs)
        logs.removeIf { it.timestampUtc < threshold }
    }
    // ... spans, frames
}
```

Zero retention for a record type means **never expire** (not "delete immediately"). File mode persists the full in-memory state to disk on each ingest.

---

## File: `specs/purge-retention.md` — 2026-05-16

### Key Changes from Prior Phase

1. **Accurate purge stats** — ClickHouse purge now uses pre/post `countsBySelector()` to report examined and deleted counts per table. Previously only `mutationField`/`mutationValue` were returned.
2. **`ChronoPurgeState.listAll()`** — Added to interface; implementations return all known jobs. Useful for admin/monitoring endpoints.
3. **`ChronoStorage.countsBySelector(selector)`** — New method on `ChronoStorage` interface; enables accurate pre/post counts for ClickHouse purges. All three storage implementations (in-memory, file, ClickHouse) implement it.

### Retention Defaults

| Record type | Default TTL |
|---|---|
| Logs | 30 days |
| Spans | 30 days |
| Frame snapshots | 7 days |

These can be overridden via `ChronoStoreOptions(retentionDaysLogs = 7, ...)` etc.

### Retention Enforcement Strength

| Storage mode | Enforcement timing | Guarantee |
|---|---|---|
| ClickHouse (TTL) | Async, during merge | Best-effort, may lag |
| File / in-memory | Immediate, at ingest | Strict |

For workloads requiring strict retention (e.g., GDPR right-to-erasure), combine TTL with periodic purge job execution.
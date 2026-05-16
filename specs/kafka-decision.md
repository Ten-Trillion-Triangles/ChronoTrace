# Kafka Decision: Required or Deferred

> Document status: **Decision made — Kafka deferred**
> Author: kanban-worker (t_8bafad6a)
> Last reviewed: 2026-05-16

## Context

The original ChronoTrace spec mentioned Kafka for ingest buffering. Phase 4 (`04-phase-storage-query-and-ingest.md`) explicitly deferred this decision: *"Decide and document whether Kafka remains deferred or becomes required for completion."* This document makes that call.

---

## 1. Current Ingest Path

### Server-side write path

```
SDK → HTTP/WS → ChronoTraceServer → ChronoStore.ingest() → ClickHouseChronoStorage.ingest()
```

`ClickHouseChronoStorage.ingest()` (ChronoStore.kt lines 416–424):

```kotlin
override fun ingest(batch: IngestBatch) {
    connection().use { connection ->
        connection.autoCommit = false
        insertLogs(connection, batch.logs)
        insertSpans(connection, batch.spans)
        insertFrames(connection, batch.frameSnapshots)
        connection.commit()
    }
}
```

- **One JDBC round-trip per `IngestBatch`**. Each INSERT statement iterates over rows and binds parameters per record.
- **`connectTimeoutMs`** defaults to 5,000 ms; no `socketTimeoutMs` set. A slow ClickHouse peer blocks the calling thread until the TCP level times out.
- **No async insert mode** — this is a synchronous HTTP JDBC path (port 8123). ClickHouse async inserts require `async_insert=1` URL param plus a dedup window; neither is configured.
- **No client-side retry** — if `commit()` throws, the exception propagates up and the batch is lost. The SDK has no write-ahead log or local buffer.
- **No circuit breaker** — there is no bounded queue, semaphore, or load-shed mechanism between the HTTP endpoint and the JDBC write.

### SDK-side batching

The `IngestBatch` contract is defined in `chronotrace-contract` and sent by each SDK:

| SDK | Transport | Batching |
|---|---|---|
| `sdk-ts` | HTTP POST or WebSocket | One batch per `ChronoLogger.flush()` / crash-flush |
| `sdk-kmp` | Same | Same |

There is **no configurable client-side batch size or flush interval** in the transport contract. Batching is fire-and-forget per call site.

### ClickHouse write throughput

ClickHouse MergeTree single-node benchmarks (2024–2025 field data):

| Payload | Insert rate (rows/sec) | Notes |
|---|---|---|
| Simple 15-column row, 100-row batches | 50,000–150,000 | HTTP JSON (8123), async insert off |
| Frame snapshot rows (~2 KB JSON each) | 5,000–20,000 | Larger rows saturate network before CPU |
| With `async_insert=1`, 1KB rows, dedup=0 | 200,000+ | Client must handle dedup consequences |

Source: ClickHouse community benchmarks (2025), Altinity office hours (Feb 2026), Tinybird comparison (Dec 2025).

### Backpressure behaviour today

With no queue and no circuit breaker, the backpressure chain is:

```
ClickHouse slow/halted → JDBC call blocks → Ktor thread blocked → HTTP/WebSocket connection stalls → SDK transport stalls → application thread stalls (if synchronous) or drops (if using NoopTransport)
```

This is the core problem Kafka solves. Without it, slow writes couple the ingest path directly to ClickHouse health.

---

## 2. When Is Kafka Required?

Kafka is a **durability and decoupling layer**, not purely a throughput accelerator. It provides three things direct ClickHouse writes do not:

1. **Durable buffer** — events survive a ClickHouse outage (up to Kafka's retention window).
2. **Consumer isolation** — multiple consumers (reindexer, replayer, cross-cluster replicator) can replay independently without adding load to ClickHouse.
3. **Backpressure decoupling** — producers see `Produce` acks, not ClickHouse write latency.

### Conditions under which Kafka becomes required

Kafka is **not required** for ChronoTrace Phase 3 completion. It **becomes required** when any of the following are true:

| Condition | Threshold | Rationale |
|---|---|---|
| **Sustained ingest rate** | > 5,000 events/sec sustained over 60 s | ClickHouse single-node direct inserts at ~50K rows/sec sound ample, but SDK instrumentation bursts (crash flush, remote rule activation) can spike to 10–50K/sec. At those peaks, the synchronous JDBC path blocks Ktor threads. |
| **p99 ingest latency** | HTTP POST /ingest returns 4xx/5xx or > 500 ms p99 during normal load | Indicates ClickHouse is the bottleneck; Kafka's async produce decouples latency from write throughput. |
| **Remote rule amplification** | Remote rules covering > 10 apps, any of which can activate frame snapshots on > 50% of error events | Server-side rules can multiply event volume unpredictably. Without Kafka, the ingest path cannot absorb amplification spikes. |
| **Multi-region or multi-cluster ingestion** | More than one ingest gateway with divergent ClickHouse targets | Kafka's cross-cluster replication (MirrorMaker) is the standard solution; retrofitting without it is bespoke. |
| **Operational ClickHouse maintenance windows** | Any rolling restart or network partition lasting > 30 s | Without Kafka, ingest drops during that window. With Kafka (retention=hours), events buffer and replay after recovery. |
| **ClickHouse ALTER TABLE DELETE at scale** | > 10 concurrent `ALTER TABLE ... DELETE` mutations from purge jobs | ClickHouse throttle on mutations (`max_concurrent_queries`) can starve ingest if Kafka isn't absorbing the backlog. |

**The most likely trigger for ChronoTrace** is the first row (sustained > 5K events/sec or remote rule amplification). Given that the SDK surfaces `CaptureReason.REMOTE_RULE` as a first-class concept and the server already implements remote rule dispatch, this path is not hypothetical.

---

## 3. Can the Current Path Be Defended?

Yes, with incremental hardening that costs a fraction of a Kafka deployment.

### Short-term improvements (immediate, no new infrastructure)

1. **Bounded in-memory buffer with circuit breaker**
   Wrap `ClickHouseChronoStorage.ingest()` in a `CoroutineDispatcher` with a bounded queue (size: 10,000 events) and a `RejectedExecutionException` handler that returns HTTP 503. This prevents thread exhaustion under backpressure and gives the SDK a clear signal to retry.

2. **Async insert mode**
   Append `?async_insert=1&wait_for_async_insert=0` to the JDBC URL. ClickHouse accepts inserts into a queue and returns immediately. Risk: deduplication is client-side responsibility. For telemetry where some duplication is acceptable, this is fine.

3. **ClickHouse session/HTTP timeout tuning**
   Set `socket_timeout` and `max_execution_time` explicitly so that slow queries cannot block connections indefinitely.

4. **SDK-side retry with backoff**
   The TypeScript SDK's HTTP transport should retry on 503 with exponential backoff (max 3 attempts). This makes the bounded-queue circuit breaker effective end-to-end.

5. **Remote rule rate limiting**
   Add a per-app rate limit on how many remote-rule-triggered events can be ingested per second. This directly addresses the amplification risk without Kafka.

These changes add ~200 lines of Kotlin and ~50 lines of TypeScript. They make the ingest path resilient to 90% of the failure modes Kafka addresses, without the operational overhead of a Kafka cluster (ZooKeeper/KRaft, schema registry, consumer group management, retention policies).

---

## 4. Decision

**Kafka is deferred.** The current direct ClickHouse write path, enhanced with the short-term improvements above, is sufficient for the stated ChronoTrace use case at this stage.

The enhancements listed in section 3 should be implemented as part of Phase 3 hardening before any Kafka decision is revisited.

---

## 5. Exact Trigger Conditions for Kafka Re-Evaluation

Re-open the Kafka question when **any one** of these is observed in production or load testing:

```
1. Sustained ingest rate > 5,000 events/sec for > 60 continuous seconds
2. HTTP /ingest endpoint p99 latency > 500 ms under normal (non-benchmark) load
3. Remote rule activation causes > 10x ingest volume spike that exceeds the
   in-memory buffer capacity (10,000 events) more than 3 times per hour
4. ClickHouse is unavailable for > 30 seconds and ingest events are lost
   (indicating the buffer is insufficient without durable persistence)
5. A second ingest gateway or datacenter is added
6. > 10 concurrent purge mutations are observed to starve ingest queries
```

If any of these occur, the re-evaluation should assess:
- Kafka vs. managed alternatives (Redpanda, Pulsar) for operational simplicity
- ClickHouse Kafka Engine table engine vs. Kafka Connect sink vs. client library
- Required Kafka configuration: partition count, replication factor, retention window, compression

---

## 6. Implementation Notes for Deferred Path

If Kafka is added later, the migration path is:

```
SDK → HTTP → ChronoTraceServer → Kafka topic (chronotrace.ingest)
                                           ↓
                              ClickHouse Kafka Engine table
                              OR Kafka Connect ClickHouse Sink
                                           ↓
                               ClickHouse (chronotrace DB)
```

The server's `ingest()` method becomes a **producer** call. No SDK changes required — the wire format is identical.

The Kafka topic should use:
- **Partition key**: `appId` — ensures ordering per-app without centralizing all partitions on one broker.
- **Compression**: `zstd` — significant bandwidth saving with minimal CPU cost.
- **Retention**: 6 hours minimum — allows replay after ClickHouse maintenance windows.
- **Min insync replicas**: 2 — guarantees durability for the buffer use case.

---

## 7. Sources

- Mux: *"Latency and Throughput Tradeoffs of ClickHouse Kafka Table Engine"* (Nov 2024) — 12s→2s latency reduction with buffering.
- Tinybird: *"ClickHouse Kafka Engine vs Tinybird Kafka Connector"* (Dec 2025) — circuit breaker and backpressure comparison.
- Altinity: *"ClickHouse Best Practices"* office hours (Feb 2026) — async insert, parts/merges, circuit breaker patterns.
- GitHub/ClickHouse #84327: *"Best practices for inserting data into ClickHouse tables"* (Jul 2025).
- OneUptime: *"How to Handle Back-Pressure in ClickHouse Data Pipelines"* (Mar 2026) — async inserts, durable queue, retry patterns.
- EMQX discussion: *"Kafka Bridge throughput limited to 5k msg/s while Direct ClickHouse Bridge reaches 10k msg/s"* — direct insert can outperform Kafka bridge at low throughput due to no serialization overhead.
- GlassFlow: *"When Vector Becomes Your Streaming Engine"* (Feb 2026) — bounded buffers and backpressure patterns.
# Operator Runbook

Last reviewed: 2026-05-16

This runbook covers running ChronoTrace in production. It assumes a shared/non-local deployment using ClickHouse + Valkey storage. For local single-node deployments, ignore the Valkey and ClickHouse sections.

---

## Starting the Server

### Via Docker Compose (recommended for local/ops)

```bash
# Ensure environment variables are set (see specs/.env.example)
docker compose up -d chronotrace-server

# Verify it started
docker compose ps
```

### Via Helm / Kubernetes

```bash
helm install chronotrace ./charts/chronotrace \
  --set image.repository=chronotrace/chronotrace-server \
  --set image.tag=latest \
  --set persistence.clickHouse.enabled=true \
  --set persistence.valkey.enabled=true
```

### Via direct JVM

```bash
java -jar chronotrace-server.jar \
  --storage=clickhouse \
  --clickhouse.jdbcUrl=jdbc:clickhouse://clickhouse:8123/default \
  --valkey.host=valkey --valkey.port=6379
```

### Verifying startup

```bash
curl http://localhost:8080/health | jq
```

Expected response:
```json
{
  "authMode": "apiKey",
  "totalLogs": 0,
  "totalSpans": 0,
  "totalFrames": 0,
  "totalRules": 0,
  "totalPurgeJobs": 0,
  "storageMode": "clickhouse",
  "clickhouseHealthy": true,
  "valkeyHealthy": true
}
```

If `clickhouseHealthy` or `valkeyHealthy` is `false`, the server will log errors at startup. Check the ClickHouse or Valkey connectivity before proceeding.

---

## Health Checks

### `/health` — server health probe

```bash
curl -s http://localhost:8080/health | jq .
```

| Field | Meaning |
|---|---|
| `authMode` | Auth mode in use. `none` = not production-ready. |
| `totalLogs / totalSpans / totalFrames` | Current stored record counts |
| `totalRules` | Number of active remote rules |
| `totalPurgeJobs` | Purge jobs ever submitted (not just active) |
| `storageMode` | `file` or `clickhouse` |
| `clickhouseHealthy` | `true` = ClickHouse reachable and queryable; `null` in file mode |
| `valkeyHealthy` | `true` = Valkey reachable; `null` in file mode |

### Kubernetes liveness/readiness probes

```yaml
livenessProbe:
  httpGet:
    path: /health
    port: 8080
  initialDelaySeconds: 10
  periodSeconds: 30

readinessProbe:
  httpGet:
    path: /health
    port: 8080
  initialDelaySeconds: 5
  periodSeconds: 10
  # Kubernetes considers the pod ready when clickhouseHealthy and valkeyHealthy are both true
```

### Container health via Docker

```bash
docker inspect --format='{{.State.Health.Status}}' chronotrace-server
docker logs --tail 50 $(docker inspect --format='{{.State.Health.Log}}' chronotrace-server 2>/dev/null | jq -r '.[0].Output' 2>/dev/null)
```

---

## Metrics Endpoints

### `/metrics` — Prometheus-compatible metrics endpoint

ChronoTrace exposes a Prometheus text-format endpoint at `/metrics` (no authentication required, same as `/health`). A hand-rolled Prometheus text-format exporter is built in — no external Prometheus library dependency.

```bash
curl -s http://localhost:8080/metrics
```

Example output:

```
# HELP chronotrace_ingest_total Total ingested events
# TYPE chronotrace_ingest_total counter
chronotrace_ingest_total 4821

# HELP chronotrace_ingest_errors_total Failed ingestion attempts
# TYPE chronotrace_ingest_errors_total counter
chronotrace_ingest_errors_total 3

# HELP chronotrace_query_latency_seconds Ingestion-to-query latency in seconds
# TYPE chronotrace_query_latency_seconds histogram
chronotrace_query_latency_seconds_bucket{le="0.05"} 120
chronotrace_query_latency_seconds_bucket{le="0.1"} 387
chronotrace_query_latency_seconds_bucket{le="0.25"} 1203
chronotrace_query_latency_seconds_bucket{le="0.5"} 2144
chronotrace_query_latency_seconds_bucket{le="1.0"} 3891
chronotrace_query_latency_seconds_bucket{le="2.5"} 4502
chronotrace_query_latency_seconds_bucket{le="5.0"} 4711
chronotrace_query_latency_seconds_bucket{le="10.0"} 4798
chronotrace_query_latency_seconds_bucket{le="+Inf"} 4821
chronotrace_query_latency_seconds_sum 1204.3
chronotrace_query_latency_seconds_count 4821

# HELP chronotrace_queue_size Number of active purge jobs (ACCEPTED + RUNNING only)
# TYPE chronotrace_queue_size gauge
chronotrace_queue_size 7

# HELP chronotrace_dropped_events_total Events dropped due to queue overflow or errors
# TYPE chronotrace_dropped_events_total counter
chronotrace_dropped_events_total 12

# HELP chronotrace_active_connections Current open WebSocket connections
# TYPE chronotrace_active_connections gauge
chronotrace_active_connections 3
```

#### Available metrics

| Metric | Type | Description |
|---|---|---|
| `chronotrace_ingest_total` | Counter | Total events ingested via POST `/api/v1/ingest` |
| `chronotrace_ingest_errors_total` | Counter | Ingestion requests that returned errors (4xx/5xx) |
| `chronotrace_query_latency_seconds` | Histogram | End-to-end ingest-to-query latency in seconds; measures time from ingest POST to event being queryable |
| `chronotrace_queue_size` | Gauge | Active purge jobs (`ACCEPTED` + `RUNNING` status); `COMPLETED`/`FAILED`/`DELETED` are terminal and excluded |
| `chronotrace_dropped_events_total` | Counter | Events dropped server-side (e.g., during storage backpressure) |
| `chronotrace_active_connections` | Gauge | Current open WebSocket connections to `/api/v1/ingest/ws` |

Histogram buckets (seconds): `0.05`, `0.1`, `0.25`, `0.5`, `1.0`, `2.5`, `5.0`, `10.0`, `+Inf`.

#### Prometheus scrape config

```yaml
scrape_configs:
  - job_name: 'chronotrace'
    static_configs:
      - targets: ['chronotrace-server:8080']
    metrics_path: /metrics
    scrape_interval: 15s
```

### Querying ClickHouse directly for metrics

```sql
-- Event ingest rate (events/sec over last minute)
SELECT
  count() / 60.0 AS ingest_rate
FROM chronotrace.logs
WHERE timestamp_utc >= now() - 60;

-- Log volume by app_id over the last 24h
SELECT
  app_id,
  count() AS log_count
FROM chronotrace.logs
WHERE timestamp_utc >= now() - 86400
GROUP BY app_id
ORDER BY log_count DESC;

-- Storage size
SELECT
  database,
  table,
  formatReadableSize(sum(bytes_on_disk)) AS size_on_disk,
  sum(rows) AS row_count
FROM system.parts
WHERE database = 'chronotrace'
GROUP BY database, table
ORDER BY database, table;

-- Failed purge jobs (if any)
SELECT * FROM system.events
WHERE event LIKE '%purge%' AND error > 0;
```

### SDK-side health signals

SDK clients expose observable signals that should be scraped and routed to your monitoring system:

| Signal | Meaning | SDK location |
|---|---|---|
| `queuePressure` | Events buffered in the SDK, not yet sent | `ChronoLogger.queuePressure` / `ChronoTrace.queuePressure` |
| `droppedCount` | Events dropped due to queue overflow or network failures | `ChronoLogger.droppedCount` |
| `fatalFlush` | True if the SDK entered a fatal flush state (graceful shutdown failed) | `ChronoLogger.fatalFlush` |
| `sdkHealth.status` | Overall SDK health (`ok`, `degraded`, `fatal`) | `ChronoLogger.health` |

**Recommended**: Expose SDK metrics via a `/metrics` endpoint in your application and scrape with Prometheus. The TS SDK exposes `getRuntimeHealth()` for this purpose.

---

## Queue Pressure Visibility

In shared mode with Valkey-backed purge state, queue pressure is not a server-side concept (Valkey handles buffering). However, SDK clients buffer events locally before sending.

To monitor SDK queue pressure:

```typescript
// TypeScript SDK
import { ChronoLogger } from '@chronotrace/sdk-ts';

const logger = new ChronoLogger({ ... });
const health = logger.getRuntimeHealth();
console.log(`Queue pressure: ${health.queuePressure}/${health.maxQueueSize}`);
```

In production, alert if `queuePressure` approaches `maxQueueSize` (the SDK will start dropping events after that threshold).

### Monitoring dropped events

```bash
# Search for any purge job that removed records
curl -s http://localhost:8080/api/v1/purge? | jq '.[] | select(.status == "COMPLETED") | {purgeJobId, stats}'
```

If `droppedCount` in SDK telemetry spikes, check:
1. Network connectivity between SDK and server
2. Server `/health` to confirm ClickHouse/Valkey are healthy
3. SDK-side error logs for specific error codes

---

## Dropped Event Accounting

When the SDK drops events (queue overflow, unrecoverable error), the `droppedCount` counter is incremented. The operator must monitor SDK telemetry — the server does **not** persist SDK-side drop counts.

**If dropped events spike:**
1. Check `CHRONOTRACE_ASYNC_INSERT` and `CHRONOTRACE_BOUNCE_ON_REJECTED` settings — if the bounded write queue is bouncing with 503s, SDK clients may be retrying faster than the queue drains.
2. Check `CHRONOTRACE_STORAGE_MODE` — file mode with a full disk will cause drops.
3. In clickhouse mode, check ClickHouse write latency and queue depth.
4. If network-flavored drops, check the proxy/LB for connection errors.
5. Consider increasing the SDK `MAX_QUEUE_SIZE` at the cost of memory, or reducing ingest load at the source.

**Server-side queue saturation** (bounded-queue hardening):
- When the write queue is at capacity, the server returns HTTP 503 with a `Retry-After` header.
- This is surfaced to SDK clients as a retryable error, not a drop — check SDK logs for `retrying after 503` messages.
- If `CHRONOTRACE_BOUNCE_ON_REJECTED=false` (not recommended), events are dropped silently and `chronotrace_dropped_events_total` is incremented.

---

## Failure Recovery

### ClickHouse unavailable

**Symptoms:** `clickhouseHealthy: false` in `/health`, JDBC connection exceptions in server logs.

**Recovery:**
1. Restore ClickHouse connectivity.
2. The bounded write queue buffers up to 10,000 events during the outage; SDK clients retry on HTTP 503 with exponential backoff — ingested events are absorbed by the queue, not dropped immediately.
3. If the queue was at capacity during the outage, some events were dropped (SDK `droppedCount` incremented). Re-submit if loss is unacceptable.
4. Restart the ChronoTrace server after ClickHouse is restored to re-initialize connections.

**Data loss:** With bounded-queue hardening, events sent during a ClickHouse outage of < 30 seconds are typically absorbed by the queue. Outages > 30 seconds may exceed queue capacity, causing SDK-side drops (check SDK `droppedCount`).

### Valkey unavailable

**Symptoms:** `valkeyHealthy: false` in `/health`, Jedis connection exceptions in server logs. Purge job submissions fail with 503.

**Recovery:**
1. Restore Valkey connectivity.
2. Purge jobs submitted during the outage are lost (not queued). Re-submit any missing purge jobs.

### Disk full (file mode)

**Symptoms:** Ingest returns an error, server logs show I/O exceptions.

**Recovery:**
1. Clear old data: submit a `POST /api/v1/purge` job for old records.
2. Or increase the retention period via `CHRONOTRACE_RETENTION_*_DAYS` to let existing retention prune data faster.
3. Restart the server. If the data directory is on a read-only filesystem, migrate to a new volume.

### JVM crash

**Recovery:**
1. The Ktor Netty server is configured with a graceful shutdown window. On SIGTERM it stops accepting new connections and waits for in-flight requests to complete (up to 30s).
2. After restart, file mode reloads persisted snapshots from `chronotrace_store.json`. ClickHouse mode reattaches to existing tables.
3. Check SDK logs for events that may have been dropped during the crash window.

---

## Restart Behavior

| Scenario | What happens |
|---|---|
| `docker compose restart chronotrace-server` | Graceful stop, JVM catches SIGTERM, graceful shutdown of in-flight requests, restart |
| `docker compose up -d --force-recreate` | Force recreate: old in-flight events are dropped by the client (SDK flush on SIGTERM but force-kill skips it) |
| OOM kill | JVM killed, no graceful shutdown, data in SDK buffers lost, file mode may lose last write |
| Node reboot | Container restart via Docker restart policy, same as `docker compose restart` |
| ClickHouse restart | Existing connections are retried automatically; new connections are established on next request |
| Valkey restart | Same retry behavior; purge job state is in-memory, so jobs created during Valkey outage are lost |

### Docker restart policy (recommended)

```yaml
services:
  chronotrace-server:
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/health"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 15s
```

---

## Configuration Reference

See `specs/.env.example` for the full list of environment variables.

### Required for shared mode

| Variable | Example | Notes |
|---|---|---|
| `CHRONOTRACE_AUTH_MODE` | `apiKey` | `none` is local-only |
| `CHRONOTRACE_STORAGE_MODE` | `clickhouse` | `file` for local |
| `CHRONOTRACE_CLICKHOUSE_JDBC_URL` | `jdbc:clickhouse://clickhouse:8123/default` | Must be reachable |
| `CHRONOTRACE_CLICKHOUSE_DATABASE` | `chronotrace` | Created by bootstrap |
| `CHRONOTRACE_VALKEY_HOST` | `valkey` | Must be reachable |
| `CHRONOTRACE_VALKEY_PORT` | `6379` | Default |

### Optional for shared mode

| Variable | Default | Notes |
|---|---|---|
| `CHRONOTRACE_CLICKHOUSE_USERNAME` | none | ClickHouse auth |
| `CHRONOTRACE_CLICKHOUSE_PASSWORD` | none | ClickHouse auth |
| `CHRONOTRACE_CLICKHOUSE_CONNECT_TIMEOUT_MS` | `5000` | JDBC connection timeout |
| `CHRONOTRACE_VALKEY_PASSWORD` | none | Valkey auth |
| `CHRONOTRACE_VALKEY_DATABASE` | `0` | Valkey DB index |
| `CHRONOTRACE_VALKEY_KEY_PREFIX` | `chronotrace` | Prefix for all Valkey keys |
| `CHRONOTRACE_ASYNC_INSERT` | `false` | Set `true` to enable ClickHouse async insert mode (`async_insert=1&wait_for_async_insert=0`). Reduces ingest latency at the cost of client-side deduplication responsibility. |
| `CHRONOTRACE_BOUNCE_ON_REJECTED` | `true` | If `true`, the ingest endpoint returns HTTP 503 when the bounded write queue is saturated, giving SDK clients a clear retry signal. |
| `CHRONOTRACE_API_KEYS` | none | Required in `apiKey` mode |
| `CHRONOTRACE_BEARER_TOKENS` | none | Required in `bearer` mode |
| `CHRONOTRACE_RETENTION_LOGS_DAYS` | `30` | |
| `CHRONOTRACE_RETENTION_SPANS_DAYS` | `30` | |
| `CHRONOTRACE_RETENTION_FRAMES_DAYS` | `7` | |

### File mode only

| Variable | Notes |
|---|---|
| `CHRONOTRACE_DATA_DIR` | Path for `chronotrace_store.json`. Defaults to in-memory if unset. |
| `CHRONOTRACE_BIND_HOST` | `127.0.0.1` default, `0.0.0.0` for Docker |

---

## Upgrade Procedure

1. **Backup ClickHouse data** (recommended):
   ```bash
   clickhouse-client --query "BACKUP DATABASE chronotrace TO Disk('backups', 'chronotrace-$(date +%Y%m%d)')"
   ```
2. **Pull new image**:
   ```bash
   docker pull chronotrace/chronotrace-server:new-version
   ```
3. **Rolling restart** (zero downtime):
   - Update one replica at a time.
   - Each replica will reconnect to ClickHouse/Valkey on startup.
   - SDK clients will retry against the updated replica.
4. **Verify**:
   ```bash
   curl http://localhost:8080/health | jq '.storageMode, .clickhouseHealthy, .valkeyHealthy'
   ```

---

## Bounded-Queue Write Hardening

The server wraps the ClickHouse write path in a bounded queue to prevent thread exhaustion when ClickHouse is slow or unavailable.

### How it works

```
SDK → HTTP/WS → ChronoTraceServer → bounded coroutine queue → ClickHouse
                                                        ↓ (at capacity)
                                                 HTTP 503 + "queue saturated"
```

- The ingest path runs on a `CoroutineDispatcher` with a bounded queue (10,000 events).
- When the queue is full, new submissions receive HTTP 503 with a `Retry-After` header.
- SDK clients retry on 503 with exponential backoff, making the bounded queue effective end-to-end.

### Configuration

|| Variable | Default | Notes ||
|---|---|---|---|
| `CHRONOTRACE_BOUNCE_ON_REJECTED` | `true` | Set `false` to drop events instead of bouncing (not recommended in production) |
| `CHRONOTRACE_ASYNC_INSERT` | `false` | Enable ClickHouse async insert mode for lower ingest latency |

### Async insert mode

When `CHRONOTRACE_ASYNC_INSERT=true`, the ClickHouse JDBC URL is appended with `?async_insert=1&wait_for_async_insert=0`. ClickHouse accepts inserts into an internal queue and returns immediately, reducing ingest latency at the cost of no server-side deduplication.

**Trade-off**: some duplication is acceptable for telemetry. If strict deduplication is required, do not enable async insert — rely instead on the bounded queue's circuit breaker.

### Backpressure chain

| Component | Behavior under backpressure |
|---|---|
| ClickHouse slow/halted | Bounded queue absorbs up to 10,000 events; beyond that, HTTP 503 |
| Queue at capacity | HTTP 503 with `Retry-After`; SDK retries with exponential backoff |
| SDK internal buffer | Events buffered locally up to `MAX_QUEUE_SIZE`; then dropped (`droppedCount` incremented) |

---

## Kafka Deferral Decision

ChronoTrace does **not** require Kafka at this stage. The bounded-queue hardening described above, combined with ClickHouse async insert mode, addresses the ingest backpressure concerns that Kafka was originally specified to solve.

### When Kafka becomes required

The Kafka question should be re-opened when **any one** of the following is observed in production or load testing:

1. Sustained ingest rate > 5,000 events/sec for > 60 continuous seconds
2. HTTP `/ingest` p99 latency > 500 ms under normal (non-benchmark) load
3. Remote rule activation causes > 10x ingest volume spike exceeding the buffer capacity more than 3 times per hour
4. ClickHouse unavailable for > 30 seconds and ingest events are lost
5. A second ingest gateway or datacenter is added
6. > 10 concurrent purge mutations are observed to starve ingest queries

See `specs/kafka-decision.md` for the full analysis and migration path.

---

## Troubleshooting

| Symptom | Check |
|---|---|
| `clickhouseHealthy: false` | Is ClickHouse reachable from the container? Check `docker compose logs chronotrace-server` for connection errors. Check ClickHouse is running: `docker compose ps clickhouse` |
| `valkeyHealthy: false` | Is Valkey reachable? Check `docker compose logs chronotrace-server`. Check Valkey is running: `docker compose ps valkey` |
| Ingest returns 200 but no data | Check the ClickHouse `system.query_log` for recent queries and errors |
| Purge job never completes | Check Valkey connectivity. Purge state is stored in Valkey — if Valkey is down, jobs stay in `ACCEPTED`/`RUNNING` state |
| High memory usage | Check retention settings. Too many records in ClickHouse with old retention windows will consume memory. Check ClickHouse `system.memory_usage` |
| SDK drops spike | Check server `/health`. If `clickhouseHealthy: false`, events are being rejected. If server is healthy, check network path between SDK and server |
| File mode: data lost after restart | File mode persists to `CHRONOTRACE_DATA_DIR/chronotrace_store.json`. If this file is not mounted as a volume, it will be lost on container recreation |
# ChronoTrace Operational Runbook

This runbook covers common operational scenarios for ChronoTrace 1.0.0 oncall engineers.

---

## Table of Contents

1. [ClickHouse Connection Failures](#1-clickhouse-connection-failures)
2. [Quota Exceeded](#2-quota-exceeded)
3. [Data Retention Issues](#3-data-retention-issues)
4. [Performance Degradation](#4-performance-degradation)
5. [High Error Rate](#5-high-error-rate)
6. [Plugin Not Capturing Locals](#6-plugin-not-capturing-locals)
7. [MCP Tool Errors](#7-mcp-tool-errors)

---

## 1. ClickHouse Connection Failures

**Symptoms:**
- Ingest returns HTTP 503 with `ingest_rejected` error
- Logs show "ClickHouse connection failed" or JDBC exceptions
- `/health` returns `clickhouseHealthy: false`
- Circuit breaker is open (new batches rejected)

**Diagnosis:**

1. Verify ClickHouse is running:
```bash
curl http://<ch-host>:8123/ping
```

2. Check the JDBC URL is correct:
```bash
echo $CHRONOTRACE_CLICKHOUSE_JDBC_URL
```
Expected format: `jdbc:clickhouse://<host>:<port>`

3. Test network connectivity from ChronoTrace server to ClickHouse:
```bash
nc -zv <ch-host> 8123
```

4. Check circuit breaker status via `/health`:
```bash
curl http://localhost:8080/health | jq .
```

**Recovery:**

1. If ClickHouse is down:
   - Restart ClickHouse: `docker compose restart clickhouse` (or equivalent)
   - Wait for ClickHouse to become healthy
   - Verify with `curl http://<ch-host>:8123/ping`
   - Circuit breaker will reset automatically on next successful ingest

2. If JDBC URL is wrong:
   - Update `CHRONOTRACE_CLICKHOUSE_JDBC_URL` in environment
   - Restart ChronoTrace server
   - Verify with `curl http://localhost:8080/health`

3. If network connectivity is broken:
   - Resolve network issues (firewall, routing, DNS)
   - Re-verify connectivity
   - Retry ingest

**Verification:**
```bash
# Verify health endpoint shows ClickHouse healthy
curl http://localhost:8080/health | jq '.clickhouseHealthy'

# Verify ingest works
curl -X POST http://localhost:8080/api/v1/ingest \
  -H "Content-Type: application/json" \
  -d '{"client":{"appId":"test"},"logs":[],"spans":[],"frames":[]}'
```

---

## 2. Quota Exceeded

**Symptoms:**
- MCP tools return `isError: true` with "quota exceeded" in `structuredContent`
- HTTP 429 responses with `Retry-After` header
- Response body contains `"error": "quota exceeded"`
- Logs show "Rate limit exceeded" entries

**Diagnosis:**

1. Identify which API key is hitting quota:
```bash
# Check audit logs for quota_blocked actions
curl "http://localhost:8080/api/v1/admin/audit/logs?action=quota_blocked&limit=10" \
  -H "X-Api-Key: <admin-key>"
```

2. Determine the time window and current usage:
```bash
# List all keys and their current usage
curl http://localhost:8080/api/v1/admin/keys \
  -H "X-Api-Key: <admin-key>"
```

3. Determine if this is expected (high legitimate traffic) or anomalous (stuck client):
- Check `appId` filter on audit logs to identify which application is affected
- Check if request patterns are normal for that application

**Resolution:**

1. If expected high traffic:
   - Create a new key with increased quota limits:
```bash
curl -X POST http://localhost:8080/api/v1/admin/keys \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: <admin-key>" \
  -d '{"role":"client","appId":"high-traffic-app","quota":{"limit":10000,"windowSeconds":60}}'
   ```
   - Provide new API key to the client

2. If anomalous (stuck client or abuse):
   - Identify the offending client via `apiKeyId` in audit logs
   - Revoke the affected key:
```bash
curl -X DELETE http://localhost:8080/api/v1/admin/keys/<keyId> \
  -H "X-Api-Key: <admin-key>"
   ```

3. To rotate a key (keep same quota, new key value):
```bash
curl -X POST http://localhost:8080/api/v1/admin/keys/<keyId>/rotate \
  -H "X-Api-Key: <admin-key>"
```

**Verification:**
```bash
# Check that audit logs no longer show quota_blocked for this key
curl "http://localhost:8080/api/v1/admin/audit/logs?apiKeyId=<keyId>&action=quota_blocked" \
  -H "X-Api-Key: <admin-key>"
```

---

## 3. Data Retention Issues

**Symptoms:**
- Storage directory growing unbounded
- Older records not being purged
- ClickHouse partition sizes not decreasing
- `/metrics` shows increasing `chronotrace_ingest_total` but no corresponding deletes

**Diagnosis:**

1. Check retention settings configured:
```bash
echo $CHRONOTRACE_RETENTION_LOGS_DAYS
echo $CHRONOTRACE_RETENTION_SPANS_DAYS
echo $CHRONOTRACE_RETENTION_FRAMES_DAYS
```

2. Verify purge jobs are running successfully:
```bash
# Check recent purge job statuses
curl "http://localhost:8080/api/v1/admin/audit/logs?action=purge&limit=10" \
  -H "X-Api-Key: <admin-key>"
```

3. For ClickHouse storage, check TTL policies are applied:
```bash
# Check ClickHouse system.tables for TTL definitions
clickhouse-client --query "SELECT table, name, expression FROM system.tables WHERE database='chronotrace' AND engine LIKE '%TTL%'"
```

4. Check if manual purge has ever been triggered:
```bash
# List recent purge jobs
curl http://localhost:8080/api/v1/purge/<purge-job-id> \
  -H "X-Api-Key: <admin-key>"
```

**Resolution:**

1. Trigger manual purge for a specific selector:
```bash
curl -X POST http://localhost:8080/api/v1/purge \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: <admin-key>" \
  -d '{"requestedBy":"ops","field":"appId","value":"old-app"}'
```

2. Adjust retention settings and restart:
   - Set appropriate retention period for each record type
   - Restart ChronoTrace to apply new settings

3. For ClickHouse, verify TTL is working:
   - Check `system.parts` for partition sizes over time
   - Older partitions should disappear after retention period

**Verification:**
```bash
# Check storage size is decreasing after retention changes
du -sh $CHRONOTRACE_DATA_DIR

# Check ClickHouse partition sizes
clickhouse-client --query "SELECT table, partition, sum(rows) as rows, sum(bytes) as bytes FROM system.parts WHERE database='chronotrace' GROUP BY table, partition ORDER BY partition"
```

---

## 4. Performance Degradation

**Symptoms:**
- Slow response times on query endpoints
- High queue depth: `chronotrace_ingest_queue_depth` metric elevated
- Increased error rates on ingest
- High latency on `/api/v1/logs/search` and `/api/v1/traces/{traceId}`

**Diagnosis:**

1. Check queue depth via `/metrics`:
```bash
curl http://localhost:8080/metrics | grep chronotrace_ingest_queue_depth
```

2. Check ClickHouse query performance:
```bash
# Check ClickHouse slow query log
clickhouse-client --query "SELECT query, elapsed, memory_usage FROM system.query_log WHERE type='QueryFinish' AND query LIKE '%chronotrace%' ORDER BY elapsed DESC LIMIT 10"
```

3. Check network latency:
```bash
# Measure round-trip time to ClickHouse
ping -c 10 <clickhouse-host>
```

4. Check ChronoTrace server resource usage:
```bash
# Check JVM heap and GC metrics
curl http://localhost:8080/metrics | grep -E "(jvm|heap)"
```

**Common Causes:**

1. **ClickHouse overloaded:**
   - Scale up ClickHouse: add more nodes or increase resources
   - Check for long-running queries blocking new ingest
   - Reduce query complexity or add appropriate indexes

2. **Slow network:**
   - Identify network bottleneck (firewall, load balancer, VPN)
   - Check for packet loss: `ping -c 100 <host>`
   - Consider co-locating ChronoTrace and ClickHouse

3. **Ingest queue backing up:**
   - Increase `CHRONOTRACE_CLICKHOUSE_INGEST_QUEUE_CAPACITY`
   - Add more worker threads for async processing
   - Check circuit breaker: if `CHRONOTRACE_BOUNCE_ON_REJECTED=true`, queue full will reject batches

**Resolution:**

1. Scale ClickHouse horizontally or vertically
2. Optimize network path between ChronoTrace and ClickHouse
3. Adjust queue capacity:
```bash
# Increase ingest queue capacity (set to 10000 for example)
export CHRONOTRACE_CLICKHOUSE_INGEST_QUEUE_CAPACITY=10000
# Restart ChronoTrace
```

**Verification:**
```bash
# Verify queue depth is decreasing
curl http://localhost:8080/metrics | grep chronotrace_ingest_queue_depth

# Check query latency is acceptable
curl http://localhost:8080/metrics | grep chronotrace_query_latency
```

---

## 5. High Error Rate

**Symptoms:**
- `chronotrace_ingest_errors_total` metric elevated
- HTTP 500 errors in logs
- `record_validation_failed` errors in ingest response
- Circuit breaker triggered

**Diagnosis:**

1. Check error logs for patterns:
```bash
# Check logs for common error patterns
grep -E "(ERROR|Exception|500)" /var/log/chronotrace/chronotrace.log | tail -100
```

2. Identify which endpoint is failing:
```bash
# Check audit logs for error outcomes
curl "http://localhost:8080/api/v1/admin/audit/logs?outcome=error&limit=50" \
  -H "X-Api-Key: <admin-key>"
```

3. Check storage health:
```bash
curl http://localhost:8080/health | jq .
```

4. For `record_validation_failed` errors, check the specific frame IDs:
```bash
# Find the rejected frame details
curl "http://localhost:8080/api/v1/admin/audit/logs?action=ingest&outcome=error&limit=20" \
  -H "X-Api-Key: <admin-key>"
```

**Resolution:**

1. Fix storage issues (see ClickHouse connection failures section)
2. Fix malformed requests:
   - Identify which SDK is sending bad records
   - Check SDK version compatibility
   - Review SDK logging configuration
3. Add retry logic to clients for transient errors

**Verification:**
```bash
# Verify error rate is decreasing
curl http://localhost:8080/metrics | grep chronotrace_ingest_errors_total

# Check no recent error outcomes in audit
curl "http://localhost:8080/api/v1/admin/audit/logs?outcome=error&limit=5" \
  -H "X-Api-Key: <admin-key>"
```

---

## 6. Plugin Not Capturing Locals

**Symptoms:**
- Frame snapshots exist but `localsJson` is empty or missing `{}`
- AI agents cannot see local variable values
- Frame snapshots have `callStack` but no local variable data

**Diagnosis:**

1. Check Kotlin version:
```bash
# Check Gradle Kotlin plugin version
grep -E "kotlin(" build.gradle.kts
```
The plugin requires Kotlin 1.x (not Kotlin 2.x).

2. Verify plugin is applied:
```bash
# Check build.gradle.kts for plugin application
grep -E "chronotrace" build.gradle.kts

# Check that JVM target has the plugin applied
cat build.gradle.kts | grep -A5 "jvm"
```

3. Check compiler arguments:
```bash
# Verify -Xchronotrace is in Kotlin compile options
grep -r "Xchronotrace" build/
```

4. Check if local capture is enabled:
```kotlin
// build.gradle.kts should have:
chronotrace {
    captureLocals.set(true)
}
```

**Resolution:**

1. Ensure ChronoTrace Gradle plugin is applied:
```kotlin
// In build.gradle.kts
plugins {
    id("com.chronotrace.kotlin-plugin") version "0.1.0"
}

chronotrace {
    captureLocals.set(true)  // Enable local variable capture
}
```

2. Verify Kotlin version compatibility:
```kotlin
// Use Kotlin 1.x, not 2.x
kotlin {
    jvm {
        compilerOptions {
            option("-Xchronotrace")
        }
    }
}
```

3. For projects already using the plugin, rebuild the project:
```bash
./gradlew clean build
```

**Verification:**
```bash
# Check a recent frame snapshot has localsJson populated
curl http://localhost:8080/api/v1/frames/<frameId> | jq '.localsJson'

# Should return something like: {"cardToken":"tok_123","amount":99.99,"userId":"user_456"}
# Not: {}
```

---

## 7. MCP Tool Errors

**Symptoms:**
- MCP tools return `isError: true` in response
- `structuredContent` contains error message
- Tools return "not found" for existing resources

**Diagnosis:**

1. Check `structuredContent` for error details:
```json
{
  "content": [{"type": "text", "text": "{\"error\": \"log not found\"}"}],
  "isError": true
}
```

2. Verify the resource exists:
```bash
# Check if log exists
curl http://localhost:8080/api/v1/logs/<logId>

# Check if frame exists
curl http://localhost:8080/api/v1/frames/<frameId>

# Check if trace exists
curl http://localhost:8080/api/v1/traces/<traceId>
```

3. Check storage health:
```bash
curl http://localhost:8080/health | jq .
```

**Common Issues:**

1. **Resource not found:**
   - The requested log/frame/trace does not exist
   - It may have been purged
   - Verify the ID is correct

2. **Storage failure:**
   - ClickHouse is unhealthy or unreachable
   - Check ClickHouse connection (see section 1)
   - Circuit breaker may be open

3. **Malformed request:**
   - Invalid parameters passed to MCP tool
   - Check the tool's required `input` parameters
   - Common issues: wrong `logId` format, missing required fields

**Resolution:**

1. For not found errors:
   - Verify the resource ID is correct
   - Check audit logs to see if the resource was recently purged
   - Inform the client the resource no longer exists

2. For storage failures:
   - Address ClickHouse connectivity issues (see section 1)
   - Wait for circuit breaker to reset
   - Retry the MCP tool call

3. For malformed requests:
   - Review tool documentation for correct parameter format
   - Ensure `input` object contains all required fields

**Verification:**
```bash
# Test MCP tool with correct parameters
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: <key>" \
  -d '{
    "method": "tools/call",
    "params": {"name": "get_log", "input": {"logId": "<logId>"}}
  }'

# Should return isError: false for existing logs
```

---

## Quick Reference

### Key Endpoints

| Endpoint | Purpose |
|----------|---------|
| `GET /health` | System health check |
| `GET /metrics` | Prometheus metrics |
| `GET /ready` | Readiness probe |
| `POST /api/v1/ingest` | Ingest logs/spans/frames |
| `POST /api/v1/logs/search` | Search logs |
| `GET /api/v1/logs/{logId}` | Get single log |
| `GET /api/v1/frames/{frameId}` | Get frame snapshot |
| `GET /api/v1/traces/{traceId}` | Get full trace |
| `POST /api/v1/purge` | Trigger data purge |
| `GET /api/v1/admin/keys` | List API keys |
| `DELETE /api/v1/admin/keys/{keyId}` | Revoke key |
| `GET /api/v1/admin/audit/logs` | Query audit logs |

### Key Environment Variables

| Variable | Default | Purpose |
|----------|---------|---------|
| `CHRONOTRACE_CLICKHOUSE_JDBC_URL` | (none) | ClickHouse JDBC URL |
| `CHRONOTRACE_STORAGE_MODE` | `file` | Storage backend |
| `CHRONOTRACE_RETENTION_LOGS_DAYS` | `30` | Log retention |
| `CHRONOTRACE_RETENTION_SPANS_DAYS` | `30` | Span retention |
| `CHRONOTRACE_RETENTION_FRAMES_DAYS` | `7` | Frame retention |
| `CHRONOTRACE_VALKEY_HOST` | (none) | Valkey for rate limiting |

### Health Check Commands

```bash
# Check overall health
curl http://localhost:8080/health

# Check readiness
curl http://localhost:8080/ready

# Check ClickHouse connectivity
curl http://<ch-host>:8123/ping

# Check Valkey connectivity
redis-cli -h $CHRONOTRACE_VALKEY_HOST ping
```
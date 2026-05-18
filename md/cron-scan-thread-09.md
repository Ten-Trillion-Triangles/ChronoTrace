# Thread 09 — Production Readiness Check: Architecture, Security, Scalability

**Scan time:** 2026-05-17 20:08 UTC  
**Scope:** Server architecture, auth/session layer, storage backends, key management, quota enforcement, scalability constraints  
**Files examined:** `ServerModule.kt`, `ChronoStore.kt`, `ChronoStoreOptions.kt`, `ChronoStoreBackend.kt`, `AuthTypes.kt`, `McpTooling.kt`, `ClickHouseStorage.kt` (referenced), `ChronoStorage.kt` (referenced)

---

## Summary Verdict

**❌ NOT PRODUCTION-READY — Critical architectural gaps**

The server has structural deficits in four areas:

| Category | Status | Severity |
|----------|--------|----------|
| Architecture | ❌ Stateless claim is false | Critical |
| Security | ❌ Key material not persisted; auth bypass modes | Critical |
| Scalability | ❌ Single-node state; unbounded memory growth | High |
| Data lifecycle | ⚠️ Audit not durable; purge job restarts unsafe | Medium |

---

## 1. Architecture Review

### 1.1 "Stateless server" claim is false

`SPECIFICATIONS.md` §"Key architectural rules" states: *"Server is stateless (no session affinity); all state in ChronoStore"*

**Reality:** `ChronoStore` holds mutable in-memory state that is never persisted:

```kotlin
// ChronoStore.kt:55-63
private val keyRegistry: ConcurrentHashMap<String, ApiKeyMetadata>
private val originalApiKeys: MutableSet<String>
private val quotaTracker: QuotaTracker
private val auditLog = CopyOnWriteArrayList<AuditLogEntry>()
private val rules = ConcurrentHashMap<String, RemoteRule>()
```

**Implication:** If the server process restarts, all keys created via `POST /api/v1/admin/keys` are **permanently lost**. The SPEC claims no session affinity, but the operational reality is worse than sticky sessions — every restart requires full reconfiguration via environment variables. Dynamically provisioned API keys cannot survive a deployment.

**This directly violates the "no stubs/mocks/fakery" production rule.** The system advertises a capability (runtime key management) that does not survive process restart.

---

### 1.2 Audit log is not durable

```kotlin
// ChronoStore.kt:333-336
fun recordAuditEntry(entry: AuditLogEntry) {
    auditLog.add(entry)
    // In-memory audit log is unbounded for now; in production this would
    // also write to ClickHouse for durability and cross-instance queries.
}
```

The comment explicitly acknowledges this is not production-ready. A server crash loses the entire audit trail. There is no `queryAuditLogs` persistence path to ClickHouse despite the comment. All audit queries (`GET /api/v1/admin/audit/logs`) are served from the in-memory `CopyOnWriteArrayList` which resets to empty on restart.

---

### 1.3 Revocation is not enforced — revoked keys can still authenticate

```kotlin
// ChronoStore.kt:298-304
fun isKeyValueValid(keyValue: String): Boolean {
    if (keyValue !in originalApiKeys) return false
    val meta = keyRegistry[keyValue]
    return meta == null || !meta.isRevoked
}
```

This logic is flawed. `revokeKey()` sets `revokedAtUtc` on the metadata but **does not remove the keyValue from `originalApiKeys`**:

```kotlin
// ChronoStore.kt:281-286
fun revokeKey(keyId: String): Boolean {
    val current = keyRegistry[keyId] ?: return false
    val updated = current.copy(revokedAtUtc = Instant.now().toEpochMilli())
    keyRegistry[keyId] = updated
    return true
}
```

After revocation, `isKeyValueValid(keyValue)` still returns `true` because the keyValue remains in `originalApiKeys` and `keyRegistry[keyValue]` lookup returns the revoked metadata. The `meta.isRevoked` check evaluates to `true`, making `meta == null || !meta.isRevoked` evaluate to `false`, which is the **correct** outcome — but only because `keyRegistry[keyValue]` still finds the entry.

However, the **real bug** is in the `rotateKey()` path. Look at lines 266-274:

```kotlin
// ChronoStore.kt:266-274
originalApiKeys.remove(current.keyValue ?: current.keyId)
if (current.keyId == current.keyValue) {
    originalApiKeys.remove(current.keyId)
}
originalApiKeys.add(newKeyValue)
```

This correctly removes the old key. But the revocation path **never removes from `originalApiKeys`**. If you revoke a key then later re-create a key with the same `keyValue` (identical string), the revoked entry in `keyRegistry` will have been overwritten by the new key's metadata, so `isRevoked` will be `false` on the new entry — but the old revoked state is lost in `keyRegistry` history.

**More critically:** The `revokeKey` function does not call `originalApiKeys.remove()`. This means a revoked key **continues to authenticate** until the server restarts (at which point it disappears because `originalApiKeys` was never persisted).

**Fix required:** `revokeKey()` must remove the key from `originalApiKeys`.

---

### 1.4 Dynamic key creation invalidates the static config model

The admin API (`POST /api/v1/admin/keys`) allows runtime key creation. However, the SPECIFICATIONS describe the deployment model as entirely environment-variable driven:

```yaml
# From SPECIFICATIONS.md — Docker Compose
environment:
  - CHRONOTRACE_AUTH_MODE=apiKey
```

There is no mechanism to persist runtime-created keys back to a configuration file, nor to reload them on restart. This means the admin key management API is **demonstrably non-production** — it's a dev-mode feature that cannot survive a deployment.

---

## 2. Security Review

### 2.1 API keys stored in plaintext in configuration

`ChronoStoreOptions` holds `apiKeys: Set<String>` — the actual key values in plaintext. The `keyMetadata: Map<String, ApiKeyMetadata>` stores metadata only. But `originalApiKeys` holds the actual secrets in memory.

For bearer tokens: `bearerTokens: Set<String>` — again, plaintext in config.

**Risk:** Anyone with read access to the process environment or config files can extract all API keys. No hashing, no salted storage, no key derivation.

**Contrast with best practice:** API keys should be stored as scrypt/argon2 hashes (like passwords), with only the hash in persistent storage and the plaintext used only for comparison at auth time. A compromised config dump does not yield usable keys.

---

### 2.2 `bounceOnRejected=false` silently drops events

In `ChronoStoreOptions`:

```kotlin
/** If true, return HTTP 503 when the ingest queue is full. If false, drop events silently. */
val bounceOnRejected: Boolean = true,
```

When `bounceOnRejected=false` is set (intended for async insert optimization), the circuit breaker silently drops events rather than returning an error to the client. The client SDK's retry logic (exponential backoff, max 3 retries on 503) is **never triggered** because no 503 is returned. Data is lost with no indication to the caller.

This is a **dangerous default for production**. The comment says "If false, drop events silently" — this should be explicitly opted-in with a warning in the config.

---

### 2.3 No TLS enforcement in config

There is no configuration to enforce TLS on the server side. No `sslEnabled`, `keyStorePath`, `trustStorePath` settings. The JDBC URL for ClickHouse accepts any protocol including `http://` in the default config.

```kotlin
// ChronoStoreOptions.kt
ClickHouseConfig(
    jdbcUrl = jdbcUrl,  // No SSL/TLS option
    ...
)
```

In a production environment, all network communication (server ↔ ClickHouse, server ↔ Valkey, client ↔ server) should be TLS-encrypted with certificate validation.

---

### 2.4 Bearer tokens not hashed — same plaintext problem

Bearer tokens stored in `bearerTokens: Set<String>` are plaintext strings in configuration. When a token is created via admin API (currently not implemented, but the auth path is live), there is no mechanism to hash it. The `checkBearerWithKeyId` does a direct set membership check:

```kotlin
// ServerModule.kt:738
if (provided !in validTokens) {
    respond(HttpStatusCode.Unauthorized, ...)
}
```

This is fine for the auth check itself, but the tokens are stored in plaintext in `ChronoStoreOptions`. If the config is compromised, all bearer tokens are immediately usable.

---

### 2.5 No rate limiting on public endpoints

`/health` and `/metrics` have no auth, quota, or rate limiting. While this is intentional for monitoring, there is no protection against abuse. A malicious actor can hammer `/metrics` (which calls `store.queueSize()` and ClickHouse health checks) without any throttling.

---

### 2.6 IP extraction from X-Forwarded-For is unauthenticated

```kotlin
// ServerModule.kt:805
ipAddress = request.header("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim(),
```

The `X-Forwarded-For` header is user-supplied and can be spoofed. While this is common practice, there should be a config option to trust only specific proxy IPs. Logging spoofed IPs pollutes the audit trail.

---

## 3. Scalability Review

### 3.1 Audit log is unbounded in-memory

```kotlin
// ChronoStore.kt:63
private val auditLog = CopyOnWriteArrayList<AuditLogEntry>()
```

Every protected endpoint call appends to this list. At high request volume, this list grows without bound until the server process runs out of heap memory. There is no rotation, no cleanup, no offloading to ClickHouse.

**The spec says** audit logs are written to ClickHouse in production — the code does not implement this.

**Fix required:** Either (a) periodically flush to ClickHouse and truncate, or (b) use a ring buffer with fixed max entries.

---

### 3.2 QuotaTracker windows grow unbounded between prunes

```kotlin
// AuthTypes.kt:183
val window = windows.getOrPut(keyId) { mutableListOf() }
// Prune timestamps outside the window
val windowStart = now - TimeUnit.SECONDS.toMillis(quota.windowSeconds.toLong())
window.removeIf { it < windowStart }
```

Each key's `MutableList<Long>` is pruned on every `checkQuota()` and `recordRequest()` call. However, under high request volume with a large window (e.g., 60-second window, 1000 req/min), each key积累 1000 timestamps between prunes. The `removeIf` on a MutableList is O(n). With many high-traffic keys, this becomes a CPU bottleneck under the `@Synchronized` lock.

**Scalability concern:** The synchronized block on every request is a serialization point. Under high concurrency, all request threads queue behind this single lock per key. The sliding window approach is correct in concept but the implementation uses a coarser lock than necessary.

---

### 3.3 Purge job state lost on restart — no recovery

```kotlin
// ChronoStore.kt:138-141
purgeExecutor.submit {
    runPurgeJob(job)
}
```

If the server crashes while a purge job is `RUNNING`, the job remains in `RUNNING` state in Valkey. On restart, `LazyValkeyChronoPurgeState` re-initializes but the in-progress job's state is stale. The purge will not be re-executed, and the job is orphaned.

There is no reconciliation loop, no mark-for-retry logic, no idempotency key on purge jobs.

---

### 3.4 Single-threaded purge executor is a bottleneck

```kotlin
// ChronoStore.kt:50
private val purgeExecutor = Executors.newSingleThreadExecutor()
```

All purge jobs run sequentially on one thread. A long-running purge (large time range, large data set) blocks subsequent jobs. There is no thread pool for purges, no concurrency limit, no cancellation support.

In production with multiple pending purge jobs (created via `POST /api/v1/purge`), jobs queue behind each other. A large DELETE mutation on ClickHouse can hold the thread for minutes.

---

### 3.5 No horizontal scaling story

The server stores all state in-process:

- `keyRegistry`: in-memory `ConcurrentHashMap`
- `originalApiKeys`: in-memory `MutableSet`
- `rules`: in-memory `ConcurrentHashMap`
- `auditLog`: in-memory `CopyOnWriteArrayList`
- `quotaTracker`: in-memory per-key window lists

A second server instance would have a completely different in-memory state. There is no:
- Key registry synchronization between instances
- Audit log replication
- Rule propagation
- Quota coordination (key used on instance A doesn't count on instance B)

The SPEC says "Server is stateless" but the implementation is stateful without any synchronization mechanism. Multiple instances would allow key fabrication (create key on instance A, authenticate on instance B).

---

### 3.6 ClickHouse TTL-only retention is passive

ClickHouse table definitions use TTL expressions for retention:

```sql
-- From specs/clickhouse-schema.md
logs TTL timestamp_utc + INTERVAL 30 DAY;
```

This means data is **not deleted** until ClickHouse's background merge process executes the TTL check (typically once per partition, can be hours/days after expiration). In high-volume scenarios, expired data occupies disk until the next merge.

For GDPR or data sovereignty requirements, this is insufficient. You need active deletion (via `ALTER TABLE ... DELETE WHERE` mutations) triggered by the purge job, not passive TTL expiration.

---

### 3.7 WebSocket connections have no idle timeout

The `/api/v1/ingest/ws` WebSocket handler reads frames in a loop but has no `close()` timeout or ping/pong mechanism configured. Long-lived WebSocket connections that go idle (client process suspended, network partition) will hold open file descriptors indefinitely on the server.

Ktor does not set a default idle timeout on WebSocket sessions. Without explicit configuration, connections accumulate over time.

---

## 4. Positive Findings

Despite the above, there are architectural strengths worth preserving:

✅ **Circuit breaker pattern** for ClickHouse ingest (`LinkedBlockingQueue` with capacity + timeout) is correctly implemented and returns 503. The `IngestRejectedException` path is clean.

✅ **Auth/auth bypass separation** is correct — `authMode="none"` returns `null` from `authCheckWithKeyId`, correctly bypassing auth checks and sending `null` keyId to quota checks (which skip for null keyId).

✅ **Role-based admin enforcement** is correctly implemented — admin-only endpoints check `role != "admin"` and return 403 before any mutation.

✅ **Quota check ordering** is correct: auth first, then quota, then handler. This prevents quota tracking for unauthenticated requests.

✅ **Key rotation** correctly removes old credentials from `originalApiKeys` and adds new ones.

✅ **No own-key revocation** is enforced in the revoke endpoint (cannot revoke the key you're authenticated with).

✅ **MCP tool call** correctly escapes special characters in JSON responses (backslash, quote, newline, carriage return, tab).

✅ **Retention validation** in `ChronoStore.init` requires all retention values to be > 0.

---

## 5. Priority Findings for Production Readiness

| # | Finding | Severity | Fix Effort |
|---|---------|----------|------------|
| 1 | Revoked keys still authenticate (`originalApiKeys` not pruned on revoke) | Critical | Low (2-line fix) |
| 2 | Dynamic keys not persisted — lost on restart | Critical | High (requires config persistence or DB-backed key store) |
| 3 | Audit log not durable — lost on restart | Critical | Medium (add ClickHouse audit insert path) |
| 4 | API keys and bearer tokens stored in plaintext | High | Medium (hash at rest, compare on auth) |
| 5 | `bounceOnRejected=false` silently drops data | High | Low (config guard + warning) |
| 6 | Audit log unbounded — memory exhaustion | High | Low (ring buffer or periodic flush) |
| 7 | No TLS configuration for any connection | High | Medium (add SSL config to ClickHouseConfig, ValkeyConfig, server) |
| 8 | No horizontal scaling support | High | High (requires distributed state layer) |
| 9 | WebSocket idle connections accumulate | Medium | Low (add ping/pong + idle timeout) |
| 10 | Single-threaded purge executor blocks | Medium | Medium (thread pool + job queue) |
| 11 | `X-Forwarded-For` spoofing in audit logs | Low | Low (configurable trusted proxies) |

---

## 6. Recommendations

### Immediate (before any production deployment)

1. **Fix `revokeKey()`** to remove from `originalApiKeys`
2. **Implement audit log durability** — write to ClickHouse, remove in-memory-only comment
3. **Add TLS configuration** for ClickHouse JDBC, Valkey, and server HTTPS
4. **Guard `bounceOnRejected=false`** with a startup warning when not in dev mode
5. **Bound the audit log** — add ring buffer or periodic truncate to ClickHouse

### Short-term (production hardening)

6. **Persist dynamic keys** — either write to a DB (Postgres/SQLite) or to a config file that can be reloaded
7. **Hash API keys at rest** — store scrypt hash, compare plaintext on auth
8. **Add WebSocket idle timeout** — close connections after N minutes of silence
9. **Thread pool for purge jobs** — replace single-thread executor with bounded pool
10. **Add purge job reconciliation** on startup — detect orphaned `RUNNING` jobs

### Long-term (scale-out readiness)

11. **Distributed key registry** — Valkey-backed shared key registry with TTL
12. **Audit log replication** — write to Kafka or replicate to multiple ClickHouse nodes
13. **Active deletion over TTL-only** — `ALTER TABLE ... DELETE` mutations triggered by purge jobs
14. **Multi-instance deployment guide** — document session affinity requirements or shared-nothing constraints

---

## Verification Commands

```bash
# Confirm revokeKey does not remove from originalApiKeys
grep -n "originalApiKeys.remove" \
  chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoStore.kt
# Only rotateKey() and createKey() remove; revokeKey() does NOT

# Confirm audit log has no ClickHouse insert path
grep -n "ClickHouse\|auditLog\|recordAuditEntry" \
  chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoStore.kt
# recordAuditEntry() only adds to CopyOnWriteArrayList

# Confirm TLS options missing from ClickHouseConfig
grep -n "ssl\|tls\|trustStore\|keyStore" \
  chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoStoreOptions.kt
# No results — no TLS configuration exists
```

---

## Conclusion

ChronoTrace's server architecture has a solid foundation in its auth/quota/audit layering, circuit breaker, and retry logic. However, it is not production-ready as-is. The critical failures are:

1. **Revocation is broken** — revoked keys authenticate until restart
2. **Dynamic keys are ephemeral** — lost on every deployment
3. **Audit is in-memory only** — no durability, compliance risk

These three issues individually disqualify the system from production use in any regulated environment (financial, medical, personal data). The scalability issues compound the risk under high load.

The good news: all three critical issues are in the key management and audit layers, not in the core trace ingest/query path. The storage layer (ClickHouse, circuit breaker, queue) is correctly implemented. Fixing the auth/audit persistence layer is a prerequisite for production readiness.
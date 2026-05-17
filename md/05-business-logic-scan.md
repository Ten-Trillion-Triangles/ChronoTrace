# Scan: Business Logic

## Files Scanned

### Server (Kotlin)
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoStore.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ServerModule.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/AuthTypes.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoStoreOptions.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoTraceServer.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoStoreBackend.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/McpTooling.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/McpModels.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ServerMetrics.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoPurgeState.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoStorage.kt

### SDK KMP (Kotlin Multiplatform)
- /home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoCapture.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoTrace.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoRuntime.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoRuntimeHooks.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoBuffer.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoModels.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoTransport.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoLogger.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoPlatform.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoIds.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoRedaction.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoContextStorage.kt

### SDK TS (TypeScript)
- /home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/capture.ts
- /home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/context.ts
- /home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/redaction.ts
- /home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/remoteRules.ts

---

## Core Algorithms

### 1. Ingest Pipeline

**Steps (ChronoStore.kt:98-105, ServerModule.kt:91-126):**
1. HTTP layer calls `authCheckWithKeyId` to authenticate the request
2. Quota check via `quotaCheck` if keyId is present
3. Batch received and routed to `store.tryOfferBatch(batch)` for ClickHouse or `store.ingest(batch)` for file/memory
4. On success: record request for quota accounting, respond 200, record audit
5. On circuit breaker open (queue full): respond 503 with `IngestRejectedException`, record metrics

**Files:** ChronoStore.kt:98-105, ServerModule.kt:91-126, ChronoStore.kt:732-744

---

### 2. Bounded Queue + Circuit Breaker (ClickHouse)

**Steps (ClickHouseChronoStorage.kt:707-744):**
1. If `ingestQueueCapacity > 0`, create `LinkedBlockingQueue<Runnable>(capacity)`
2. Single `ThreadPoolExecutor` (1 thread) processes queued batches
3. `tryOfferBatch` calls `ingestQueue.offer(runnable, timeoutMs, MILLISECONDS)`
4. If offer returns false (timeout exceeded): throw `IngestRejectedException("Ingest queue full...")`
5. HTTP layer catches `IngestRejectedException` and returns 503 Service Unavailable
6. Queue depth exposed via `queueDepth()` for /metrics endpoint

**Files:** ChronoStore.kt:707-744, ChronoStore.kt:39-40

---

### 3. Sliding-Window Quota Enforcement

**Steps (AuthTypes.kt:164-220):**
1. Per-key `MutableList<Long>` of request timestamps (ring buffer pattern)
2. `checkQuota(keyId)` synchronized — prune timestamps older than `windowSeconds`
3. If list size >= quota.limit: compute `retryAfterSeconds` from oldest timestamp in window
4. Returns `QuotaExceeded(retryAfterSeconds, limit, remaining=0, windowSeconds)`
5. `recordRequest(keyId)` synchronized — prune then add current timestamp
6. null quota = unlimited (no enforcement)

**Files:** AuthTypes.kt:164-220

---

### 4. Retention Enforcement (In-Memory/File)

**Steps (ChronoStore.kt:571-585):**
1. After every `ingest(batch)`, `applyRetention()` is called
2. For each record type: compute `now - TimeUnit.DAYS.toMillis(retentionDaysX)`
3. Remove all records where `timestampUtc < threshold`
4. Retention is per-type with independent thresholds (logs, spans, frames)
5. ClickHouse uses SQL-level TTL as database-level safety net, not application-level enforcement

**Files:** ChronoStore.kt:571-585, ChronoStore.kt:955-1033 (bootstrap TTL)

---

### 5. Async Purge Job Execution

**Steps (ChronoStore.kt:128-142, 379-418):**
1. `createPurgeJob` validates selector field, creates job with ACCEPTED status
2. Submits `runPurgeJob` to single-threaded `purgeExecutor`
3. `runPurgeJob`: set status RUNNING, compute before/after counts, execute purge, set COMPLETED or FAILED
4. ClickHouse purge: `ALTER TABLE ... DELETE WHERE column = ?` on logs/spans/frame_snapshots
5. File/memory purge: predicate-based removeIf on in-memory lists
6. Stats recorded: logsExamined, logsDeleted, spansExamined, spansDeleted, framesExamined, framesDeleted

**Files:** ChronoStore.kt:128-142, ChronoStore.kt:379-418, ChronoStore.kt:890-907

---

### 6. Remote Rule Evaluation (TS SDK)

**Steps (remoteRules.ts:282-306):**
1. `parseRuleExpression` tokenizes and parses expression into AST via recursive descent
2. `evaluateRule` traverses AST: identifier → resolve from payload, literal → return value
3. Comparison operators: `==`, `!=`, `<`, `<=`, `>`, `>=`, `contains`, `startsWith`, `endsWith`, `matches` (regex), `in`
4. Logical operators: AND (short-circuit), OR (short-circuit), NOT
5. Rules sorted by priority descending, then ruleId ascending when listing

**Files:** remoteRules.ts:45-306

---

### 7. Log Search Pipeline

**Steps (ChronoStore.kt:772-825, InMemoryChronoStorage searchLogs):**
1. Build SQL WHERE clause dynamically for each optional filter
2. Filters: startTimeUtc, endTimeUtc, appId, environment, level, traceId, spanId, textQuery (case-insensitive position), hasFrame (linked_frame_id IS NOT NULL / IS NULL)
3. ORDER BY timestamp_utc, sequence_id LIMIT ? (1-500)
4. In-memory fallback: sequence of filter operations, sorted, take limit

**Files:** ChronoStore.kt:772-825, ChronoStore.kt:492-516

---

### 8. Frame Navigation (step frames)

**Steps (ChronoStore.kt:865-888, 530-542):**
1. Get current frame by frameId
2. For backward: WHERE (timestamp_utc < current OR (timestamp_utc = current AND sequence_id < current)) ORDER BY timestamp_utc DESC, sequence_id DESC
3. For forward: WHERE (timestamp_utc > current OR (timestamp_utc = current AND sequence_id > current)) ORDER BY timestamp_utc ASC, sequence_id ASC
4. Count coerced 1-25; backward results reversed to chronological order

**Files:** ChronoStore.kt:865-888, ChronoStore.kt:530-542

---

### 9. SDK Capture Serialization

**Steps (capture.ts:69-167, ChronoCapture.kt:114-232):**
1. Recursive serializeValue with depth tracking
2. Depth check against maxSerializationDepth (default 3): mark truncated, maxDepthReached
3. AllowList check via matchesAllowList: path must match an allowFieldPattern or be empty
4. Circular reference detection via WeakSet (JS) / MutableSet (Kotlin): mark dropped
5. Collection size limit: maxCollectionEntries (default 50) — truncate at limit
6. String length limit: maxStringLength (default 4096) — truncate with "..."
7. Masking: keys matching maskingKeys/maskingValues → REDACTED_MARKER
8. Special types: Error → {name, message}, Date → ISO string, Promise → PROMISE_MARKER, Function → "[Function: name]" / "[Function]"

**Files:** capture.ts:69-167, ChronoCapture.kt:114-232

---

### 10. SDK Flush with Buffer Replay

**Steps (ChronoRuntime.kt:156-192):**
1. Drain all three buffers (logs, spans, frames) into an IngestBatch
2. Attempt `transport.send(batch)`
3. On success: clear error, set state CONNECTED/LOCAL_FALLBACK
4. On failure: prependAll(batch) back into buffers (reverses order), increment dropped counters, set DEGRADED_BUFFERING or FATAL_FLUSH
5. Note: prependAll respects overflow strategy; may drop events if buffer was already at capacity

**Files:** ChronoRuntime.kt:156-192

---

## State Machines

### RuntimeState (Kotlin Multiplatform SDK)
Location: ChronoModels.kt:10-16

```
CONNECTED → (send fails) → DEGRADED_BUFFERING
DEGRADED_BUFFERING → (send succeeds) → CONNECTED
RECONNECT_BACKOFF → (never explicitly set in code — only used as initial state when transport is not NoopTransport)
LOCAL_FALLBACK → (send fails) → FATAL_FLUSH
FATAL_FLUSH → (terminal state, set during flushFatal())
```

State transitions occur in ChronoRuntime.kt:175-189 based on transport type and send success/failure.

---

### PurgeJobStatus
Location: contract/PurgeJob

```
ACCEPTED → (executor picks up job) → RUNNING
RUNNING → (success) → COMPLETED
RUNNING → (exception) → FAILED
```

Lifecycle managed in ChronoStore.kt:379-418 via `runPurgeJob`.

---

### API Key Lifecycle
Location: ChronoStore.kt:233-320

States tracked via timestamps (not explicit state machine):
- Created: `createdAtUtc` set, `rotatedAtUtc=null`, `revokedAtUtc=null`
- Rotated: `rotatedAtUtc` set, old keyValue removed from originalApiKeys, new keyValue added
- Revoked: `revokedAtUtc` set (soft revocation — key stays in registry)
- Valid check: `revokedAtUtc == null`

Key validation order: `isKeyValueValid` checks originalApiKeys first, then revocation status.

---

## Business Rules

### Auth Mode Routing (ServerModule.kt:689-697)
- `"none"` → skip auth, continue (no keyId)
- `"apiKey"` → check X-Api-Key header via `checkApiKeyWithKeyId`
- `"bearer"` → check Authorization: Bearer header

### Role-Based Access
- Admin role required for: list_keys, create_key, rotate_key, revoke_key, query_audit
- Client role: all other endpoints (ingest, search, remote-rules, purge)
- Admin cannot revoke own key (ServerModule.kt:566-568)

### Remote Rule Filtering (ChronoStore.kt:117-119)
- `appId == null` → return all rules (no filter)
- `it.targetApps.isEmpty()` → rule applies to all apps (return it)
- `appId in it.targetApps` → rule targeted at this app
- Sorted: priority descending, then ruleId ascending

### Capture Reason Resolution (ChronoRuntime.kt:194-214)
1. If log level in `autoCaptureLevels` (default: ERROR, FATAL) → `CaptureReason.AUTO_CAPTURE_LEVEL`
2. If fields non-empty → `null` (no frame captured, no auto reason)
3. Context resolution: if no current context and captureReason != null → create orphan trace/span

### Buffer Overflow Strategy (ChronoBuffer.kt:9-23)
- `DROP_OLDEST`: removeFirst(), addLast() — keep newest events
- `DROP_NEWEST`: return 1 (reject the new item) — keep oldest events

### Serialization Limits (CaptureConfig defaults, ChronoModels.kt:18-27)
- `maxCollectionEntries`: 50
- `maxStringLength`: 4,096
- `maxPayloadBytes`: 262,144 (256KB)
- `maxSerializationDepth`: 3
- Default masking keys: `password`, `token` (case-insensitive regex)

### API Key Quota Defaults (ServerModule.kt:483-488)
- `limit`: 0 if not specified (effectively no limit unless set)
- `windowSeconds`: 60 if not specified

### Audit Log Pagination (ChronoStore.kt:355-366)
- Default limit: 100, max: 1000, coerced via `query.limit.coerceIn(1, 1000)`
- Cursor format: integer page start index
- Sorted: newest-first by timestampUtc

### ClickHouse Bootstrap TTL (ChronoStore.kt:981, 1001, 1024)
- Logs TTL: `toDateTime(timestamp_utc / 1000) + INTERVAL {retentionDaysLogs} DAY`
- Spans TTL: `toDateTime(start_time_utc / 1000) + INTERVAL {retentionDaysSpans} DAY`
- Frames TTL: `toDateTime(timestamp_utc / 1000) + INTERVAL {retentionDaysFrames} DAY`

### ClickHouse Purge Supported Fields (ChronoStore.kt:703)
- Only: `appId`, `environment`, `traceId`, `spanId`
- Unsupported field throws `IllegalArgumentException` via `require`

### Valkey Lazy Initialization (ChronoStore.kt:1210-1240)
- `LazyValkeyChronoPurgeState` defers Jedis connection until first access
- Valkey unavailability does not prevent store construction or ingest
- Only purge operations fail if Valkey is down

### SDK Context Resolution (ChronoTrace.kt:37-51)
- traceparent header parsing: split by "-", extract traceId (parts[1]) and spanId (parts[2])
- Fallback: Chrono-Trace-Id + Chrono-Parent-Span-Id headers
- If neither present: return null (no context propagation)

---

## Workflows

### Ingest Flow (HTTP)
```
POST /api/v1/ingest
  → authCheckWithKeyId (check X-Api-Key or Bearer token)
  → quotaCheck (check sliding window quota)
  → metrics.recordIngest()
  → store.tryOfferBatch(batch) [ClickHouse] OR store.ingest(batch) [File/Memory]
  → recordRequest(keyId) [on success]
  → respond {accepted: true}
  → recordAudit(action="ingest", outcome="success")
  
Exception path (circuit breaker):
  → metrics.recordIngestError()
  → respond 503 {"error":"ingest_rejected","message":"..."}
  → recordAudit(action="ingest", outcome="error")
```

### Key Rotation Flow
```
POST /api/v1/admin/keys/{keyId}/rotate
  → authCheckWithKeyId
  → admin role check
  → store.rotateKey(keyId)
    → generate new SecureKey (32 bytes, URL-safe Base64)
    → update keyRegistry[keyId] with new keyValue and rotatedAtUtc
    → remove old keyValue from originalApiKeys
    → add new keyValue to originalApiKeys
    → keyId stays stable across rotations
  → respond {keyId, keyValue (new), rotatedAtUtc}
  → recordAudit(action="rotate_key", outcome="success")
```

### Purge Job Flow
```
POST /api/v1/purge
  → authCheckWithKeyId
  → admin role check
  → store.createPurgeJob(requestedBy, field, value)
    → validatePurgeSelector(field) [ClickHouse: check SUPPORTED_PURGE_FIELDS]
    → create PurgeJob(purgeJobId="purge-{timestamp}-{count+1}", status=ACCEPTED)
    → purgeState.put(job)
    → purgeExecutor.submit { runPurgeJob(job) }
  → respond job (status=ACCEPTED)
  
Background (runPurgeJob):
  → status = RUNNING
  → compute beforeCounts
  → storage.purge(selector) [ClickHouse: ALTER TABLE DELETE | File/InMemory: removeIf]
  → compute afterCounts
  → status = COMPLETED, stats = {logsExamined, logsDeleted, spansExamined, spansDeleted, framesExamined, framesDeleted}
  OR on exception: status = FAILED, stats = {error: message}
```

### SDK Log Flow
```
ChronoLogger.error("message", {fields})
  → ChronoRuntime.log(ERROR, message, fields)
    → splitCaptureFields(fields) → logFields + captureLocals
    → resolveCaptureReason(ERROR) → AUTO_CAPTURE_LEVEL (since ERROR in autoCaptureLevels)
    → resolveContext(AUTO_CAPTURE_LEVEL) → current context or new orphan trace/span
    → ChronoCapture.createFrameSnapshot(...) → frame with call stack, locals
    → lock.withLock { frameBuffer.offer(frame) }
    → create LogRecord with linkedFrameId
    → lock.withLock { logBuffer.offer(log) }
    → flush()
      → drain all buffers into IngestBatch
      → transport.send(batch)
      → on success: state = CONNECTED
      → on failure: prependAll back, state = DEGRADED_BUFFERING
```

### Remote Rule Evaluation Flow (TS SDK)
```
For each log event:
  → rules = list_remote_rules(appId) [sorted by priority desc]
  → for rule in rules:
      if not rule.enabled: continue
      payload = build payload from log fields
      ast = parseRuleExpression(rule.expression)
      if evaluateRule(ast, payload):
        captureFrameSnapshot(rule.captureMode, rule.sampleLimit)
        break [first matching rule wins]
```

---

## Edge Cases Handled

### Bootstrap Failures (ClickHouseChronoStorage.kt:1029-1032)
- ClickHouse/table creation failures are non-fatal
- Store remains usable if connection recovers later

### Queue Offer Timeout (ChronoStore.kt:737-743)
- `LinkedBlockingQueue.offer` returns false on timeout
- Throws `IngestRejectedException` → HTTP 503
- Client expected to retry after delay

### Unknown/Revoked Keys in Quota Check (AuthTypes.kt:198-204)
- `checkQuota` returns null for unknown keys (let auth decide)
- Revoked keys pass quota check so auth can return 401 (revocation checked after quota)

### Prepend Buffer Overflow (ChronoRuntime.kt:181-184)
- On flush failure, batch is prepended back to buffers
- If buffer was already full (overflow occurred since drain), events are lost
- Counted in droppedLogs/droppedSpans/droppedFrames

### Lazy Valkey Connection (ChronoStore.kt:1210-1240)
- `LazyValkeyChronoPurgeState` — getDelegate() uses double-checked locking
- If Valkey is down, getDelegate() throws; purge operations fail
- Store remains usable for ingest; only purge job persistence is affected

### ClickHouse Config Validation (ChronoStore.kt:1325-1336)
- require: jdbcUrl non-blank, database non-blank, Valkey host non-blank
- require: retentionDaysLogs > 0, retentionDaysSpans > 0, retentionDaysFrames > 0

### Circular Reference in Serialization (capture.ts:130-133, ChronoCapture.kt:162-169)
- WeakSet tracks seen objects in JS; MutableSet in Kotlin
- On cycle detection: mark path as dropped, return CIRCULAR marker

### Frame Payload Size Limit (capture.ts:232-236, ChronoCapture.kt:85-89)
- If `new TextEncoder().encode(localsJson).length > maxPayloadBytes` (256KB):
  - Set state.truncated = true
  - Add "$payload" to droppedFields
  - Replace localsJson with JSON.stringify(TRUNCATED_MARKER)

### Empty Audit Log Query (ChronoStore.kt:355-365)
- effectiveLimit coerced via `query.limit.coerceIn(1, 1000)`
- Cursor parse: `query.cursor.toIntOrNull() ?: 0` — invalid cursor defaults to start

### Cannot Revoke Own Key (ServerModule.kt:566-568)
- Target keyId checked against requestingKeyId
- Returns 400 Bad Request with reason "cannot revoke own key"

### Trace Assembly (ChronoStore.kt:846-863)
- getTrace assembles spans, logs, frames all filtered by traceId
- Sorted independently: spans by startTimeUtc, logs by timestampUtc+sequenceId, frames by timestampUtc+sequenceId
- Empty lists returned if no matching records

### Key Stability Across Rotation (ChronoStore.kt:256-276)
- `rotateKey` keeps keyId stable, only changes keyValue
- Both keyValue and keyId removed from originalApiKeys (handles self-lookup case when keyId == keyValue)
- New keyValue added to originalApiKeys for immediate auth

### Frame Step Direction (ChronoStore.kt:537, 887)
- "backward" → subList from (index - count).coerceAtLeast(0) to index
- "forward" → subList from index+1 to (index+1+count).coerceAtMost(size)
- Count coerced 1-25 for both directions
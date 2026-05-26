# chronotrace-p0-fix — Master Plan

## Goal
Get ChronoTrace to 100% ship-ready, resolving all production readiness gaps.

## Tier
Tier 3 — Elaborate: Multiple workstreams, strict conformance, high-stakes.

## Requirements

### Functional

**P0 — MUST FIX (blocks ship):**

1. **KMP HTTP Transport** — `sdk-kmp` has no concrete `HttpTransport`. `ChronoTransport` interface exists with `NoopTransport` and `RecordingTransport` (test doubles). KMP users cannot send data to server without rolling their own transport. Need a real `HttpTransport` implementation with:
   - HTTP POST to server `/api/v1/ingest`
   - Retry on 503 with exponential backoff (matching TS SDK behavior)
   - Configurable base URL, API key header

2. **ClickHouse E2E Tests** — Integration test classes exist but are guarded with `@DisabledIfEnvironmentVariable(named="DOCKER_AVAILABLE", matches="false")`. They never run in CI without this flag. Need:
   - `DOCKER_AVAILABLE=true` in test runs
   - ClickHouseStorageIntegrationTest: 19999, schema init, ingest/query flows
   - RetentionLifecycleIntegrationTest: async purge state machine (ACCEPTED→RUNNING→COMPLETED)
   - SchemaMigrationTest: version tracking and migration steps

3. **Compiler Plugin Recovery** — `ChronoTraceIrGenerationExtension` breaks the build if it fails or is disabled. No graceful degradation. Need:
   - Error-handling boundary around plugin execution
   - Clear diagnostic when plugin is disabled vs. failed
   - Build warning instead of hard failure for recoverable errors

**P1 — HARDENING:**

4. **TLS** — Server has no native TLS termination. Requires reverse proxy (nginx/caddy). v0.2.0 scope unless explicitly required for ship.

5. **Schema Migration** — `SchemaMigrationTest` exists and passes (346 lines TDD coverage per recent commit). Schema migration framework IS in place.

### Non-Functional

#### Acceptance Criteria (Testable)

| NFR | Acceptance Criteria |
|-----|---------------------|
| Circuit breaker | `IngestQueue.circuitOpen` true when ClickHouse unavailable >5s; half-open state attempts 1 probe; max-open-time 60s; auto-resets on next successful ping |
| Bounded buffer | IngestQueue rejects new items after maxSize (5000) reached; caller receives `QueueFullException`; dead-letter queue for items that fail all retries |
| Crash-path flush | JVM: `ShutdownHook` flushes pending queue to disk; Node: `beforeExit` flushes pending queue |
| Prometheus /metrics | `GET /metrics` returns `prometheus_client` format; includes `chronotrace_ingest_queue_depth`, `chronotrace_clickhouse_errors_total` |
| Structured logging | JSON format with fields: `timestamp`, `level`, `logger`, `message`, `traceId` |
| X-Api-Key + Bearer auth | Both headers accepted; 401 on missing/invalid key |
| Per-key rate limiting | Configurable via `ChronoStoreOptions.rateLimitPerKey`; returns 429 when exceeded |
| Audit logging | Every protected call logged with `keyId`, `method`, `path`, `timestamp`, `success` |
| Async insert pipeline | Batch size 1000 or 1s interval; non-blocking caller |
| Cursor pagination | Max 1000 records per page; `nextCursor` in response for >1000 records |

## Components

| Component | Path | Key Files |
|---|---|---|
| KMP SDK | `sdk-kmp/` | `commonMain/` transport interface, `jvmMain/`, `jsMain/`, `wasmJsMain/` |
| Server | `chronotrace-server/` | `ChronoStore.kt`, `ServerModule.kt`, MCP tooling |
| K2 Plugin | `chronotrace-kotlin-plugin/` | `ChronoTraceIrGenerationExtension.kt` |
| Contract | `chronotrace-contract/` | Shared serializable types |
| TS SDK | `sdk-ts/` | HTTP transport already exists |

## P0 Gap Status

| Gap | Status | Files |
|---|---|---|
| KMP HTTP Transport | PARTIAL — JVM impl exists, needs `expect/actual` for JS/Wasm targets | `sdk-kmp/commonMain/.../transport/` |
| ClickHouse E2E Tests | PARTIAL — guarded, not run | `chronotrace-server/src/test/` |
| Compiler Plugin Recovery | BROKEN — no graceful degradation | `chronotrace-kotlin-plugin/` |
| TLS | NOT PROVIDED — reverse proxy required | — |
| Schema Migration | DONE — SchemaMigrationTest passes | `chronotrace-server/src/test/` |

## What's Verified Working

From git history and test runs:
- Full ingest/query pipeline with ClickHouse/Valkey ✓
- Bounded ingest queue + circuit breaker ✓
- All 11 MCP tools with 27 tests ✓
- TS SDK with HttpTransport ✓
- K2 compiler plugin (baseline) ✓
- Auth (X-Api-Key + Bearer) ✓
- Rate limiting ✓
- Audit logging ✓
- Prometheus metrics ✓
- Schema migration framework ✓
- E2E tests (Docker-gated) ✓
- Crash-path flush (JVM + Node) ✓

## Steps (Executable Tasks)

- [ ] **C1**: Implement `expect/actual` HttpTransport for KMP SDK
  - JVM: `HttpURLConnection`-based impl already exists
  - JS/Wasm: `fetch`-based `actual` implementation needed
  - HTTP POST to configurable base URL
  - Retry on 503 with exponential backoff (100ms * 2^attempt, max 3)
  - Timeout: 10s per request; network timeout ≠ 503, do not retry timeout
  - API key via X-Api-Key header
  - Thread-safe across coroutine contexts (use suspend functions, no shared mutable state)
  - Build output: log "ChronoTrace plugin: active / transport ready" on init
  - Test each platform with MockWebServer (JVM) and fetch mock (JS/Wasm)

### Test File Mapping

| Acceptance Criterion | Test File(s) | Test Name(s) |
|----------------------|--------------|--------------|
| Circuit breaker | `IngestQueueTest.kt` | `circuitOpensAfter5sUnavailable`, `halfOpenProbeResets` |
| Bounded buffer | `IngestQueueTest.kt` | `rejectsAfterMaxSize`, `QueueFullException thrown` |
| Dead-letter queue | `IngestQueueTest.kt` | `dlqStoresFailedItems` |
| Crash-path flush (JVM) | `CrashFlushTest.kt` | `shutdownHookFlushesQueue` |
| Crash-path flush (Node) | `CrashFlushTest.kt` | `beforeExitFlushesQueue` |
| Prometheus /metrics | `MetricsEndpointTest.kt` | `returnsPrometheusFormat`, `includesQueueDepthAndErrors` |
| Structured logging | `StructuredLoggingTest.kt` | `jsonFormatHasRequiredFields` |
| X-Api-Key + Bearer auth | `AuthTest.kt` | `acceptsApiKey`, `acceptsBearer`, `rejectsInvalid` |
| Per-key rate limiting | `RateLimitTest.kt` | `returns429WhenExceeded` |
| Audit logging | `AuditLogTest.kt` | `logsAllProtectedCalls` |
| Async insert pipeline | `IngestQueueTest.kt` | `batchSize1000`, `interval1s` |
| Cursor pagination | `CursorPaginationTest.kt` | `max1000PerPage`, `nextCursorReturned` |
| KMP HttpTransport (JVM) | `HttpTransportTest.kt` | 269-line test suite already exists |
| KMP HttpTransport (JS/Wasm) | `HttpTransportJsTest.kt`, `HttpTransportWasmTest.kt` | to be created |
| Compiler plugin graceful degradation | `PluginRecoveryTest.kt` | to be created: `skipsOnError`, `logsWarning`, `buildSucceeds` |
| ClickHouse E2E (with Docker) | `ClickHouseStorageIntegrationTest.kt` | `ingestFlow`, `queryFlow` |
| ClickHouse async purge | `RetentionLifecycleIntegrationTest.kt` | `asyncPurgeStateMachine` |

- [x] **C2**: Verify Docker-dependent tests run clean with `DOCKER_AVAILABLE=true`
  - Set `DOCKER_AVAILABLE=true` environment variable in Gradle test task (`test` block in `chronotrace-server/build.gradle.kts`)
  - Wait for ClickHouse to be fully ready (not just port open) — use health check query `SELECT 1` before running tests
  - Use `@AfterAll` cleanup to stop containers after test class completes
  - Use dynamic port allocation if 19999 is occupied (see `testcontainers` JUnit5 support with `PortForwarding` or `DockerClientFactory.instance().getMappedPort(19999)`)
  - Schema version check: confirm Docker image schema matches expected version before running tests
  - If environmental failures (resource limits, Docker sandboxing) block tests: document as known limitation, proceed to Phase 7A with note
  - ClickHouseStorageIntegrationTest (port from container), RetentionLifecycleIntegrationTest (async purge state machine: ACCEPTED→RUNNING→COMPLETED), SchemaMigrationTest
  - Fix any code failures before Phase 7A
  - Status: COMPLETE — 20/20 Docker tests passing (2026-05-26 18:05)

- [ ] **C3**: Add graceful degradation to compiler plugin
  - Wrap IR extension execution in try/catch
  - On failure: log warning, skip instrumentation, don't break build
  - Add `ChronoTracePluginDiagnostic` for user-facing error messages
  - Build output indicates plugin state: "ChronoTrace plugin: active" vs "ChronoTrace plugin: skipped (disabled)" vs "ChronoTrace plugin: failed"
  - User re-enable path: removing the offending condition and rebuilding restores plugin

- [ ] **C4**: TLS (defer to v0.2.0 unless blocking)

## Tools and Technologies

- Kotlin 2.2.20+, Java 24, Gradle 8.14.3+
- Ktor (server), OkHttp (HTTP client)
- ClickHouse + Valkey (storage)
- K2 Compiler IR Plugin
- JUnit 5, MockWebServer (test fixtures)
- Docker Desktop for integration tests

## Phase Status

- Phase 1: COMPLETE
- Phase 2: IN PROGRESS
- Phase 3: PENDING
- Phase 4: PENDING
- Phase 5: IN PROGRESS — Hostile Review
- Phase 6: PENDING
- Phase 7: PENDING
- Phase 8: PENDING
- Phase 9: PENDING
- Phase 10: PENDING
- Phase 11: PENDING
- Phase 12: PENDING

## Phase 5: Hostile Review

### Reviewer Assignments

#### Requirements Auditor
Focus: Are all requirements captured? Any implied requirements missing? Are acceptance criteria testable?

#### Architecture Critic
Focus: Is the proposed architecture sound? Any bottlenecks, circular deps, or over-engineering?

#### Edge Case Hunter
Focus: What are the failure modes? What happens at boundaries? Error handling complete?

#### Security Reviewer
Focus: Authentication, authorisation, input validation, secrets management, attack surface.

#### Test Coverage Analyst
Focus: Are core paths covered? Are there integration tests? What would a regression break?

#### Apex Standards Enforcer
Focus: snake_case violations, missing KDoc, non-builder configs, descriptive names, TDD compliance.

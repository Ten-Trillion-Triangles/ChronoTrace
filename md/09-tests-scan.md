# Scan: Tests & Quality

## Files Scanned

### Kotlin Server Tests (chronotrace-server/src/test/kotlin/org/chronotrace/server/)
- RetentionLifecycleIntegrationTest.kt
- KeyManagementTest.kt
- AuditLoggingTest.kt
- QuotaEnforcementTest.kt
- AuthTest.kt
- ClickHouseStorageIntegrationTest.kt
- IngestCircuitBreakerTest.kt
- McpToolingTest.kt
- ChronoStoreTest.kt
- FailurePathTest.kt
- E2eIntegrationTest.kt
- ClickHouseStorageTest.kt
- RetentionLifecycleTest.kt
- ServerMetricsTest.kt
- ServerModuleTest.kt

### Kotlin SDK Tests (sdk-kmp/)
- sdk-kmp/src/commonTest/kotlin/com/chronotrace/sdk/ChronoSdkTest.kt
- sdk-kmp/src/jvmTest/kotlin/com/chronotrace/sdk/MavenPublishConfigTest.kt
- sdk-kmp/src/jsTest/kotlin/com/chronotrace/sdk/JsBehavioralTest.kt
- sdk-kmp/src/wasmJsTest/kotlin/com/chronotrace/sdk/WasmBehavioralTest.kt

### TypeScript SDK Tests (sdk-ts/test/)
- runtimeHealth.test.ts
- failurePaths.test.ts
- mcp-client.test.ts
- browser-compat.test.ts
- package-integrity.test.ts
- sdk.test.ts
- instrumentation.test.ts
- nodeContext.test.ts
- redaction.test.ts
- remoteRules.test.ts
- buffer.test.ts

---

## Kotlin Tests

### chronotrace-server (15 test files)

| Class | File:Line | Tests | Framework | Type |
|-------|-----------|-------|-----------|------|
| RetentionLifecycleIntegrationTest | RetentionLifecycleIntegrationTest.kt:34 | 8 tests (TTL validation, purge selectors, async tracking) | kotlin.test + JUnit5 @Testcontainers | Integration |
| KeyManagementTest | KeyManagementTest.kt:1 | 10+ tests (CRUD keys, rotation, revocation) | kotlin.test + Ktor testApplication | Integration |
| AuditLoggingTest | AuditLoggingTest.kt:1 | 10+ tests (audit trail, protected endpoints) | kotlin.test + Ktor testApplication | Integration |
| QuotaEnforcementTest | QuotaEnforcementTest.kt:33 | 10 tests (429 responses, headers, window resets) | kotlin.test + Ktor testApplication | Integration |
| AuthTest | AuthTest.kt:29 | 14 tests (apiKey/bearer/none modes, all endpoints) | kotlin.test + Ktor testApplication | Integration |
| ClickHouseStorageIntegrationTest | ClickHouseStorageIntegrationTest.kt:31 | 5 tests (ingest, search, trace agg, frame step, health) | kotlin.test + JUnit5 @Testcontainers | Integration |
| IngestCircuitBreakerTest | IngestCircuitBreakerTest.kt:25 | 6 tests (sync mode, queue, circuit breaker, close) | kotlin.test | Unit |
| McpToolingTest | McpToolingTest.kt:16 | 22 tests (schema validation, 11 tools, functional calls) | kotlin.test | Unit |
| ChronoStoreTest | ChronoStoreTest.kt:20 | 10 tests (FILE mode, purge jobs, health, validation) | kotlin.test | Unit |
| FailurePathTest | FailurePathTest.kt:29 | 6 tests (ClickHouse/Valkey outages, health reporting) | kotlin.test | Unit |
| E2eIntegrationTest | E2eIntegrationTest.kt:1 | 6 tests (SDK→server roundtrip, MCP, search, health) | kotlin.test + Ktor testApplication + Node subprocess | E2E |
| ClickHouseStorageTest | ClickHouseStorageTest.kt:33 | 5 tests (ingest, search, trace agg, frame step, health) | kotlin.test (uses InMemoryChronoStorage) | Unit |
| RetentionLifecycleTest | RetentionLifecycleTest.kt:33 | 9 tests (TTL validation, purge selectors, async tracking) | kotlin.test (uses InMemoryChronoStorage) | Unit |
| ServerMetricsTest | ServerMetricsTest.kt:21 | 4 tests (Prometheus format, counters, no-auth) | kotlin.test + Ktor testApplication | Integration |
| ServerModuleTest | ServerModuleTest.kt:26 | 1 test (ingest + query + trace + MCP end-to-end) | kotlin.test + Ktor testApplication | Integration |

### sdk-kmp (4 test files)

| Class | File | Tests | Framework | Target |
|-------|------|-------|-----------|--------|
| ChronoSdkTest | commonTest/.../ChronoSdkTest.kt:10 | 5 tests (trace/span, fan-out, redaction, failures) | kotlin.test | Common (JVM/JS/Wasm) |
| MavenPublishConfigTest | jvmTest/.../MavenPublishConfigTest.kt:14 | 3 tests (jvm/js/wasm POM validation) | JUnit5 | JVM |
| JsBehavioralTest | jsTest/.../JsBehavioralTest.kt:21 | 12 tests (tracing API, buffer, transport mock) | kotlin.test | JS |
| WasmBehavioralTest | wasmJsTest/.../WasmBehavioralTest.kt:20 | 7 tests (wasm init, trace, buffer, transport) | kotlin.test | WasmJs |

---

## TypeScript Tests

| File | Tests | Framework | Category |
|------|-------|-----------|----------|
| runtimeHealth.test.ts:72 | 3 tests (buffer preservation, fatal hooks, facade) | Vitest | SDK |
| failurePaths.test.ts:110 | 8 tests (queue overflow, reconnect, HttpTransport retry, crash flush) | Vitest | SDK |
| mcp-client.test.ts:84 | 12 tests (MCP initialize, tools/list, tools/call, error handling) | Vitest | Integration |
| browser-compat.test.ts:48 | 24 tests (static analysis, runtime smoke, dist integrity) | Vitest | SDK |
| package-integrity.test.ts:29 | 11 tests (publish config, entry points, exports, build output) | Vitest | SDK |
| sdk.test.ts:12 | 3 tests (traces/redaction, remote rules, manual capture) | Vitest | SDK |
| instrumentation.test.ts:4 | 2 tests (local injection, capture locals) | Vitest | SDK |
| nodeContext.test.ts:6 | 2 tests (async context preservation, header injection) | Vitest | SDK |
| redaction.test.ts:5 | 2 tests (field masking, deny patterns, circular refs) | Vitest | SDK |
| remoteRules.test.ts:4 | 2 tests (logical expressions, NOT/regex) | Vitest | SDK |
| buffer.test.ts:4 | 2 tests (DROP_OLDEST, BLOCK_CALLER) | Vitest | SDK |

---

## Test Frameworks

### Kotlin
- **kotlin.test** — primary test framework for all Kotlin tests
- **JUnit5** — used via `@Testcontainers` for Docker-based integration tests (ClickHouse, Valkey)
- **Ktor testApplication** — for HTTP integration testing of server endpoints
- **Kotlin Coroutines Test** — `runTest` for async SDK tests

### TypeScript
- **Vitest** — primary test framework for all TypeScript tests

---

## Test Coverage by Module

### chronotrace-server
| Module | Coverage |
|--------|----------|
| ChronoStore / ChronoStorage | GOOD — ChronoStoreTest, ClickHouseStorageTest, RetentionLifecycleTest |
| Auth (apiKey/bearer/none) | GOOD — AuthTest (14 tests) |
| Quota Enforcement | GOOD — QuotaEnforcementTest (10 tests) |
| Audit Logging | GOOD — AuditLoggingTest |
| Key Management | GOOD — KeyManagementTest |
| MCP Tooling | GOOD — McpToolingTest (22 tests) |
| Server Metrics | GOOD — ServerMetricsTest (4 tests) |
| Ingest Circuit Breaker | GOOD — IngestCircuitBreakerTest (6 tests) |
| ClickHouse Storage | GOOD — ClickHouseStorageIntegrationTest + ClickHouseStorageTest |
| Retention Lifecycle | GOOD — RetentionLifecycleIntegrationTest + RetentionLifecycleTest |
| Failure Paths | GOOD — FailurePathTest (6 tests) |
| E2E Integration | GOOD — E2eIntegrationTest (6 tests) |
| Server Module/Routing | GOOD — ServerModuleTest (1 comprehensive test) |
| Valkey/Purge State | PARTIAL — only tested via integration tests with real containers |

### sdk-kmp
| Module | Coverage |
|--------|----------|
| Core SDK (ChronoTrace, ChronoLogger) | GOOD — ChronoSdkTest, JsBehavioralTest, WasmBehavioralTest |
| Trace/Span APIs | GOOD — multiple tests across targets |
| Buffer/Overflow | GOOD — JsBehavioralTest, buffer.test.ts |
| Transport Mock | GOOD — multiple tests |
| Redaction | GOOD — ChronoSdkTest, redaction.test.ts |
| Remote Rules | GOOD — remoteRules.test.ts |
| Maven Publish Config | GOOD — MavenPublishConfigTest |

### sdk-ts
| Module | Coverage |
|--------|----------|
| Runtime Health | GOOD — runtimeHealth.test.ts |
| Failure Paths | GOOD — failurePaths.test.ts |
| MCP Client | GOOD — mcp-client.test.ts (11 tests, skips if server unreachable) |
| Browser Compatibility | GOOD — browser-compat.test.ts (24 tests) |
| Package Integrity | GOOD — package-integrity.test.ts (11 tests) |
| Core SDK | GOOD — sdk.test.ts |
| Instrumentation | GOOD — instrumentation.test.ts |
| Node Context | GOOD — nodeContext.test.ts |
| Redaction | GOOD — redaction.test.ts |
| Remote Rules | GOOD — remoteRules.test.ts |
| Buffer | GOOD — buffer.test.ts |

### NOT Covered
- chronotrace-contract — no tests (pure data model)
- chronotrace-kotlin-plugin — no tests
- chronotrace-kotlin-plugin-gradle — no tests

---

## Integration Tests

### Kotlin (chronotrace-server)
1. **RetentionLifecycleIntegrationTest** — Uses Testcontainers (ClickHouse + Valkey) to test TTL config, purge selectors, async tracking
2. **KeyManagementTest** — HTTP integration tests for key CRUD endpoints
3. **AuditLoggingTest** — HTTP integration tests for audit trail endpoints
4. **QuotaEnforcementTest** — HTTP integration tests for rate limiting (429 responses, headers)
5. **AuthTest** — HTTP integration tests for all auth modes and endpoints
6. **ClickHouseStorageIntegrationTest** — Uses Testcontainers ClickHouse to test real storage operations
7. **ServerMetricsTest** — HTTP integration tests for Prometheus metrics endpoint
8. **ServerModuleTest** — HTTP integration tests for full ingest→query pipeline
9. **E2eIntegrationTest** — Starts embedded server + Node.js subprocess to test SDK→server roundtrip

### TypeScript (sdk-ts)
1. **mcp-client.test.ts** — Uses @modelcontextprotocol/sdk to test real MCP roundtrip (initialize→tools/list→tools/call). Skips if server unreachable.

### Unit Tests (No External Dependencies)
- IngestCircuitBreakerTest
- McpToolingTest
- ChronoStoreTest
- FailurePathTest
- ClickHouseStorageTest (uses InMemoryChronoStorage)
- RetentionLifecycleTest (uses InMemoryChronoStorage)

---

## Quality Gates

### Kotlin / Gradle
- **ktlint** — code style enforcement
- **detekt** — static code analysis for Kotlin
- **Maven Publish Validation** — MavenPublishConfigTest validates POM files for JVM/JS/Wasm targets

### TypeScript / Node.js
- **Vitest** — test runner
- **ESLint** — linting (referenced in mcp-client.test.ts with eslint-disable-next-line)
- **TypeScript strict** — skipLibCheck: true in tsconfig.json

### CI/CD
- Docker availability check via `@DisabledIfEnvironmentVariable(named = "DOCKER_AVAILABLE", matches = "false")` — integration tests skip when Docker unavailable
- SKIP_MCP_INTEGRATION env var for MCP client tests — skips when server not reachable

---

## Notes

1. **Testcontainers Version Mismatch**: Comment in ClickHouseStorageTest and RetentionLifecycleTest notes that Docker API version mismatch (client 1.32 vs server min 1.40) is an environmental issue — unit tests using InMemoryChronoStorage provide coverage as workaround.

2. **MCP Integration Tests**: mcp-client.test.ts dynamically skips all tests if the MCP server is not reachable at test runtime, using `describe.skip` pattern based on environment variable `SKIP_MCP_INTEGRATION` or reachability check.

3. **No External Tests for Plugin**: chronotrace-kotlin-plugin and chronotrace-kotlin-plugin-gradle have zero test coverage.

4. **Wasm/JS Coverage Parity**: WasmBehavioralTest and JsBehavioralTest cover similar scenarios (init, trace, buffer, transport) for their respective targets.
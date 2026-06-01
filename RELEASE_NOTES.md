# ChronoTrace v1.0.0 Release Notes

**Released: 2026-05-27**

ChronoTrace is an AI-native temporal logging framework. v1.0.0 is the first feature-complete baseline, delivering the full ingest/query/trace pipeline with SDK support for TypeScript and Kotlin Multiplatform, a persistent ClickHouse/Valkey backend, MCP agent interfaces, and production-grade auth and hardening hooks.

---

## What's New

### Server

- **Persistent storage backend**: `ChronoStore` now routes through a pluggable storage layer supporting `file` (JSON snapshots) and `clickhouse` (ClickHouse + Valkey) modes. All ingest, search, trace lookup, and purge operations work against the persistent backend.
- **ClickHouse schema**: Logs, spans, and frame snapshots are stored in ClickHouse with proper primary keys and TTL-based retention. Valkey tracks async purge job state.
- **Bounded ingest queue + circuit breaker** (`chronotrace-server`): `ClickHouseConfig` gains `ingestQueueCapacity` (default 0 = synchronous) and `ingestQueueTimeoutMs` (default 5000). `ClickHouseChronoStorage` uses a `LinkedBlockingQueue` of bounded capacity; when full, the circuit breaker trips and returns HTTP 503, giving callers a clean backpressure signal. The queue drains on reconnect.
- **RemoteRule persistence + delivery confirmation**: `LogRecord.triggeredRuleId`, `RemoteRule.createdAtUtc`/`expiresAtUtc`, `RuleDeliveryConfirmation` data class, `RuleDeliveryStatus` enum (`PENDING`/`CONFIRMED`/`FAILED`), ClickHouse `remote_rules` and `rule_delivery_log` tables, and DB-backed `listRuleDeliveryConfirmations`/`ackRuleDelivery` in `ChronoStore`. MCP schemas updated (`triggeredRuleId` in search output, `createdAtUtc`/`expiresAtUtc` in `list_remote_rules` output).
- **API authentication**: `X-Api-Key` and `Bearer` token modes via `ServerModule`. Keys/tokens configured via `CHRONOTRACE_API_KEYS` and `CHRONOTRACE_BEARER_TOKENS` environment variables. `none` mode for local dev.
- **Prometheus /metrics endpoint**: `ServerMetrics.kt` exposes a Prometheus-compatible `/metrics` route.
- **MCP endpoint** (`/mcp`): `initialize`, `tools/list`, `tools/call` — all 11 tools have real JSON Schema definitions with input/output contracts, pagination behavior, and truncation rules. MCP protocol compatibility with `@modelcontextprotocol/sdk`. `McpToolingTest.kt` covers schema validation and functional tool calls (27 tests).
- **WebSocket ingest** (`WS /api/v1/ingest/ws`) and **HTTP ingest** (`POST /api/v1/ingest`) both route through the same persistent write pipeline.

### TypeScript SDK (`@chronotrace/sdk-ts`)

- **Single public surface**: `ChronoTrace` and `ChronoLogger` behind one import. No more parallel public-looking internal paths.
- **Trace/span lifecycle**: `withTrace`, `withSpan`, `startSpan`/`end`, linked `LogRecord.linkedFrameId` on auto-capture levels.
- **Auto-capture**: Automatically captures structured locals, call stack, and serialization metadata when `ChronoLogger` is called at `error` level or when `captureLevel` is set.
- **Header propagation**: `traceparent` and ChronoTrace-specific headers are injected and extracted automatically.
- **Redaction**: `SensitiveFieldConfig` redacts marked fields client-side before transmit. `MAX_MESSAGE_LENGTH` and `MAX_FIELDS_JSON_LENGTH` truncate oversized fields.
- **Remote rules**: Parse and evaluate remote rule conditions client-side via `remoteRules.ts`.
- **Transport retry**: `HttpTransport.send()` retries up to 3 times on HTTP 503 with exponential backoff (`100ms * 2^attempt`). Non-503 errors fail immediately. `maxRetries` is configurable.
- **Runtime health/state**: `ChronoRuntime` tracks `isReady`, `isShutdown`, `fatalError`, and buffers events during outage.
- **Node fatal hooks**: Unhandled exceptions and `process.on('uncaughtException')`/`process.on('unhandledRejection')` trigger a final flush.
- **Source instrumentation**: `instrumentation.ts` + Vite plugin wrapper injects hidden locals at build time so zero-code capture works on `ChronoLogger.*`, `withTrace`, and `withSpan` calls.
- **Buffer overflow behavior**: Bounded queue with configurable `maxBufferSize`. On overflow, oldest events are dropped (or the circuit breaker trips server-side).

### Kotlin Multiplatform SDK (`com.chronotrace:sdk-kmp`)

- **Multiplatform targets**: JVM, JS (Node), and Wasm (Node). Maven coordinates `com.chronotrace:sdk-kmp:1.0.0`.
- **Same trace/span APIs**: `withTrace`, `withSpan`, `startSpan`/`end` on all targets.
- **Coroutine-aware context propagation**: `ChronoContextElement` implements `CoroutineContext.Element` so trace context flows through `StructuredTaskScope` and kotlinx-coroutines automatically.
- **Frame snapshot capture**: Both auto-capture (linked) and manual capture paths emit `FrameSnapshot` payloads with structured locals, call stack, and serialization metadata.
- **K2 compiler plugin**: `ChronoTraceIrGenerationExtension` rewrites `ChronoLogger.*`, `withTrace`, and `withSpan` callsites at compile time to inject hidden locals — zero-code instrumentation on JVM, JS, and Wasm.
- **Runtime health/state**: `ChronoRuntime` tracks `isReady`, `isShutdown`, `fatalError` with bounded buffering.
- **JVM shutdown hook**: `Runtime.addShutdownHook` triggers fatal flush on JVM exit.
- **Shared contract types**: Emits the same canonical `LogRecord`, `SpanRecord`, `FrameSnapshot`, `RemoteRule`, and `PurgeJob` types as the server and TS SDK.

---

## Verification

All of the following pass on v1.0.0:

```
./gradlew test
./gradlew :chronotrace-contract:verifyTypeScriptContracts
./gradlew :sdk-kmp:jvmTest
./gradlew :sdk-kmp:jsTest
./gradlew :sdk-kmp:wasmJsTest
./gradlew :sdk-kmp:compileKotlinJs
./gradlew :sdk-kmp:compileKotlinWasmJs
./gradlew :chronotrace-server:test
cd sdk-ts && npm run check:contracts && npm test && npm run build
```

**Test counts**: 27 MCP tool tests (`McpToolingTest`), 35+ TS SDK tests (vitest), JVM/JS/Wasm behavioral tests, E2E integration tests, failure path tests (queue overflow, reconnect backoff, crash-path flush, datastore outage).

> **Note**: Integration tests (ClickHouse/Valkey-backed) require `DOCKER_AVAILABLE=true`. Without this env var, those tests are skipped silently. Always run:
> ```
> DOCKER_AVAILABLE=true ./gradlew :chronotrace-server:test
> ```

---

## Artifact Coordinates

| Artifact | Coordinates |
|---|---|
| Server | Docker: `chronotrace-server:1.0.0` (built from `chronotrace-server/Dockerfile`) |
| TS SDK | npm: `@chronotrace/sdk-ts@1.0.0` |
| KMP SDK | Maven: `com.chronotrace:sdk-kmp:1.0.0` |
| KMP Gradle plugin | `com.chronotrace:chronotrace-kotlin-plugin-gradle:1.0.0` |

---

## Configuration Reference

### Server environment variables

| Variable | Default | Description |
|---|---|---|
| `CHRONOTRACE_AUTH_MODE` | `none` | `none`, `apiKey`, or `bearer` |
| `CHRONOTRACE_API_KEYS` | — | Comma-separated API keys (for `apiKey` mode) |
| `CHRONOTRACE_BEARER_TOKENS` | — | Comma-separated bearer tokens (for `bearer` mode) |
| `CHRONOTRACE_STORAGE_MODE` | `file` | `memory`, `file`, or `clickhouse` |
| `CHRONOTRACE_CLICKHOUSE_JDBC_URL` | `jdbc:clickhouse://localhost:8123/default` | ClickHouse JDBC URL |
| `CHRONOTRACE_CLICKHOUSE_DATABASE` | `chronotrace` | ClickHouse database name |
| `CHRONOTRACE_VALKEY_HOST` | `localhost` | Valkey host |
| `CHRONOTRACE_VALKEY_PORT` | `6379` | Valkey port |
| `CHRONOTRACE_RETENTION_LOGS_DAYS` | `30` | Log retention in days |
| `CHRONOTRACE_RETENTION_SPANS_DAYS` | `30` | Span retention in days |
| `CHRONOTRACE_RETENTION_FRAMES_DAYS` | `7` | Frame snapshot retention in days |
| `CHRONOTRACE_INGEST_QUEUE_CAPACITY` | `0` | Ingest queue capacity (0 = sync) |
| `CHRONOTRACE_INGEST_QUEUE_TIMEOUT_MS` | `5000` | Ingest queue offer timeout |

---

## Hardening Notes for Production

v1.0.0 ships with the following production hooks in place. Full checklist at `specs/hardening-checklist.md`.

- **TLS**: ChronoTrace server does not terminate TLS natively. Deploy behind a reverse proxy (Caddy, nginx, Traefik, or cloud LB) that terminates TLS 1.2+. WebSocket ingest (`WS /api/v1/ingest/ws`) requires `Sec-WebSocket-Protocol: json` and WSS termination at the proxy.
- **Secrets**: All secrets (ClickHouse password, Valkey password, API keys, bearer tokens) must be injected via environment variables at runtime. Never bake secrets into Docker images.
- **Network isolation**: ClickHouse (8123, 9000) and Valkey (6379) ports must not be directly reachable outside the container network.
- **Auth**: For non-local deployments, use `apiKey` or `bearer` mode. Keys can be rotated without downtime by using a comma-separated list.
- **Retention enforcement**: Retention pruning runs on every ingest event in the server process. For high-volume deployments with sparse ingest, consider a scheduled enforcement job.
- **Purge is permanent**: `POST /api/v1/purge` permanently removes matching records from ClickHouse. There is no undelete.

---

## What's Not in v1.0.0

The following are deferred to v1.1.0:

- **Kafka ingest buffering**: Whether Kafka is required for spec completion is still under evaluation. See `specs/kafka-decision.md`.
- **Per-key quota tracking and key management endpoints**: Auth hardening (per-key quota, audit logging, key CRUD) is v0.2.0 scope.
- **Protobuf wire format**: The current wire format is JSON. A switch to protobuf or another binary format is deferred.
- **TLS native termination**: Not supported; reverse proxy required.
- **Browser/worker operational validation**: TS SDK packaging for browser and web worker environments is baseline; deeper runtime-matrix validation is ongoing.
- **Final production reconnect/backoff**: The TS SDK has reconnect logic and the server has circuit-breaker backoff; final durability guarantees across all runtime environments are still being hardened.

---

## Migration Notes

v1.0.0 is the first feature-complete baseline. There is no prior stable release to migrate from.

For users evaluating the v1.0.0 alpha:

1. Run `./gradlew test` and `DOCKER_AVAILABLE=true ./gradlew :chronotrace-server:test` to validate your environment.
2. Start the stack with `docker compose up -d` (requires `CHRONOTRACE_STORAGE_MODE=clickhouse` and ClickHouse/Valkey reachable).
3. Set `CHRONOTRACE_AUTH_MODE=apiKey` (or `bearer`) before exposing the server outside `localhost`.
4. Configure retention TTLs via `CHRONOTRACE_RETENTION_*_DAYS` for your compliance requirements.
5. See `specs/operator-runbook.md` for full deployment and operational guidance.

---

## Changelog (key commits)

```
68eb898 fix tests: package-integrity expects 'dist/src/' not 'dist/', MCP client test skips gracefully when server unavailable
fb87090 feat(chronotrace-server): bounded ingest queue + circuit breaker
e2089f1 feat: RemoteRule persistence + delivery confirmation tracking
2032112 fix(sdk-kmp): add missing override val key in ChronoContextElement expect/actual
1713eed TS SDK HTTP transport: exponential backoff (max 3) on 503
12cc68c operator-runbook.md: add bounded-queue hardening, async insert config, and Kafka deferral decision
b3c1fa4 docs: refresh 01-gap-analysis.md to reflect v0.1.0 scope
08df448 confirm integration tests intact — disabledIfEnvVar guards already in place
2cd2348 fix: restore all non-testcontainer tests to passing state
546733e docs: refresh 08-progress-report.md with May 16 completions
20419d5 Trim SDK TS files field to ["dist/src/"] for leaner npm publish
9532491 chore(sdk-kmp): add jsTest and wasmJsTest source set dependencies
5026305 Fix MCP protocol compatibility for /mcp endpoint
3d44438 feat(sdk-kmp): add JS and Wasm behavioral tests
4cbccfc feat(chronotrace-server): add Prometheus-compatible /metrics endpoint
39a2d32 fix: force Gradle JDK 21 to avoid Kotlin 2.2.21 crash on JDK 25
85e6ad1 Phase 6 load/failure tests: queue overflow, reconnect backoff, crash-path flush, datastore outage
9509118 Phase 3 purge/retention: progress reporting, countsBySelector, listAll, tests, spec
21acb2d docs: clarify primary key ordering rationale in clickhouse-schema.md
3af876c fix E2eIntegrationTest timing: withSpan args order, flush waits
0a3d973 Phase 5: API authentication for chronotrace-server
5a82298 Phase 3: ClickHouse schema hardening — storage doc, integration tests, retention lifecycle
30ef791 Phase 4 MCP: replace placeholder schemas with real JSON schemas for all 11 tools
a77e179 sdk-kmp: add maven-publish plugin for JVM/JS/Wasm targets
95abbfa sdk-ts: publish-ready package config with exports, ESM/CJS entries, README
```
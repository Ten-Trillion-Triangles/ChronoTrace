# ChronoTrace Gap Analysis

Last reviewed: 2026-05-16

This document maps the original ChronoTrace spec to the current repository state.

## Validation Basis

Primary repo evidence used for this report:

- `README.md`
- `chronotrace-contract/src/commonMain/kotlin/org/chronotrace/contract/ChronoContracts.kt`
- `chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoStore.kt`
- `chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoStoreOptions.kt`
- `chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoStorage.kt`
- `chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoPurgeState.kt`
- `chronotrace-server/src/main/kotlin/org/chronotrace/server/ServerModule.kt`
- `chronotrace-server/src/main/kotlin/org/chronotrace/server/McpTooling.kt`
- `chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoTraceServer.kt`
- `docker-compose.yml`
- `sdk-ts/src/client.ts`
- `sdk-ts/src/capture.ts`
- `sdk-ts/src/instrumentation.ts`
- `sdk-ts/src/config.ts`
- `sdk-ts/src/remoteRules.ts`
- `sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoTrace.kt`
- `sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoCapture.kt`
- `sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoContextStorage.kt`

## Status Legend

- `Implemented`: present and working at baseline level.
- `Partial`: present, but not complete relative to the original spec.
- `Minimal`: only baseline scaffolding or dependency-level groundwork exists.
- `Missing`: not implemented in the repository.

## Section-by-Section Status

### 1. Executive Summary

Status: `Partial`

- The repository already reflects the product direction: no human dashboard, AI-oriented access, and SDK/server separation.
- The current implementation is still a baseline, not the finished “AI-native temporal logging framework” promised by the original spec.
- Completion requirement:
  - restate the final product scope in docs
  - align all later work with that scope

### 2. System Architecture And Technology Stack

Status: `Partial`

- Implemented:
  - Kotlin server and Kotlin shared contracts
  - canonical shared contract module used as the contract source of truth
  - TypeScript SDK single public surface backed by generated contract types
  - Kotlin Multiplatform SDK aligned to the shared contract types
- Missing:
  - persistent storage integration
  - final deployment architecture
  - additional language SDKs beyond TS and KMP
- Evidence:
  - `README.md` lists the server, contracts, `sdk-kmp`, and `sdk-ts`
  - `README.md` explicitly states the current server is in-memory

### 3. Client SDK Design

Status: `Partial`

- Implemented:
  - TS and KMP tracing APIs
  - buffering/redaction config
  - context propagation primitives
  - header inject/extract
  - remote-rule parsing/evaluation on the TS side
  - single TS public SDK surface instead of parallel public-looking paths
  - runtime `FrameSnapshot` creation in both SDK families
  - linked log-to-frame capture for auto-capture levels
  - manual trace/span capture when hidden locals are provided to the runtime
  - KMP compiler-plugin-based hidden locals injection
  - baseline runtime state and fatal-flush handling in both SDK families
- Missing:
  - final production-grade runtime guarantees across every supported environment
  - broader browser/worker operational validation
- Evidence:
  - `sdk-ts/src/client.ts`
  - `sdk-ts/src/capture.ts`
  - `sdk-ts/src/instrumentation.ts`
  - `sdk-ts/src/config.ts`
  - `sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoTrace.kt`
  - `sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoCapture.kt`
  - `sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoContextStorage.kt`
  - `chronotrace-kotlin-plugin/src/main/kotlin/org/chronotrace/plugin/ChronoTraceIrGenerationExtension.kt`

### 4. Execution Workflow Example

Status: `Partial`

- The baseline supports `withTrace`, `withSpan`, `startSpan`, and logger methods.
- Automatic structural capture on `error` is now implemented in the TS and KMP runtimes.
- Completion requirement:
  - update docs and examples so they reflect the real frame snapshot behavior and the remaining zero-code gaps

### 5. Zero-Code Variable Extraction And Context Mechanics

Status: `Partial`

- Implemented:
  - TS source transformer that injects hidden locals into logger and trace/span calls
  - Vite plugin wrapper for the TS source transformer
  - runtime hooks in both SDKs that accept injected locals and turn them into frame snapshots
  - K2 compiler plugin that injects hidden locals for KMP logger and trace/span calls
- Missing:
  - final Node/browser/worker integration packaging around the TS transformer
  - deeper runtime-matrix test coverage beyond the current baseline
- Completion requirement:
  - KMP plugin design and delivery
  - TS/JS transform integration completion
  - exact capture guarantees per runtime

### 6. Safe Serialization

Status: `Partial`

- TS contains redaction and bounded sanitization behavior.
- KMP now emits structured frame snapshot payloads with shared serialization metadata.
- Missing:
  - two-phase snapshot/formatting architecture
  - broader parity for object-introspection behavior across all runtimes
  - final truncation, circular-reference, and type fallback rules for unsupported host/runtime-specific objects

### 7. Resilience, Concurrency, And Crash Handling

Status: `Partial`

- TS includes queueing, buffer overflow behavior, explicit runtime state, and Node fatal hooks.
- KMP includes bounded buffering, explicit runtime state, and JVM shutdown-hook fatal flush support.
- Missing:
  - final runtime-specific crash guarantees across all environments
  - production reconnect/backoff semantics tied to the persistent backend
  - exact lock-free or equivalent concurrency guarantees per runtime

### 8. Clock Drift And Deterministic Ordering

Status: `Partial`

- Contracts already include timestamps and sequence IDs.
- Missing:
  - final ordering contract across persistent storage and distributed ingest
  - final sort keys tied to the production datastore

### 9. Payload Schema And Server Contract

Status: `Partial`

- The repository now has a canonical shared Kotlin contract module plus generated TS contract types for wire-facing shapes.
- Missing:
  - protobuf definitions from the original spec
  - final schema once later phases add protobuf or other wire-format changes

### 10. Backend Database And Ingestion Pipeline

Status: `Implemented` (baseline production path)

- Implemented:
  - `ChronoStore` routes through a storage/state boundary instead of a single in-memory-only implementation.
  - Server supports `file` and `clickhouse` storage modes through `ChronoStoreOptions`, `ServerModule`, and environment-driven startup config.
  - Baseline ClickHouse-backed storage implementation for ingest, log search, log/frame lookup, trace reconstruction, and frame stepping.
  - Baseline Valkey-backed purge-job state implementation for `clickhouse` mode.
  - Purge in `clickhouse` mode is asynchronous at the server level and restricted to indexed selector fields.
- Evidence:
  - `chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoStore.kt`
  - `chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoStoreOptions.kt`
  - `chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoStorage.kt`
  - `chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoPurgeState.kt`
  - `chronotrace-server/src/main/kotlin/org/chronotrace/server/ServerModule.kt`
  - `docker-compose.yml`

### 11. MCP Tool Schemas And Outputs

Status: `Implemented`

- Implemented:
  - `/mcp` endpoint with `initialize`, `tools/list`, and `tools/call`
  - All 11 tool descriptors have real JSON Schema definitions with input/output contracts, pagination behavior, and truncation rules
  - `McpToolingTest.kt` covers schema validation and functional tool calls (27 tests)
  - MCP protocol compatibility fix for `@modelcontextprotocol/sdk` Client.connect()
- Evidence:
  - `chronotrace-server/src/main/kotlin/org/chronotrace/server/ServerModule.kt`
  - `chronotrace-server/src/main/kotlin/org/chronotrace/server/McpTooling.kt`

### 12. Deployment

Status: `Partial` (baseline production path)

- Implemented:
  - Root `docker-compose.yml`
  - Server `Dockerfile`
  - API authentication: X-Api-Key and Bearer token modes via `ServerModule`
  - Prometheus `/metrics` endpoint via `ServerMetrics.kt`
- Missing:
  - production-ready server configuration
  - persistent service wiring
  - auth hardening (per-key quota, audit logging, key management endpoints)
  - TLS deployment modes
  - operator runbooks and hardening

## Gap Summary

### Implemented Now

- Server baseline
- Canonical shared contract module
- TS SDK single public surface with generated contract types
- KMP SDK baseline aligned to shared contracts
- Runtime frame capture in both SDK families
- TS source instrumentation helper plus Vite wrapper
- KMP compiler-plugin hidden-locals injection
- Baseline runtime health, dropped-event accounting, and fatal-flush hooks
- Tests/builds for current baseline
- `file` and `clickhouse` storage modes with ClickHouse/Valkey backend
- Valkey-backed async purge state for `clickhouse` mode
- All 11 MCP tool schemas with real JSON Schema definitions (27 McpToolingTest tests)
- API authentication: X-Api-Key and Bearer token modes
- Prometheus `/metrics` endpoint
- SDK TS publish pipeline (ESM/CJS entries, npm publish dry-run ready)
- SDK KMP maven-publish plugin (JVM/JS/Wasm targets)
- Load/failure path tests (SDK queue overflow, reconnect backoff, crash-path flush; server FailurePathTest, E2eIntegrationTest)

### Partial And Needs Expansion

- serialization (two-phase snapshot model not complete)
- buffering/concurrency (production durability not complete)
- JS/Wasm behavioral coverage (lighter than spec)
- ClickHouse/Valkey persistence hardening (schema tuning, retention lifecycle)
- deployment (TLS, persistent volumes, operator guidance)

### Still Missing

- Kafka or equivalent scale-out ingest buffering (if required for spec completion)
- final protobuf or post-JSON wire contract across SDKs
- production auth hardening (per-key quota, audit logging, key management endpoints)
- production-grade crash-path handling guarantees
- final release-grade acceptance matrix and publish pipeline completion

## Where A New Agent Should Start

- Read `08-progress-report.md` first for the current active phase and next implementation slice.
- Phase 5 (MCP) and Phase 5 (auth) are complete — do not revisit unless spec demands changes.
- Phase 7 (load/failure tests) is complete.
- The next active implementation target is Phase 3 storage hardening — ClickHouse schema tuning, retention lifecycle validation, and datastore-backed E2E tests. Phase 6 deployment and Phase 7 release gates follow storage hardening.
- For the v0.1.0 release scope, the primary code entrypoints are:
  - `sdk-ts/src/client.ts`, `sdk-ts/src/capture.ts`, `sdk-ts/src/instrumentation.ts` (TS SDK)
  - `sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoTrace.kt`, `ChronoCapture.kt`, `ChronoContextStorage.kt` (KMP SDK)
  - `chronotrace-kotlin-plugin/src/main/kotlin/org/chronotrace/plugin/ChronoTraceIrGenerationExtension.kt` (compiler plugin)
  - `chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoStore.kt`, `ChronoStoreOptions.kt`, `ChronoStorage.kt`, `ChronoPurgeState.kt`, `ServerModule.kt` (server/backend)
  - `chronotrace-server/src/main/kotlin/org/chronotrace/server/McpTooling.kt` (MCP interface)
- v0.1.0 tag (9532491) is the released baseline; all subsequent work is v0.2.0+ territory.

## Exit Criteria For This Gap Report

This document is maintained as a historical baseline. The canonical status reference is `08-progress-report.md`.

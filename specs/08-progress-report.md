# ChronoTrace Progress Report

Last reviewed: 2026-05-16 (Evening)

## Purpose

This document is the current implementation status dashboard for ChronoTrace. It summarizes what has been completed in the repository so far and compares that work against the original specification and the phase docs in this `specs/` directory.

## Overall Status

ChronoTrace is now a validated Phase 1 plus Phase 2 baseline with the first real Phase 3 backend slice landed, not a spec-complete system. The repository has a canonical shared contract module, a TypeScript SDK consolidated to one public surface, a Kotlin Multiplatform SDK aligned to the same wire-facing model, real frame snapshot capture in both SDK families, a working KMP compiler plugin, baseline runtime health/fatal-flush behavior, and a `ChronoStore` facade that can now run in file-backed or `clickhouse` modes. The remaining work is now concentrated in hardening the persistent backend, finalizing MCP contracts, and deployment/security hardening.

## Current Active Phase

- Active phase: `07-phase-test-and-release-plan.md` (load/failure testing complete)
- Why it is active now:
  - Phase 4 MCP tool schemas are complete (all 11 tools have real JSON schemas)
  - Phase 5 authentication is landed (X-Api-Key and Bearer token modes)
  - Phase 6 load/failure tests are complete (SDK + server failure path coverage)
  - The primary remaining gap is Phase 3 storage hardening and Phase 7 release gates

## Completed So Far

### Server / Backend Baseline

- Kotlin/Ktor server exposes health, ingest, log search, trace lookup, remote-rule, purge, and MCP-style endpoints.
- WebSocket ingest and HTTP ingest are both present in the baseline server.
- Trace, log, and frame lookup paths are wired end-to-end in the in-memory implementation.
- The server now routes `ChronoStore` operations through a file-backed persistent backend that snapshots logs/spans/frames and tracks purge jobs asynchronously.
- The server now supports explicit `file` and `clickhouse` storage modes through `ChronoStoreOptions`, `ServerModule`, and environment-driven startup config.
- A baseline ClickHouse-backed storage implementation now exists for ingest, search, lookup, trace reconstruction, and frame stepping.
- A baseline Valkey-backed purge-state implementation now exists for async purge-job tracking in `clickhouse` mode.
- RemoteRule persistence and delivery-confirmation tracking now landed: `LogRecord.triggeredRuleId`, `RemoteRule.createdAtUtc`/`expiresAtUtc`, `RuleDeliveryConfirmation` data class, `RuleDeliveryStatus` enum (PENDING/CONFIRMED/FAILED), ClickHouse `remote_rules` and `rule_delivery_log` tables, DB-backed `listRuleDeliveryConfirmations`/`ackRuleDelivery` in `ChronoStore`, MCP schemas updated (`triggeredRuleId` in search output, `createdAtUtc`/`expiresAtUtc` in list_remote_rules output), McpToolingTest coverage added.

### Shared Contracts

- Kotlin shared contract models exist for logs, spans, frame snapshots, remote rules, purge jobs, health, and tool calls.
- The contract module is now the canonical contract source for server, KMP, and generated TS wire-facing types.
- A TypeScript contract generation step and drift check now exist.

### TypeScript SDK Baseline

- Trace/span lifecycle APIs exist behind one public facade.
- Header propagation exists for `traceparent` and ChronoTrace headers.
- Buffering, redaction, transport selection, and remote-rule parsing/evaluation are implemented.
- Auto-capture levels now emit linked frame snapshots with structured locals and serialization metadata.
- A source instrumentation helper and Vite plugin wrapper now exist for hidden-locals injection.
- Runtime health/state and Node fatal hook support now exist.
- Wire-facing event types now come from generated contract output instead of hand-maintained duplicates.
- Current TS test, build, and contract-check commands pass.

### Kotlin Multiplatform SDK Baseline

- Trace/span lifecycle APIs exist.
- Coroutine-aware context propagation exists through `ChronoContextElement`.
- KMP baseline targets include JVM, JS, and Wasm scaffolding.
- KMP now emits shared canonical contract types instead of duplicate wire-facing event models.
- KMP runtime now emits linked frame snapshots for auto-capture and manual trace capture paths.
- K2 compiler-plugin rewriting now injects hidden locals for `ChronoLogger.*`, `withTrace`, and `withSpan`.
- Runtime health/state and JVM shutdown-hook fatal flush support now exist.
- Current JVM tests and JS/Wasm compile validation pass.

### Verification Coverage

- `./gradlew test` passes.
- `./gradlew :sdk-kmp:jvmTest` passes.
- `./gradlew :sdk-kmp:jsTest` passes (JS behavioral tests).
- `./gradlew :sdk-kmp:wasmJsTest` passes (Wasm behavioral tests).
- `./gradlew :sdk-kmp:compileKotlinJs` passes.
- `./gradlew :sdk-kmp:compileKotlinWasmJs` passes.
- `./gradlew :chronotrace-contract:verifyTypeScriptContracts` passes.
- `./gradlew :chronotrace-server:test` passes (McpToolingTest, AuthTest, FailurePathTest, E2eIntegrationTest).
- `cd sdk-ts && npm test` passes (35+ tests including failure path coverage).
- `cd sdk-ts && npm run check:contracts` passes.
- `cd sdk-ts && npm run build` passes.

## Partially Completed

- MCP support is complete — all 11 tools have real JSON schemas with input/output contracts, pagination behavior, and truncation rules. McpToolingTest.kt covers schema validation and functional tool calls.
- Remote rules exist with full persistence, delivery-confirmation tracking, and ClickHouse-backed storage (see above).
- Serialization/redaction now exists as a shared runtime capture path, but the final two-phase snapshot model and full object-parity across runtimes are not complete.
- Buffering and resilience now include explicit runtime state and fatal-flush hooks at baseline level, but the full production durability story is not complete.
- Deployment scaffolding exists through Docker files and compose setup, but the production deployment profile is not complete.
- KMP target coverage now compiles for JVM, JS, and Wasm, but test depth is still lighter than the original spec expectations.
- TS build-time instrumentation exists at the supported source-transform plus Vite level, but not every later packaging path is implemented.
- The backend persistence path now includes a baseline ClickHouse/Valkey mode, but it still needs deeper hardening, retention tuning, and datastore-focused test coverage.

## Missing / Not Started

- Final decision and implementation for Kafka or equivalent ingest buffering if required for spec completion.
- Final protobuf or equivalent post-JSON wire contract across SDKs.
- Final MCP schemas and agent-facing output contract.
- Production auth/TLS model and deployment hardening.
- Release-grade end-to-end, load, and failure-path validation.

## Most Important Remaining Gaps

- Phase 3 storage: ClickHouse schema tuning, retention hardening, and datastore-backed E2E validation.
- Phase 5 auth: per-key quota, audit logging, and key management endpoints.
- Phase 6 deployment: TLS configuration, persistent volume setup, and operator documentation.
- Phase 7 release gates: publish pipeline (npm + Maven), performance benchmarks, spec-complete sign-off.

## Comparison To Spec Docs

| Spec Doc | Status Now | Already Done | What Still Blocks Completion |
| --- | --- | --- | --- |
| `01-gap-analysis.md` | Current | Baseline inventory and requirement mapping exist | Needs future refresh as implementation changes land |
| `02-phase-foundation-and-contracts.md` | Implemented | Canonical shared contract module, TS surface consolidation, generated TS contracts, KMP alignment | Only normal follow-up maintenance, not phase-defining work |
| `03-phase-capture-and-sdk-instrumentation.md` | Implemented | Runtime frame capture, linked snapshots, TS source instrumentation, KMP compiler plugin, runtime health/fatal flush baseline | Only maintenance follow-up if later phases force runtime/contract refinement |
| `04-phase-storage-query-and-ingest.md` | Partial | File-backed persistence, `clickhouse` mode, Valkey purge state, config wiring, ClickHouse schema hardening, integration tests, purge/retention lifecycle | Datastore hardening, deeper retention tuning, durable ingest validation |
| `05-phase-mcp-and-agent-interfaces.md` | Implemented | `/mcp` endpoint, all 11 tool schemas with real JSON Schema, MCP protocol compatibility fix, McpToolingTest (27 tests) | Only maintenance — MCP is phase-complete |
| `06-phase-deployment-security-and-hardening.md` | Partial | Compose and Docker baseline, API auth (X-Api-Key + Bearer), /metrics endpoint | Auth hardening (per-key quota, audit), TLS, persistent deployment, operator guidance |
| `07-phase-test-and-release-plan.md` | Partial | Baseline tests, JS/Wasm behavioral tests, load/failure tests (SDK queue overflow, reconnect backoff, crash-path flush; server FailurePathTest, E2eIntegrationTest) | Release-grade E2E gates, performance benchmarks, publish pipeline |

## Recommended Next Focus

The next execution target is Phase 3 storage hardening — ClickHouse schema tuning, retention lifecycle validation, and datastore-backed E2E tests. MCP (Phase 5) and auth (Phase 5) are phase-complete and should not be revisited unless the spec demands changes. Deployment (Phase 6) and release (Phase 7) follow storage hardening.

## Immediate Next Implementation Slice

- Deepen datastore-backed tests around the new `clickhouse` mode, purge lifecycle, and startup/config validation.
- Harden the ClickHouse schema/bootstrap path and retention handling for real local compose usage.
- Expand health and operational reporting around the persistent backend.
- Preserve the Phase 2 SDK contract surface while maturing only the backend storage and query implementation.
- SDK TS publish pipeline: validate tarball installability, complete npm publish dry-run, publish to registry.
- SDK KMP publish: configure Maven Central/publish plugin for JVM/JS/Wasm targets.

## Verification Sources

Primary repo files used for this report:

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
- `chronotrace-kotlin-plugin/src/main/kotlin/org/chronotrace/plugin/ChronoTraceIrGenerationExtension.kt`

Verified commands:

- `./gradlew test`
- `./gradlew :sdk-kmp:jvmTest`
- `./gradlew :sdk-kmp:compileKotlinJs`
- `./gradlew :sdk-kmp:compileKotlinWasmJs`
- `./gradlew :chronotrace-contract:verifyTypeScriptContracts`
- `cd sdk-ts && npm test`
- `cd sdk-ts && npm run check:contracts`
- `cd sdk-ts && npm run build`

## Revision Log

- 2026-05-16: Refreshed with all May 16 completions. Phase 5 MCP complete (11 real JSON schemas, McpToolingTest 27 tests). Phase 5 auth landed (X-Api-Key + Bearer). Phase 6 load/failure tests complete (SDK queue overflow/reconnect backoff/crash-path flush, server FailurePathTest 6 tests, E2eIntegrationTest fixed). ServerMetrics.kt adds Prometheus /metrics endpoint. KMP JS/Wasm behavioral tests added. SDK TS publish-ready (exports, ESM/CJS entries). KMP maven-publish plugin added. JDK 21 forced in gradle.properties (JDK 25 crash workaround). MCP protocol compatibility fixed.
- 2026-03-11: Initial progress dashboard created from the baseline repository and existing spec docs.
- 2026-03-11: Phase 1 contract consolidation completed with shared contract generation, TS surface collapse, and KMP alignment.
- 2026-03-11: Phase 2 runtime capture baseline landed with real frame snapshots in both SDKs and TS source instrumentation support.
- 2026-03-11: Phase 2 closeout landed with the KMP compiler plugin, runtime health/state tracking, fatal-flush hooks, and JS/Wasm compile validation.
- 2026-03-11: Progress-tracking docs refreshed so Phase 3 is the explicit active phase and the remaining work is storage, MCP, and deployment hardening.
- 2026-03-11: Phase 3 landed the first real persistent backend slice with `clickhouse` storage mode, Valkey-backed purge state, and server/compose config wiring; the remaining work is backend hardening and datastore-focused validation.

# ChronoTrace Progress Report

Last reviewed: 2026-03-11

## Purpose

This document is the current implementation status dashboard for ChronoTrace. It summarizes what has been completed in the repository so far and compares that work against the original specification and the phase docs in this `specs/` directory.

## Overall Status

ChronoTrace is now a validated Phase 1 plus Phase 2 baseline with the first real Phase 3 backend slice landed, not a spec-complete system. The repository has a canonical shared contract module, a TypeScript SDK consolidated to one public surface, a Kotlin Multiplatform SDK aligned to the same wire-facing model, real frame snapshot capture in both SDK families, a working KMP compiler plugin, baseline runtime health/fatal-flush behavior, and a `ChronoStore` facade that can now run in file-backed or `clickhouse` modes. The remaining work is now concentrated in hardening the persistent backend, finalizing MCP contracts, and deployment/security hardening.

## Current Active Phase

- Active phase: `04-phase-storage-query-and-ingest.md`
- Why it is active now:
  - Phase 2 baseline capture/instrumentation work is implemented
  - the main product gap has shifted to hardening the new persistent ingest/query plane
  - the first persistent backend slice is now landed, so the phase has moved from design work to datastore hardening and validation
  - MCP and deployment work depend on the persistent data model this phase will define

## Completed So Far

### Server / Backend Baseline

- Kotlin/Ktor server exposes health, ingest, log search, trace lookup, remote-rule, purge, and MCP-style endpoints.
- WebSocket ingest and HTTP ingest are both present in the baseline server.
- Trace, log, and frame lookup paths are wired end-to-end in the in-memory implementation.
- The server now routes `ChronoStore` operations through a file-backed persistent backend that snapshots logs/spans/frames and tracks purge jobs asynchronously.
- The server now supports explicit `file` and `clickhouse` storage modes through `ChronoStoreOptions`, `ServerModule`, and environment-driven startup config.
- A baseline ClickHouse-backed storage implementation now exists for ingest, search, lookup, trace reconstruction, and frame stepping.
- A baseline Valkey-backed purge-state implementation now exists for async purge-job tracking in `clickhouse` mode.

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
- `./gradlew :sdk-kmp:compileKotlinJs` passes.
- `./gradlew :sdk-kmp:compileKotlinWasmJs` passes.
- `./gradlew :chronotrace-contract:verifyTypeScriptContracts` passes.
- `./gradlew :chronotrace-server:test` passes.
- `cd sdk-ts && npm test` passes.
- `cd sdk-ts && npm run check:contracts` passes.
- `cd sdk-ts && npm run build` passes.

## Partially Completed

- MCP support exists, but the tool schemas are still placeholders and the final agent contract is not complete.
- Remote rules exist, but the end-to-end server delivery, persistence, and full original-spec rule model are not complete.
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

- Persistent backend hardening and datastore-backed validation.
- Final MCP schemas and deterministic agent outputs.
- Production auth, TLS, and self-hosted hardening.
- Release-grade datastore-backed validation and failure-path coverage.

## Comparison To Spec Docs

| Spec Doc | Status Now | Already Done | What Still Blocks Completion |
| --- | --- | --- | --- |
| `01-gap-analysis.md` | Current | Baseline inventory and requirement mapping exist | Needs future refresh as implementation changes land |
| `02-phase-foundation-and-contracts.md` | Implemented | Canonical shared contract module, TS surface consolidation, generated TS contracts, KMP alignment | Only normal follow-up maintenance, not phase-defining work |
| `03-phase-capture-and-sdk-instrumentation.md` | Implemented | Runtime frame capture, linked snapshots, TS source instrumentation, KMP compiler plugin, runtime health/fatal flush baseline | Only maintenance follow-up if later phases force runtime/contract refinement |
| `04-phase-storage-query-and-ingest.md` | Partial | File-backed persistence, `clickhouse` mode, Valkey purge state, and config wiring exist | Datastore hardening, deeper tests, retention/purge maturity, durable ingest path |
| `05-phase-mcp-and-agent-interfaces.md` | Partial | `/mcp` endpoint and core tools exist | Final schemas, tool contracts, pagination, stable agent outputs |
| `06-phase-deployment-security-and-hardening.md` | Minimal | Compose and Docker baseline exist | Auth, TLS, persistent deployment modes, hardening, operator guidance |
| `07-phase-test-and-release-plan.md` | Partial | Baseline server, TS, KMP JVM tests, and KMP JS/Wasm compile verification exist | End-to-end, load, failure, and spec-complete release gates |

## Recommended Next Focus

The next execution target remains [04-phase-storage-query-and-ingest.md](./04-phase-storage-query-and-ingest.md). The current repo no longer needs capture/runtime groundwork first; it needs the new persistent backend hardened enough that later MCP and deployment work have a stable production-facing data plane.

## Immediate Next Implementation Slice

- Deepen datastore-backed tests around the new `clickhouse` mode, purge lifecycle, and startup/config validation.
- Harden the ClickHouse schema/bootstrap path and retention handling for real local compose usage.
- Expand health and operational reporting around the persistent backend.
- Preserve the Phase 2 SDK contract surface while maturing only the backend storage and query implementation.

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

- 2026-03-11: Initial progress dashboard created from the baseline repository and existing spec docs.
- 2026-03-11: Phase 1 contract consolidation completed with shared contract generation, TS surface collapse, and KMP alignment.
- 2026-03-11: Phase 2 runtime capture baseline landed with real frame snapshots in both SDKs and TS source instrumentation support.
- 2026-03-11: Phase 2 closeout landed with the KMP compiler plugin, runtime health/state tracking, fatal-flush hooks, and JS/Wasm compile validation.
- 2026-03-11: Progress-tracking docs refreshed so Phase 3 is the explicit active phase and the remaining work is storage, MCP, and deployment hardening.
- 2026-03-11: Phase 3 landed the first real persistent backend slice with `clickhouse` storage mode, Valkey-backed purge state, and server/compose config wiring; the remaining work is backend hardening and datastore-focused validation.

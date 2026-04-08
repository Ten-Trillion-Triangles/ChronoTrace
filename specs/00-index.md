# ChronoTrace Completion Specs

Last reviewed: 2026-03-11

This directory converts the original ChronoTrace specification into an implementation roadmap from the current repository baseline.

## Current Baseline

The repository already contains:

- A Kotlin/Ktor server with ingest, query, purge, remote-rule, and MCP-style endpoints.
- A canonical Kotlin Multiplatform contract module for logs, spans, frame snapshots, remote rules, purge jobs, and tool calls.
- A TypeScript SDK surface consolidated around `ChronoTrace` and `ChronoLogger`, backed by generated contract types.
- A Kotlin Multiplatform SDK aligned to the shared contract types with tracing and coroutine-aware context propagation.
- Runtime frame snapshot capture in both SDK families, including linked `linkedFrameId` wiring.
- A TypeScript source instrumentation helper and Vite plugin wrapper for hidden locals injection.
- A KMP compiler plugin that injects hidden locals into logger and trace/span calls.
- Runtime health/state tracking plus baseline fatal-flush hooks in the TS and KMP SDKs.
- A `ChronoStore` facade that now supports in-memory, file-backed, and `clickhouse` storage modes behind the same server routes.
- Baseline ClickHouse-backed storage wiring plus Valkey-backed purge-job state for the first real persistent Phase 3 backend slice.

The repository does not yet contain the full production completion path described by the original spec. The main missing areas are deeper ClickHouse/Valkey hardening and validation, finalized MCP contracts, and deployment/security hardening.

## Verified Commands

- `./gradlew test :chronotrace-contract:verifyTypeScriptContracts :sdk-kmp:compileKotlinJs :sdk-kmp:compileKotlinWasmJs`
- `cd sdk-ts && npm run check:contracts && npm test && npm run build`

## Document Order

1. [08-progress-report.md](./08-progress-report.md)
2. [01-gap-analysis.md](./01-gap-analysis.md)
3. [02-phase-foundation-and-contracts.md](./02-phase-foundation-and-contracts.md)
4. [03-phase-capture-and-sdk-instrumentation.md](./03-phase-capture-and-sdk-instrumentation.md)
5. [04-phase-storage-query-and-ingest.md](./04-phase-storage-query-and-ingest.md)
6. [05-phase-mcp-and-agent-interfaces.md](./05-phase-mcp-and-agent-interfaces.md)
7. [06-phase-deployment-security-and-hardening.md](./06-phase-deployment-security-and-hardening.md)
8. [07-phase-test-and-release-plan.md](./07-phase-test-and-release-plan.md)

## How To Use This Spec Set

- Read order for lost context:
  - `08-progress-report.md`
  - `01-gap-analysis.md`
  - the currently active phase doc
- Read the progress report first for the current implementation snapshot and active phase.
- Read the gap analysis second for the full requirement-by-requirement mapping.
- The currently active phase is now `04-phase-storage-query-and-ingest.md`.
- Execute the phase docs in order unless a later phase explicitly states that work can be parallelized.
- Treat each phase document as a completion contract. A phase is not done until its acceptance section is satisfied.

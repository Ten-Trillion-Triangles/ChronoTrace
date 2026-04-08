# Phase 3: Storage, Query, And Ingest

Last reviewed: 2026-03-11

## Goal

Replace the in-memory backend with the persistent ingest and query plane required by the original spec.

## Current Status

Status: `Partial`

This is the current active implementation phase.

### Already Landed

- Ingest, query, trace lookup, purge, and MCP-style operations exist against the in-memory `ChronoStore`.
- Contracts already model logs, spans, frame snapshots, purge jobs, and system health in shapes that can be carried into persistent storage.
- Phase 2 closeout means logs, spans, and frame snapshots now arrive from both SDK families with stable linked-capture semantics.
- `ChronoStore` now delegates to a file-backed persistent backend with JSON snapshots, TTL trimming, and asynchronous purge-job execution while keeping the existing API surface.
- `ChronoStore` now supports `clickhouse` mode with explicit server config, ClickHouse-backed ingest/query behavior, and Valkey-backed purge-job state.
- `docker-compose.yml` now includes the baseline environment wiring for running the server against ClickHouse and Valkey.

### Remaining To Complete This Phase

- Harden the new ClickHouse-backed data plane beyond the baseline implementation.
- Expand datastore-backed tests for ingest/query behavior, purge lifecycle, and startup/config validation.
- Finalize retention handling and operational health reporting for real persistent deployments.
- Decide and document whether Kafka remains deferred or becomes required for completion.
- Deepen purge-job observability and mutation tracking beyond the current baseline status model.

### Immediate Starting Slice

- Add datastore-backed tests around the new `clickhouse` mode and selector validation.
- Tighten the ClickHouse schema/bootstrap path and health output for real local compose usage.
- Keep the current HTTP, WebSocket, and MCP server routes stable while hardening the persistent backend under them.

### Code Entry Points

- `chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoStore.kt`
- `chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoStoreOptions.kt`
- `chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoStorage.kt`
- `chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoPurgeState.kt`
- `chronotrace-server/src/main/kotlin/org/chronotrace/server/ServerModule.kt`
- `chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoTraceServer.kt`
- `docker-compose.yml`
- `chronotrace-contract/src/commonMain/kotlin/org/chronotrace/contract/ChronoContracts.kt`

### Verification To Re-run

- `./gradlew :chronotrace-server:test`
- `./gradlew test :chronotrace-contract:verifyTypeScriptContracts :sdk-kmp:compileKotlinJs :sdk-kmp:compileKotlinWasmJs`
- Any new datastore-backed integration tests added during this phase

## Workstreams

### Persistent Storage

- Harden the ClickHouse-backed persistence for logs, spans, and frame snapshots.
- Harden the Valkey-backed purge-job state and operational metadata path.
- Decide whether Kafka is part of completion or remains a post-completion scale phase.

### Ingest Pipeline

- Define the durable ingest path from SDK transport to persisted records.
- Define batching, retries, partial failure handling, and backpressure.
- Define how WebSocket and HTTP ingest flows map into the same write pipeline.

### Query Plane

- Replace `ChronoStore`-backed lookups with datastore-backed query services.
- Preserve the current API intent while making results durable and pageable.
- Finalize trace reconstruction behavior from persisted spans/logs/frames.

### Retention And Purge

- Expand the new asynchronous purge-job flow with deeper status/progress reporting.
- Finalize purge selectors, statuses, and completion rules for persistent deployments.
- Finalize retention defaults and configurable TTLs in datastore terms.

## Required Outputs

- Storage schema doc
- Ingest pipeline doc
- Purge and retention doc
- Migration plan from current baseline store to persistent services

## Acceptance Criteria

- The server can run end-to-end in `clickhouse` mode through documented config.
- Core read paths no longer depend on in-memory collections in persistent mode.
- Purge is asynchronous and observable in persistent mode.
- Ordering, paging, and retention behavior are specified against the real datastore.

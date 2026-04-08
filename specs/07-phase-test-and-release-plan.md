# Phase 6: Test And Release Plan

Last reviewed: 2026-03-11

## Goal

Define the final acceptance matrix for calling the original ChronoTrace spec complete.

## Current Status

Status: `Partial`

### Already Landed

- Baseline server tests cover ingest, search, trace lookup, and MCP initialization/tool calling.
- TS tests now cover frame snapshot emission, serialization/redaction behavior, context propagation, source instrumentation, runtime health, and Node fatal-hook behavior.
- KMP JVM tests now cover trace/span behavior, frame snapshot emission, compiler-plugin-driven locals capture, and runtime state after transport failure.
- KMP JS and Wasm compile verification now pass as baseline coverage.

### Remaining To Complete This Phase

- Add deeper JS and Wasm behavioral validation for KMP beyond compile coverage.
- Add dedicated compiler-plugin rewrite/golden coverage beyond the current end-to-end usage exercised by `sdk-kmp` tests.
- Add end-to-end validation against persistent storage and finalized MCP contracts.
- Add load, reconnect, crash-path, and failure-injection coverage for the production architecture.

### Code Entry Points

- `chronotrace-server/src/test/kotlin/org/chronotrace/server/ServerModuleTest.kt`
- `sdk-ts/test`
- `sdk-kmp/src/commonTest/kotlin/com/chronotrace/sdk/ChronoSdkTest.kt`

### Verification To Re-run

- `./gradlew test :chronotrace-contract:verifyTypeScriptContracts :sdk-kmp:compileKotlinJs :sdk-kmp:compileKotlinWasmJs`
- `cd sdk-ts && npm run check:contracts && npm test && npm run build`

## Workstreams

### Server Validation

- Expand server tests beyond baseline happy-path ingest/query/MCP checks.
- Add datastore-backed integration tests once persistent storage is introduced.
- Add purge-job and retention validation.

### TS SDK Validation

- Preserve the current passing unit/integration coverage.
- Expand the existing transform/instrumentation tests beyond the current TS source transformer baseline.
- Add runtime-matrix coverage for Node, browser, and workers.

### KMP SDK Validation

- Preserve JVM tests.
- Preserve JS and Wasm compile verification.
- Add explicit JS and Wasm behavioral validation.
- Add dedicated compiler-plugin rewrite coverage alongside the current end-to-end plugin usage path.

### End-To-End Validation

- Add full flow tests from SDK event emission to persistent query retrieval.
- Add distributed trace propagation tests across service boundaries.
- Add remote-rule delivery and evaluation tests.

### Load And Failure Validation

- Add queue overflow scenarios.
- Add reconnect/backoff tests.
- Add crash-path flush tests where supported.
- Add datastore outage and partial-failure scenarios.

## Release Gates

- All phase-doc acceptance criteria satisfied
- Persistent storage enabled
- MCP schemas finalized
- zero-code instrumentation delivered for the supported runtime matrix
- deployment/security profile documented and tested
- end-to-end validation green

## Completion Definition

The original ChronoTrace spec is complete only when the repository no longer depends on baseline-only substitutions for core product promises.

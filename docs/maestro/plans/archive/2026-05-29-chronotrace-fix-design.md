# Design: Fix Production-Blocking Issues

## Problem Statement
ChronoTrace has 2 uncommitted production-blocking issues:
1. `HttpTransportJsTest.kt` (untracked) has 5 compilation errors due to visibility mismatches and JVM APIs used in JS test target
2. `/metrics` endpoint in `ServerModule.kt` has no auth check — 3 auth tests for it are failing

## Requirements
- Fix JS test compilation without breaking JVM/WASM targets
- Add auth to `/metrics` following the same pattern as `/api/v1/ingest`
- All 3 targets (JVM + JS + WASM) must compile and test successfully

## Approach
- **Phase 1**: Fix visibility issues in JS test + expose `apiKey` via `internal val` in `HttpTransport.js.kt`
- **Phase 2**: Add `authCheckWithKeyId` to `/metrics` route in `ServerModule.kt`
- **Phase 3**: Full verification across all targets

## Risk Assessment
- LOW risk: targeted visibility fixes + additive auth wiring following established patterns
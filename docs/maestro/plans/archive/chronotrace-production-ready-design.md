# ChronoTrace Production Readiness Fix

## Context
ChronoTrace is NOT yet ship-ready. A production readiness review identified 3 critical blockers, 4 major issues, 5 minor issues. This plan addresses ALL of them using TDD patterns with real live tests proving correctness.

## Issues Summary
**Critical Blockers (C1-C3):** WasmJs stub (C1 - now partially fixed, JS works), Missing native targets (C2), IrSuspendBody (C3 - already fixed).
**Major Issues (M4-M7):** No auth on /metrics (M4), CI tests disabled (M5), No schema migrations (M6), No circuit breaker JS/WASM (M7 - JS fixed, WasmJs blocked by Kotlin/Wasm interop).
**Minor Issues (m8-m12):** Various minor gaps.

## Resolution Strategy

### C1 - wasmJs HttpTransport (RESOLVED JS, IN-PROGRESS WasmJs)
- **JS target**: ✅ Fully implemented with `fetch` + circuit breaker + exponential backoff. Compiles and tests pass.
- **WasmJs target**: ❌ Kotlin/Wasm does not support `dynamic` type in function bodies, making standard `fetch` interop impossible without `@JsExport` typed wrappers. **Solution**: Create a `@JsExport` typed fetch wrapper at top-level that the HttpTransport calls.

### C2 - Native Targets (linuxX64, macosX64)
Add `linuxX64()` and `macosX64()` Kotlin targets with native curl-based HTTP transport.

### M4 - Auth on /metrics
Add `authCheckWithKeyId()` to `/metrics` route in ServerModule.kt.

### M5 - CI Integration Tests
Add ClickHouse testcontainer support for server integration tests.

### M6 - Schema Documentation
Document ClickHouse DDL in docs/clickhouse-schema.md.

### Validation
All fixes must compile and pass tests.
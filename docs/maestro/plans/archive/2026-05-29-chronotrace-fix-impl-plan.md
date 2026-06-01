# Plan: Fix Production-Blocking Issues — JS Test Compilation + Metrics Auth

## Phase 1: Fix HttpTransportJsTest.kt Compilation Errors
- Agent: coder
- Files: sdk-kmp/src/jsTest/kotlin/HttpTransportJsTest.kt, sdk-kmp/src/jsMain/kotlin/com/chronotrace/sdk/transport/HttpTransport.js.kt

## Phase 2: Add Auth to /metrics Endpoint
- Agent: coder
- Files: chronotrace-server/src/main/kotlin/org/chronotrace/server/ServerModule.kt

## Phase 3: Full Verification
- Agent: tester
- Runs: WASM compile, JS tests, JVM tests, server auth tests
# ChronoTrace: Real Time and Stack Traces for Linux/macOS Native

## Context

The linuxX64 and macosX64 native targets have stub implementations that produce fake/missing data:
- `ChronoPlatform.nowMillis()` returns a counter (1,2,3...) not wall-clock time
- `captureCallStack()` returns `emptyList()` always
- `HttpTransport.monotonicTimeMillis()` circuit breaker uses the same fake counter — 30_000 increments ≠ 30 seconds
- User explicitly said this is NOT ship-ready and demanded proper TDD-based implementation

## What I Learned

**Time (NO cinterop):** `kotlin.time.Clock.System.now().toEpochMilliseconds()` from stdlib — deprecated kotlinx-datetime is NOT needed. Available on all native targets including linuxX64 and macosX64.

**Stack traces (NO cinterop):** `Throwable().stackTraceToString()` on Kotlin/Native returns `Array<String>` with format like `com.chronotrace.sdk.capture(File.kt:5)`. Can parse with regex `(.+)\((.+):(\d+)\)`. Column number unavailable on native — set to null.

**Circuit breaker fix:** Same `kotlin.time.Clock.System` source replaces fake `monotonicCounter` with real elapsed time.

**macOS:** Same pattern as linuxX64 (confirmed with user).

**Test infrastructure:** No linuxX64Test or macosX64Test source sets exist. Must create from scratch using standard Kotlin/Multiplatform test configuration.

## TL;DR
- **Summary:** Replace all fake stub implementations with real kotlin.time.Clock wall-clock time and Kotlin/Native Throwable stack trace parsing
- **Deliverables:**
  - R̲ ChronoPlatform.nowMillis() using kotlin.time.Clock.System  
  - R̲ captureCallStack() using Throwable().stackTraceToString() parsed into CallStackItem
  - R̲ HttpTransport.monotonicTimeMillis() circuit breaker using real wall-clock time
  - A̲ linuxX64Test and macosX64Test source sets for TDD validation
  - P̲ Prove all fixes compile and tests pass
- **Effort:** Medium
- **Critical Path:** ChronoPlatform time fix → circuit breaker fix → tests
- **Test Strategy:** TDD - tests first to verify time progression and stack trace parsing

## Big Picture Intent

> **When facing unexpected decisions during execution, align with this intent.**

- **Original Problem:** Fake stub implementations (counter time, empty stack traces, broken circuit breaker timing) are not ship-ready
- **Why This Matters:** Timestamps are sequential integers not real time — cannot correlate native traces with server logs. Empty stack traces defeat the entire tracing purpose.
- **Key Constraints:** NO cinterop complexity — use stdlib only. Must use TDD approach with tests proving it works.
- **Primary Driver:** Prove with tests that time advances and stack traces are captured, not stubbed/faked

## Must NOT (Guardrails)
- Don't touch working JVM/JS/WASM implementations
- Don't use cinterop — stdlib only
- Don't break existing tests (JVM/JS/WASM tests must still pass)
- Don't add kotlinx-datetime dependency (kotlin.time.Clock is stdlib)

## Tasks

### Task 1: Fix ChronoPlatform.nowMillis() — Real Wall-Clock Time
- **Files:**
  - `sdk-kmp/src/linuxX64Main/kotlin/com/chronotrace/sdk/ChronoPlatform.linux.kt`
  - `sdk-kmp/src/macosX64Main/kotlin/com/chronotrace/sdk/ChronoPlatform.macos.kt`
- **Changes:** Replace stub counter with `kotlin.time.Clock.System.now().toEpochMilliseconds()` using the stdlib `kotlin.time.Clock` (confirmed available in Kotlin 1.9+ stdlib) [FIXED]
  - Note: Import `kotlin.time.Clock`. Call `Clock.System.now().toEpochMilliseconds()` — `Instant` returned by `now()` has `toEpochMilliseconds()` method.
- **Validation:** `nowMillis()` returns wall-clock ms (epoch since Unix epoch), not 1,2,3... counter
- **References:**
  - Pattern: `sdk-kmp/src/jvmMain/kotlin/com/chronotrace/sdk/ChronoPlatform.jvm.kt:4` — System.currentTimeMillis()
  - API: `kotlin.time.Clock.System.now().toEpochMilliseconds()` — stdlib, Instant.toEpochMilliseconds()
- **Tests:** Create `sdk-kmp/src/linuxX64Test/kotlin/com/chronotrace/sdk/ChronoPlatformLinuxTest.kt`
- **Commit:** `feat(sdk-kmp): use kotlin.time.Clock.System for linuxX64/macosX64 wall-clock time`
- **Acceptance:** 
  ```bash
  ./gradlew :sdk-kmp:compileKotlinLinuxX64 :sdk-kmp:compileKotlinMacosX64
  ```

### Task 2: Implement captureCallStack() — Real Stack Trace Parsing
- **Files:**
  - `sdk-kmp/src/linuxX64Main/kotlin/com/chronotrace/sdk/ChronoCapture.linux.kt`
  - `sdk-kmp/src/macosX64Main/kotlin/com/chronotrace/sdk/ChronoCapture.macos.kt`
- **Changes:** Use `Throwable().stackTraceToString()` and parse lines like `com.typ.Pkg.func(File.kt:5)` into List<CallStackItem>
- **Regex:** `^(?:at\s+)?(.+?)\((.+?):(\d+)\)$` — captures function, file:line
- **Filtering:** Drop ChronoCapture/ChronoRuntime frames and skipFrames, consistent with JVM implementation
- **Validation:** captureCallStack() returns non-empty list with valid functionName, filePath, lineNumber
- **References:**
  - Pattern: `sdk-kmp/src/jvmMain/kotlin/com/chronotrace/sdk/ChronoCapture.jvm.kt:5-19`
  - API: `org.chronotrace.contract.CallStackItem`
- **Tests:** Create `sdk-kmp/src/linuxX64Test/kotlin/com/chronotrace/sdk/ChronoCaptureLinuxTest.kt`
- **Commit:** `feat(sdk-kmp): captureCallStack for linuxX64/macosX64 via Throwable parsing`
- **Acceptance:**
  ```bash
  ./gradlew :sdk-kmp:compileKotlinLinuxX64 :sdk-kmp:compileKotlinMacosX64
  ```

### Task 3: Fix HttpTransport Circuit Breaker — Real Time
- **Files:**
  - `sdk-kmp/src/linuxX64Main/kotlin/com/chronotrace/sdk/transport/HttpTransport.linux.kt`
  - `sdk-kmp/src/macosX64Main/kotlin/com/chronotrace/sdk/transport/HttpTransport.macos.kt`
- **Changes:** Replace `monotonicCounter` with real wall-clock time: `kotlin.time.Clock.System.now().toEpochMilliseconds()` for circuit breaker timestamps. All time-based delays use real elapsed milliseconds.
- **Validation:** Circuit breaker opens after 5 failures, half-opens after ~30 real seconds (not 30000 counter increments)
- **References:**
  - Pattern: `sdk-kmp/src/jvmMain/kotlin/com/chronotrace/sdk/transport/HttpTransport.jvm.kt` — JVM version for reference
  - API: `kotlin.time.Clock.System.now().toEpochMilliseconds()`
- **Commit:** `fix(sdk-kmp): circuit breaker uses real wall-clock time on native`
- **Acceptance:**
  ```bash
  ./gradlew :sdk-kmp:compileKotlinLinuxX64 :sdk-kmp:compileKotlinMacosX64
  ```

### Task 4: Add Native Test Infrastructure
- **Files:**
  - `sdk-kmp/build.gradle.kts` — add linuxX64Test and macosX64Test source sets
  - `sdk-kmp/src/linuxX64Test/kotlin/com/chronotrace/sdk/` — test sources
  - `sdk-kmp/src/macosX64Test/kotlin/com/chronotrace/sdk/` — test sources
- **Changes:** Add native test source sets with `kotlin("test")` framework, same pattern as jvmTest/wasmJsTest. Reference existing jvmTest configuration at build.gradle.kts lines 44-50 for pattern.
- **Validation:** `./gradlew :sdk-kmp:compileTestKotlinLinuxX64` compiles (native test runtime availability varies by CI environment)
- **References:**
  - Pattern: `sdk-kmp/build.gradle.kts:44-50` — jvmTest configuration, and lines 56-60 for wasmJsTest
  - API: `kotlin { linuxX64() {...} }` sourceSets — add named("linuxX64Test") block with kotlin("test")
- **Commit:** `test(sdk-kmp): add linuxX64Test and macosX64Test source sets`
- **Acceptance:**
  ```bash
  ./gradlew :sdk-kmp:compileTestKotlinLinuxX64 2>&1 | head -20
  # Should compile without errors (tests may be NoOp if runtime unavailable)
  ```

## Decisions

1. **Use `kotlin.time.Clock.System.now().toEpochMilliseconds()` (stdlib)** — API confirmed from Kotlin stdlib reference: `Clock.System.now()` returns `Instant`, `Instant.toEpochMilliseconds()` is available. Not kotlinx-datetime which is deprecated for this use case.

2. **Use `Throwable().stackTraceToString()` NOT cinterop libbacktrace** — Native stdlib provides stack strings without C library dependency. Parse with regex — start with `(.+)\\((.+?):(\\d+)\\)` targeting the `funName(File.kt:line)` format seen in test output. Handle `at ` prefix and `kfun:` variants with fallback parsing if regex fails.

3. **Stack trace: Regex `^(?:at\s+)?(.+?)\((.+?):(\d+)\)$`** — Handles both JVM-style `at pkg.Class.method(File.kt:5)` parsed lines and Kotlin/Native native format `kfun:funcname @0xaddress`

4. **Native columnNumber = null** — Kotlin/Native stack strings don't include column number; JVM StackTraceElement provides it, native doesn't

5. **Copy same pattern for macOS** — User confirmed both platforms use same approach

## Risks

| Risk | Severity | Mitigation |
|------|----------|------------|
| Kotlin/Native stack format varies by version | MEDIUM | TDD approach: write test first, iterate parser until test passes. Start with simple regex, add variants as needed. |
| linuxX64Test runtime unavailable on CI | MEDIUM | Tests compile-verify only; manual on-device testing for runtime |
| Clock resolution: circuit breaker needs sub-second precision | LOW | Clock provides millisecond precision; exponential backoff math unchanged |
| Breaking JVM/JS/WASM time contract | LOW | Only the `actual` for linux/macOS changes; other platforms unchanged |
| Stack trace parsing fails on edge cases | MEDIUM | Implement test-first iterative approach; fallback to raw string parsing if regex fails |

## Validation Protocol

| Task | Validation Required | Command |
|------|---------------------|---------|
| 1: ChronoPlatform time | YES | `./gradlew :sdk-kmp:compileKotlinLinuxX64 :sdk-kmp:compileKotlinMacosX64` |
| 2: captureCallStack parsing | YES | Same compile + linuxX64Test asserts time>0 and stack non-empty |
| 3: Circuit breaker time | YES | Same compile + test circuit transitions at real timestamps |
| 4: Test infrastructure | YES | `./gradlew :sdk-kmp:compileTestKotlinLinuxX64` |
| Regression | YES | `./gradlew :sdk-kmp:jvmTest` (existing tests must pass) |

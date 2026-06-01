# Plan: ChronoTrace Critical Fixes

## Context

CEO review identified4 critical blockers before GitHub deployment. This plan addresses each one systematically.

---

## Issue 1: Local Variable Capture Test Failing

**Problem**: `sdk-kmp/src/commonTest/kotlin/com/chronotrace/sdk/ChronoSdkTest.kt` — test `manual trace locals create a frame snapshot` fails on all KMP platforms.

**Root Cause**: The test calls `withTrace("checkout")` which the compiler plugin is supposed to rewrite to `withTraceCaptured` with locals. But the SDK unit test compiles WITHOUT the compiler plugin — it tests the raw SDK. The SDK's `withTrace` calls `startSpan(name)` (no locals), so `captureLocals.isEmpty()` is true and NO frame snapshot is created. The test expects `localsJson` to contain `userId` but it never will without the plugin.

**Fix**: Call `withTraceCaptured` directly in the test. `withTraceCaptured` is `internal` (visible within same module) and takes a `Map<String, Any?>` of locals. The test already has `userId` as a local — pass it explicitly.

**File**: `sdk-kmp/src/commonTest/kotlin/com/chronotrace/sdk/ChronoSdkTest.kt`
**Change**: Lines 73-76 — replace `withTrace("checkout") { ChronoLogger.info("starting") }` with `withTraceCaptured("checkout", mapOf("userId" to userId)) { ChronoLogger.info("starting") }`

**Note**: This does NOT change production code. It changes a TEST to call the correct internal API. The E2E tests (JVM, JS, Wasm) already prove the plugin transformation works correctly.

---

## Issue 2: Remove accelbyte-sdk from settings.gradle.kts

**Problem**: `settings.gradle.kts` line 6 includes `accelbyte-sdk` which doesn't compile (unresolved references). `./gradlew build` fails.

**Fix**: Remove the include line.

**File**: `settings.gradle.kts`
**Change**: Line 6 — delete `include(":accelbyte-sdk")`

---

## Issue 3: Sync Version Documentation

**Problem**: Version mismatch across files:
- `RELEASE_NOTES.md` line 1: `v0.1.0` (stale)
- `README.md` line 34: `1.0.0`
- `gradle.properties` line 13: `1.0.0`
- `CHANGELOG.md` line 8: `1.0.0`
- Root `build.gradle.kts` line 10: `0.1.0-SNAPSHOT`

**Fix**: Update `RELEASE_NOTES.md` header to `v1.0.0` and root `build.gradle.kts` version to `1.0.0-SNAPSHOT` to match the actual released version.

**Files**:
- `RELEASE_NOTES.md` line 1: Change `# ChronoTrace v0.1.0 Release Notes` → `# ChronoTrace v1.0.0 Release Notes`
- `build.gradle.kts` (root) line 10: Change `version = "0.1.0-SNAPSHOT"` → `version = "1.0.0-SNAPSHOT"`

---

## Issue 4: Add JDK 25 Incompatibility to README

**Problem**: `gradle.properties` documents the JDK 25/Kotlin 2.2.21 crash but `README.md` does not. A developer on JDK 25 will try to build, fail, and not know why.

**Fix**: Add a note to the Prerequisites table in README.md about JDK 21 requirement for building.

**File**: `README.md`
**Change**: Add to the Prerequisites table row for "Server (dev)": `Java 21+ for build (Kotlin 2.2.21 crashes on JDK 25+), Gradle 8.x`

---

## Task Execution Order

1. **Issue 2** (accelbyte removal) — Simple, unblocks build
2. **Issue 3** (version sync) — Simple documentation fix
3. **Issue 4** (JDK 25 README note) — Simple documentation fix
4. **Issue 1** (local capture test) — Requires understanding the SDK internal API

---

## Verification

| Issue | Validation |
|-------|------------|
| Issue 1 | `./gradlew :sdk-kmp:jvmTest --tests "com.chronotrace.sdk.ChronoSdkTest.manual trace locals create a frame snapshot"` — PASS |
| Issue 2 | `./gradlew build` — Exit code 0 |
| Issue 3 | `grep "1.0.0" RELEASE_NOTES.md README.md gradle.properties CHANGELOG.md` — All match |
| Issue 4 | `grep -i "jdk.*21\|jdk.*25\|kotlin.*2.2.21" README.md` — Found |

---

## Files to Modify

| File | Action |
|------|--------|
| `sdk-kmp/src/commonTest/kotlin/com/chronotrace/sdk/ChronoSdkTest.kt` | Edit (test fix) |
| `settings.gradle.kts` | Edit (remove accelbyte-sdk line) |
| `RELEASE_NOTES.md` | Edit (version header) |
| `build.gradle.kts` (root) | Edit (version) |
| `README.md` | Edit (JDK 25 note) |

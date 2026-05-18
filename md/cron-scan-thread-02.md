# Test Suite Verification - Thread 02

**Date:** 2026-05-17  
**Time:** 19:39:15 - 23:42 (UTC)

---

## Summary

| Component | Tests | Passed | Failed | Skipped |
|-----------|-------|--------|--------|---------|
| chronotrace-server | 146 | 146 | 0 | 0 |
| sdk-kmp (jvmTest) | 7 | 7 | 0 | 0 |
| sdk-kmp (jvmTest via maven) | 3 | 3 | 0 | 0 |
| sdk-ts (vitest) | 82 | 82 | 0 | 0 |
| **TOTAL** | **238** | **238** | **0** | **0** |

---

## chronotrace-server (Kotlin/JVM)

**Location:** `chronotrace-server/build/test-results/test/`

| Test Class | Tests |
|------------|-------|
| AuditLoggingTest | 13 |
| AuthTest | 16 |
| ChronoStoreTest | 11 |
| ClickHouseStorageIntegrationTest | 5 |
| ClickHouseStorageTest | 5 |
| E2eIntegrationTest | 2 |
| FailurePathTest | 6 |
| IngestCircuitBreakerTest | 7 |
| KeyManagementTest | 22 |
| McpToolingTest | 27 |
| QuotaEnforcementTest | 9 |
| RetentionLifecycleIntegrationTest | 9 |
| RetentionLifecycleTest | 9 |
| ServerMetricsTest | 4 |
| ServerModuleTest | 1 |
| **Total** | **146** |

**All tests passed.** No failures, errors, or skipped tests.

---

## sdk-kmp (Kotlin Multiplatform) - jvmTest

**Location:** `sdk-kmp/build/test-results/jvmTest/`

| Test Class | Tests |
|------------|-------|
| ChronoSdkTest | 4 |
| MavenPublishConfigTest | 3 |
| **Total** | **7** |

**All tests passed.**

Note: Full KMP test suite includes jsTest, wasmJsTest, and other targets (not executed in this run):
- jsNodeTest: 18 tests
- jsBrowserTest: 18 tests
- wasmJsNodeTest: 11 tests
- wasmJsBrowserTest: 11 tests

---

## sdk-ts (TypeScript)

**Location:** `sdk-ts/`

| Test File | Tests |
|-----------|-------|
| package-integrity.test.ts | 13 |
| remoteRules.test.ts | 2 |
| redaction.test.ts | 2 |
| buffer.test.ts | 2 |
| mcp-client.test.ts | 11 |
| instrumentation.test.ts | 2 |
| nodeContext.test.ts | 2 |
| runtimeHealth.test.ts | 3 |
| sdk.test.ts | 3 |
| browser-compat.test.ts | 34 |
| failurePaths.test.ts | 8 |
| **Total** | **82** |

**All tests passed** (vitest run).

---

## Build Environment

- **DOCKER_AVAILABLE=true** passed to all Gradle commands
- **Gradle:** 8.14.3
- **Node.js:** For TS SDK tests
- **Vitest:** 3.2.4
- **Test Run:** 19:39:15 UTC

---

## Commands Executed

```bash
# Server + KMP JVM tests
DOCKER_AVAILABLE=true ./gradlew :chronotrace-server:test :sdk-kmp:jvmTest --rerun-tasks

# TS SDK tests
cd sdk-ts && npm test
```

---

## XML Results Parsed

- chronotrace-server: 15 XML files in `chronotrace-server/build/test-results/test/`
- sdk-kmp (jvm): 2 XML files in `sdk-kmp/build/test-results/jvmTest/`
- TS SDK: JSON output from vitest (11 test files, 82 tests)
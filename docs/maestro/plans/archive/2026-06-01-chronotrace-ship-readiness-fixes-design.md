---
title: "ChronoTrace Ship-Readiness Remediation"
created: "2026-06-01T07:35:00Z"
status: "approved"
authors: ["TechLead", "User"]
type: "design"
design_depth: "standard"
task_complexity: "complex"
---

# ChronoTrace Ship-Readiness Remediation Design

## Problem Statement

The previous audit returned verdict **NOT-SHIP-READY** for the ChronoTrace framework. 60+ issues span 11 modules; the K2 compiler plugins — the product's primary value proposition — are functionally broken for any consumer because of unreachable `internal` SDK symbols and a visitor that does not descend into the inner `SuspendLambda.invokeSuspend` body. The cross-platform runtime parity goal is unmet on JS, WasmJs, linuxX64, and macosX64 (runtime hooks that lose buffered events; context elements that are no-ops; wasmJs transport that lacks circuit breaker, retry, and backoff). The TypeScript contract generator hand-maintains a list that has drifted from the Kotlin source (5 missing types, 4 missing `RemoteRule` fields), and the server's `StatusPages` silently downgrades unhandled exceptions to HTTP 200.

The user has directed: "Fix all the found issues, and prove they have all been fully fixed. I assume the not ship ready issues aren't some outright technical wall and can be fixed to the intended, and expected design spec I set out for this project where all platforms work thee same with logging and stack captures."

## Requirements

### Functional Requirements

1. **REQ-1**: Every KMP target (JVM, JS, WasmJs, linuxX64, macosX64) must implement the same `ChronoRuntimeHooks` contract: a process/page lifecycle hook that calls `runtimeRef.flushFatal()` before teardown so buffered events are not lost.
2. **REQ-2**: Every KMP target must implement the same `ChronoContextElement` contract: `updateThreadContext`/`restoreThreadContext` push and pop `ChronoContextStorage.current` so `withContext(ChronoContextElement(ctx))` propagates context.
3. **REQ-3**: Every KMP target must implement the same `HttpTransport` contract: circuit breaker (5-failure threshold, 30s half-open), exponential backoff, retry up to `maxRetries`.
4. **REQ-4**: The K2 compiler plugin must successfully resolve and rewrite SDK calls in any consumer module — including `suspend fun` bodies.
5. **REQ-5**: The TypeScript contract (`sdk-ts/src/generated/contracts.ts`) must match the Kotlin source (`ChronoContracts.kt`) 1:1; the gradle `verifyTypeScriptContracts` task must pass in CI.
6. **REQ-6**: The server's `StatusPages` must return a real HTTP 5xx code on unhandled exceptions; 404 handlers must return HTTP 404.
7. **REQ-7**: The server must expose HTTP `DELETE /api/v1/remote-rules/{ruleId}` and `GET /api/v1/frames/{frameId}/step` to match the existing MCP surface.
8. **REQ-8**: The Gradle plugin must fail loudly (via `project.logger.error` + `throw`) when the plugin JAR cannot be resolved, and add a documented Kotlin version check.
9. **REQ-9**: `load-test/` k6 scripts must send payloads that match the server's `IngestBatch` schema; the success rate against a healthy local server must be 100%.
10. **REQ-10**: `sdk-ts` must use `crypto.getRandomValues` for IDs (not `Math.random`); must wire `AuthConfig` through `HttpTransport`; must implement `trace()` (not downcast to `debug`); must not depend on `@accelbyte/sdk`.

### Non-Functional Requirements

1. **REQ-N1**: Every fix must be proven by build + test, a direct exercise, or a CI/regenerated artifact.
2. **REQ-N2**: All 5 KMP targets must build and test green.
3. **REQ-N3**: `verifyTypeScriptContracts` must exit 0 in CI.
4. **REQ-N4**: The orphan `accelbyte-sdk/` module and 14 dead TestKit fixture projects must be removed; the committed `hs_err_pid*.log`, `.bak` files, leftover tarballs, `.codex`, `bui/`, `tstep-enhancements/`, `.hermes/plans/`, and `clickhouse-user-config.xml` must be deleted.
5. **REQ-N5**: Version drift must be resolved: `gradle.properties` is the single source of truth, and `build.gradle.kts` + `sdk-ts/package.json` + all subproject manifests must match.
6. **REQ-N6**: License mismatch (MIT root vs Apache-2.0 in `sdk-ts/package.json`) must be resolved; the broken `repository: https://github.com/TTT/ChronoTrace` URL must be fixed.
7. **REQ-N7**: Documentation must match reality: `mcp/v1` → `POST /mcp`; `CHRONOTRACE_PORT` → `PORT`; retention env var names corrected; `mTLS`/`BLOCK_CALLER` claims removed (or implemented).

### Constraints

- The design is **cross-platform parity**: every target must honor the same contract.
- The plugin's `HelperSymbols` resolve SDK functions by FQ-name; the smallest fix is to drop `internal` on the three functions rather than wire `friendPaths` (consistent with the existing build-comment in `chronotrace-kotlin-plugin-js/build.gradle.kts:15-17`).
- The minimum-change approach is the right one — the design exists, the audit mapped the gaps.

## Approach

### Cross-cutting principles

1. **Public API over friend-paths** for plugin ↔ SDK access.
2. **Visitor descent for `suspend fun`** in all three compiler-plugin extensions (JVM, JS, WasmJs).
3. **Per-platform runtime parity** with JVM as the reference.
4. **Hand-maintained contract list with mandatory CI guard** (no runtime reflection; keeps the generator simple and the diff small).
5. **Single version source of truth** in `gradle.properties`.

### Architectural choices (resolved)

- **Plugin ↔ SDK access**: public API (drop `internal`).
- **Suspend instrumentation**: extend `visitFunction` to descend into inner `SuspendLambda.invokeSuspend`.
- **Native shutdown**: `atexit(StaticCFunction { ... })` via `kotlinx.cinterop` from `platform.posix`.
- **TS contract generation**: hand-maintained list with mandatory `verifyTypeScriptContracts` CI check.
- **Wasm `HttpTransport` parity**: status-returning JS callback (`@JsFun callJsFetchWithStatus(jsonBatch: String): Promise<Int>`).
- **`load-test/` payloads**: rewrite to match `IngestBatch` schema.
- **Plugin Gradle wiring**: `project.logger.error` + `throw GradleException` on failure; add Kotlin version check.
- **TestKit fixtures**: delete 14 dead projects.

### Alternatives considered

- **friendPaths over public API** — rejected because the existing build comment already presumes public reachability, and `friendPaths` is harder to make portable across KMP targets.
- **Reflection-based contract generator** — rejected because runtime reflection is heavyweight, error-prone in KMP, and a hand-maintained list with a CI guard is simpler.
- **Phase-change IrGenerationExtension for suspend** — rejected as a redesign; visitor descent is the minimum change.
- **Signal handler over `atexit` for native** — rejected because `atexit` is the POSIX standard and is the simplest portable answer.

## Architecture

```
            ┌─────────────────────────────────────────────┐
            │              chronotrace-server             │
            │  Ktor 3.x / Netty / ClickHouse / Valkey /   │
            │  Prometheus / MCP (11 tools)                │
            └─────────────────┬───────────────────────────┘
                              │ HTTP / WS / MCP
       ┌──────────────────────┼──────────────────────┐
       │                      │                      │
       ▼                      ▼                      ▼
┌──────────────┐     ┌──────────────┐      ┌──────────────┐
│   sdk-kmp    │     │   sdk-ts     │      │   MCP clients│
│  JVM JS Wasm │     │  Node /      │      │              │
│  Linux macOS │     │  browser     │      │              │
└──────┬───────┘     └──────┬───────┘      └──────────────┘
       │                    │
       │ at compile time    │
       ▼                    ▼
┌──────────────┐     ┌──────────────┐
│ JVM K2 plugin│     │   Vite plugin│
│  JS plugin   │     └──────────────┘
│  Wasm plugin │
└──────────────┘
       │ reads SDK symbols via
       ▼
┌──────────────────────────────────────────────┐
│   chronotrace-contract  (single source)      │
│   28 @Serializable types → TS via gradle     │
│   generateTypeScriptContracts + verify-...   │
└──────────────────────────────────────────────┘
```

## Risk Assessment

| Risk | Severity | Likelihood | Mitigation |
|------|----------|------------|------------|
| Suspend visitor descent breaks existing instrumentation | HIGH | MEDIUM | Additive change (`isSuspend` only); full plugin test suite; new suspend test case |
| Dropping `internal` pollutes public API | LOW | LOW | KDoc + `@Deprecated` for plugin-internal symbols |
| `atexit` not available on all K/N targets | MEDIUM | LOW | Linux + macOS both ship `platform.posix.atexit`; verified by tests |
| Wasm `Promise<Int>` bootstrap requires JS-side changes | MEDIUM | MEDIUM | Mirror existing `callJsFetch` shape; 1-file JS change |
| Regenerated TS contract breaks `sdk-ts` | MEDIUM | MEDIUM | Type-only diff; fix rendering, not consumer |
| Missed audit findings | MEDIUM | MEDIUM | Final re-audit workflow catches leftovers; iterate to SHIP-READY |

## Success Criteria

1. All 5 KMP targets build green: `./gradlew :sdk-kmp:build` succeeds.
2. All KMP target tests pass: jvm, jsNode, wasmJsNode, linuxX64, macosX64.
3. Plugin tests pass; suspend instrumentation test reports >0 instrumented functions in a `suspend fun` body.
4. `verifyTypeScriptContracts` exits 0.
5. `sdk-ts` `npm run build` + `npm test` pass; no `@accelbyte/sdk`, no `Math.random` in `src/`.
6. Server `StatusPages` returns 500 on `RuntimeException`; new test asserts this.
7. `DELETE /api/v1/remote-rules/{ruleId}` returns 204 then 404; `GET /api/v1/frames/{frameId}/step?direction=next` returns the next frame.
8. `accelbyte-sdk/` is deleted; 14 dead fixtures deleted; crash dump deleted.
9. Version 1.0.0 propagated to all subprojects; license consistent; repo URL real.
10. Documentation matches reality (env var names, MCP route, no `mTLS`/`BLOCK_CALLER` claims).
11. **Re-running the original audit workflow returns verdict: SHIP-READY.**

See `/home/cage/.claude/plans/quiet-bubbling-kahan.md` for the full file inventory, execution strategy, and verification commands.

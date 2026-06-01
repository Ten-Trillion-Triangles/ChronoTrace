---
title: "ChronoTrace Full Ship-Readiness Remediation"
created: "2026-06-01T17:08:00Z"
status: "approved"
authors: ["TechLead", "User"]
type: "design"
design_depth: "standard"
task_complexity: "complex"
---

# ChronoTrace Full Ship-Readiness Remediation — Design

## Problem Statement

The ChronoTrace KMP framework is **NOT-SHIP-READY for v1.0.0** per a 6-phase ship-readiness audit (`/chronotrace-ship-readiness-audit`) that returned **4 critical + 11 high + 12 medium + 25 low findings** (52 total). The headline K2 IR plugin is silently broken on suspend functions, the JS HttpTransport is non-functional in real environments, TLS is announced but not wired to the Netty engine, and `docker-compose.yml` mounts a deleted file. A prior targeted remediation (the 9-phase plan) closed the original audit's 11 items but exposed deeper issues that this design addresses comprehensively.

**User directive**: "Fix all of these issues in full. TDD, and prove working. Apply ship-ready review afterwards and report findings. Consult me if something needs to be decided upon that's major such as a breaking change or radical refactor. Otherwise take care of these all yourself."

## Requirements

### Functional Requirements

1. **REQ-1**: All 52 audit findings (4 critical, 11 high, 12 medium, 25 low) are resolved.
2. **REQ-2**: Every fix has a failing test written first (TDD discipline).
3. **REQ-3**: Each fix is proven working (test passes, build is green, smoke test where applicable).
4. **REQ-4**: The same `/chronotrace-ship-readiness-audit` workflow that returned NOT-SHIP-READY now returns SHIP-READY.
5. **REQ-5**: Cross-platform parity preserved: JVM, JS, WasmJs, linuxX64, macosX64, TypeScript all implement the same logging and stack-capture contract.

### Non-Functional Requirements

1. **REQ-N1**: Minimal diffs (user directive: "Be conservative: minimal diffs").
2. **REQ-N2**: No new module additions, no version bumps, no .gitignore extensions beyond what was originally reverted.
3. **REQ-N3**: Reverted files (build.gradle.kts version, settings.gradle.kts modules, clickhouse-user-config.xml, .gitignore, HttpTransport.js.kt stub, ChronoTraceServer.kt) are NOT re-applied.

### Constraints

- Repository state: 8211 files, 11 directory depth, 116 modified files, ~1694 new / 11318 removed lines (per audit).
- 8 Gradle modules: chronotrace-contract, chronotrace-server, sdk-kmp, chronotrace-kotlin-plugin, chronotrace-kotlin-plugin-js, chronotrace-kotlin-plugin-wasm, chronotrace-kotlin-plugin-gradle, chronotrace-ir-core (placeholder).
- Multi-language: Kotlin (JVM/JS/WasmJs/Native), TypeScript, gradle scripts, Dockerfile, docker-compose, K6 load tests, ClickHouse + Valkey.
- Build system: Gradle with K2 compiler plugin.
- User explicitly delegates all decisions except breaking changes / radical refactors.

## Approach

### Selected Approach

**Sequential Phase Execution with TDD Discipline** — Execute the 12-phase plan in `/home/cage/.claude/plans/quiet-bubbling-kahan.md` (also materialized at `docs/maestro/plans/2026-06-01-chronotrace-ship-ready-remediation-impl-plan.md`). Each phase is delegated to the appropriate agent with a TDD-first contract:

1. **Test first**: Agent writes a failing test that demonstrates the bug.
2. **Fix second**: Agent applies the minimum diff to make the test pass.
3. **Verify third**: Agent runs the test, captures output, confirms green.
4. **Iterate**: If test does not pass, agent iterates until it does.

Sequential execution (not parallel) is chosen because the plan has 12 sequential phases with shared module file ownership — Phase 0 (foundation) blocks 1-9; Phase 1-9 are mostly independent of each other but their fix patterns (TDD test creation, plugin class extension, module structure) are easier to validate when run one at a time.

### Alternatives Considered

#### A. Big-bang parallel execution
- **Description**: Dispatch all 12 phases in parallel.
- **Pros**: Faster wall-clock.
- **Cons**: Phase 0 must complete first; later phases share modules (sdk-kmp, chronotrace-server); risk of file ownership conflicts.
- **Rejected Because**: File ownership contention would dominate the speedup; the audit's prior reverts were caused by over-eager parallel agents.

#### B. Single mega-agent
- **Description**: One agent does everything.
- **Pros**: No coordination overhead.
- **Cons**: Context window pressure for 52 fixes; no specialist expertise per phase.
- **Rejected Because**: 52 fixes across 8 modules is too large for a single agent's context.

### Decision Matrix

| Criterion | Weight | A. Parallel | B. Single agent | C. Sequential (selected) |
|-----------|--------|-------------|----------------|--------------------------|
| TDD rigor per phase | 25% | 3: coordination overhead | 2: context pressure | 5: one focus at a time |
| File ownership safety | 25% | 1: high collision risk | 5: single owner | 5: one phase at a time |
| Wall-clock time | 20% | 4: faster | 3: depends | 3: 5-7 hours |
| Verifiability | 20% | 3: hard to track | 2: monolithic | 5: per-phase green |
| User oversight load | 10% | 4: high churn | 2: dump all at once | 4: phase reports |
| **Weighted Total** | | 2.85 | 2.65 | **4.5** |

## Architecture

### Component Diagram

```
                        ┌────────────────────────────────────────┐
                        │       ChronoTrace Workspace            │
                        └────────────────────────────────────────┘
                                          │
        ┌─────────────────────────────────┼─────────────────────────────────┐
        │                                 │                                 │
   ┌────▼─────┐                    ┌──────▼──────┐                  ┌──────▼──────┐
   │ sdk-kmp  │ ◄──K2 Plugin───────│  Plugins   │                  │chronotrace- │
   │ (5 KMP   │    depends on       │ (3 variants)│                  │   server    │
   │ targets) │                     └──────┬──────┘                  │  (JVM/Ktor) │
   └────┬─────┘                            │                          └──────┬──────┘
        │                            ┌─────▼──────┐                          │
   ┌────▼─────┐                      │  Plugin    │                    ┌─────▼──────┐
   │  sdk-ts  │                      │  Gradle    │                    │ ClickHouse │
   │ (TypeS.) │                      └─────┬──────┘                    │  + Valkey  │
   └────┬─────┘                            │                            └────────────┘
        │                            ┌─────▼──────┐
   ┌────▼─────┐                      │  Shared    │
   │  Vite    │                      │  IR Core   │
   └──────────┘                      │  (Phase 0) │
                                     └────────────┘
```

### Data Flow

The K2 IR plugin (3 variants → 1 shared core) reads user Kotlin code, instruments `ChronoLogger.*` calls to inject locals-capture, and the instrumented code calls the SDK to send logs/spans to the server via the HTTP/WS transport. The server stores in ClickHouse + Valkey. The TypeScript SDK parallel for JS/TS consumers.

### Key Interfaces

- `HelperSymbols` (in shared core) — resolves SDK symbol references with diagnostic errors.
- `IrGenerationExtension` (in shared core) — visits user IR, rewrites `ChronoLogger.*` calls.
- `HttpTransport` (in sdk-kmp) — multi-target HTTP transport with circuit breaker.
- `ChronoTransport` (in sdk-kmp) — interface implemented by HttpTransport + WebSocketTransport.

## Agent Team

| Phase | Primary Agent | Secondary Agent | Deliverable |
|-------|--------------|-----------------|-------------|
| 0 | `coder` | `refactor` | Foundation, hygiene, shared IR core module |
| 1 | `coder` | `tester` | Suspend function IR instrumentation |
| 2 | `coder` | `tester` | JS HttpTransport real fetch |
| 3 | `coder` | `tester` | TLS wiring to Netty |
| 4 | `coder` | — | docker-compose mount + repo hygiene |
| 5 | `coder` | `tester` | Native FD leak + plugin wiring |
| 6 | `coder` | `tester` | sdk-ts WS/mTLS/QuotaTracker |
| 7 | `coder` | `tester` | Plugin Gradle real tests + Configuration |
| 8 | `coder` | `tester` | Contract tests + generator hardening + maven-publish |
| 9 | `coder` | `tester` | HelperSymbols diagnostic + sdk-kmp open issues |
| 10 | `coder` | `tester` | 12 medium-priority fixes |
| 11 | `coder` | `technical-writer` | 25 low-priority + docs parity |
| 12 | (workflow) | — | Final re-audit |
| Code review | `code-reviewer` | — | Validate all changes |

## Risk Assessment

| Risk | Severity | Likelihood | Mitigation |
|------|----------|------------|------------|
| Phase 1 IR refactor breaks existing (non-suspend) instrumentation | HIGH | MEDIUM | Existing test suite + new suspend-specific test |
| Phase 3 TLS wiring has Ktor/Netty gotchas (JKS format, password env) | MEDIUM | MEDIUM | TlsWiringTest with self-signed JKS |
| Phase 6 WS reconnect edge cases (rapid disconnect, server not back) | MEDIUM | MEDIUM | Multiple reconnect test scenarios |
| Phase 7 GradleRunner tests slow/flaky in CI | MEDIUM | MEDIUM | Realistic timeouts, no parallelism |
| Phase 11 doc drift has many small fixes | LOW | HIGH | Item-by-item validation against audit output |
| Re-audit returns NOT-SHIP-READY | MEDIUM | LOW | Iterate back to specific phase |

## Success Criteria

1. All 52 audit items resolved with TDD evidence (failing test → fix → passing test).
2. Full build green: `./gradlew clean build` succeeds on all 8 modules.
3. All test tasks green: plugin, sdk-kmp (5 targets), contract, server, sdk-ts.
4. Live smoke: server starts, JS SDK connects, logs flow end-to-end.
5. `clickhouse-user-config.xml` present; `docker compose config` valid.
6. Hygiene: no stale research artifacts, version consistent, gitignore updated.
7. `/chronotrace-ship-readiness-audit` returns verdict **SHIP-READY**.

## Reference Documents

- Implementation Plan: `docs/maestro/plans/2026-06-01-chronotrace-ship-ready-remediation-impl-plan.md`
- Source Plan: `/home/cage/.claude/plans/quiet-bubbling-kahan.md`
- Audit Output: `/tmp/claude-1000/-home-cage-Desktop-Workspaces-ChronoTrace/0c02f66d-947f-4d3a-b630-5da34cc894fe/tasks/w7os17gjj.output` (re-audit that exposed the 4 NEW criticals)

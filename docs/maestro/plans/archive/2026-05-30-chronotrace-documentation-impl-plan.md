---
title: "ChronoTrace Documentation Implementation Plan"
design_ref: "docs/maestro/plans/2026-05-30-chronotrace-documentation-design.md"
created: "2026-05-30T13:46:31Z"
status: "draft"
total_phases: 4
estimated_files: 5
task_complexity: "complex"
---

# ChronoTrace Documentation Implementation Plan

## Plan Overview

- **Total phases**: 4
- **Agents involved**: technical-writer
- **Estimated effort**: Documentation audit and improvement across 5 files; ~500 lines of new content

## Dependency Graph

```
Phase 1 (Audit& Fix Stale References)
        |
        v
Phase 2 (Install/Build Documentation)
        |
        v
Phase 3 (SDK Public API Reference)
        |
        v
Phase 4 (Review & Link Checking)
```

## Execution Strategy

| Stage | Phases | Execution | Agent Count | Notes |
|-------|--------|-----------|-------------|-------|
| 1 | Phase 1 | Sequential | 1 | Version audit and fixes |
| 2 | Phase 2 | Sequential | 1 | New install.md |
| 3 | Phase 3 | Sequential | 1 | New sdk-api.md |
| 4 | Phase 4 | Sequential | 1 | Link verification |

---

## Phase 1: Audit & Fix Stale References

### Objective
Fix all stale version references in README.md, docs/user-manual.md, and docs/deployment-guide.md to reflect version 1.0.0, Kotlin 2.2.21, and correct Gradle plugin IDs.

### Agent: technical-writer
### Parallel: No

### Files to Modify

- `README.md` — Fix: (1) version 0.1.0-SNAPSHOT → 1.0.0, (2) Gradle plugin ID `id("com.chronotrace.kotlin-plugin")` → `id("org.chronotrace.kotlin-plugin")`, (3) SDK dependency version 0.1.0 → 1.0.0
- `docs/user-manual.md` — Fix: (1) Kotlin multiplatform plugin version "2.1.0" → "2.2.21", (2) SDK version sdk-kmp:0.1.0 → sdk-kmp:1.0.0, (3) Gradle plugin version "0.1.0" → "1.0.0", (4) Plugin ID same correction as README
- `docs/deployment-guide.md` — Audit for any "0.1.0" or "2.1.0" references and fix

### Implementation Details

Read each file, search for the stale patterns, and replace:
- `0.1.0-SNAPSHOT` → `1.0.0`
- `0.1.0` (standalone version) → `1.0.0`
- `version "2.1.0"` (Kotlin version in examples) → `version "2.2.21"`
- `id("com.chronotrace.kotlin-plugin")` → `id("org.chronotrace.kotlin-plugin")`
- `com.chronotrace.kotlin-plugin` → `org.chronotrace.kotlin-plugin`

### Validation

- `./gradlew build -x test` passes (doc-only changes, build should be unaffected)
- Grep for remaining `0.1.0` patterns in docs/ and README.md — should return no matches

### Dependencies
- Blocked by: None
- Blocks: Phase 2

---

## Phase 2: Install/Build Documentation

### Objective
Create docs/install.md covering build-from-source, prerequisites, server operation, and Docker setup.

### Agent: technical-writer
### Parallel: No

### Files to Create

- `docs/install.md` — New install/build guide with sections:
  1. **Prerequisites** — Java 21+ JDK, Node.js 18+, Docker & Docker Compose
  2. **Building from source** — `./gradlew build`, per-module targets (`:chronotrace-server:installDist`, `:sdk-kmp:publishToMavenLocal`, `:chronotrace-contract:generateTypeScriptContracts`)
  3. **Running the server** — in-memory (`:chronotrace-server:run`), file mode (`CHRONOTRACE_STORAGE_MODE=file`), ClickHouse mode (`CHRONOTRACE_STORAGE_MODE=clickhouse`)
  4. **Running tests** — `./gradlew test`, Docker integration tests (`docker compose up -d && ./gradlew :chronotrace-server:test --tests "*IntegrationTest"`)
  5. **Docker Compose setup** — Service descriptions (chronotrace-server, clickhouse, valkey), ports, volumes
  6. **Publishing to Maven Local** — `:sdk-kmp:publishToMavenLocal`
  7. **TypeScript SDK build** — `npm install && npm run build` in `sdk-ts/`

### Implementation Details

Draw from:
- README.md quick-start section (existing good content to preserve/expand)
- gradle.properties for version constants
- docker-compose.yml for service names and ports
- ServerModule.kt for environment variable documentation
- CI workflow for build/test commands

### Validation

- docs/install.md exists and is non-empty
- All commands in the doc are syntactically valid shell commands
- Doc builds without errors (no broken markdown)

### Dependencies
- Blocked by: Phase 1
- Blocks: Phase 3

---

## Phase 3: SDK Public API Reference

### Objective
Create docs/sdk-api.md documenting the Kotlin SDK public API surface with correct signatures.

### Agent: technical-writer
### Parallel: No

### Files to Create

- `docs/sdk-api.md` — Kotlin SDK public API reference with sections:
  1. **Initialization**: `ChronoTrace.init(config: ChronoConfig)`, `ChronoTrace.shutdown()`
  2. **Logging**: `ChronoLogger.trace(msg, fields)`, `debug`, `info`, `warn`, `error`, `fatal` — all `suspend` functions
  3. **Spans/Traces**: `ChronoTrace.startSpan(name: String): SpanHandle`, `withTrace(name) { }`, `withSpan(name) { }`, `SpanHandle.end()`
  4. **Context Propagation**: `ChronoTrace.injectHeaders(carrier: MutableMap<String, String>, context: ChronoSpanContext?)`, `ChronoTrace.extractHeaders(carrier: Map<String, String>): ChronoSpanContext?`
  5. **Configuration**: `ChronoConfig`, `CaptureConfig`, `BufferConfig`, `OverflowStrategy` enum values
  6. **Runtime Health**: `ChronoTrace.runtimeHealth(): RuntimeHealth`
  7. **Transport**: `ChronoTransport` interface, `NoopTransport`, `RecordingTransport`

### Implementation Details

Read actual source files to get exact signatures:
- `sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoTrace.kt`
- `sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoLogger.kt`
- `sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoTransport.kt`
- `sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoModels.kt`

Each API entry should include: function signature, parameter types, return type, description, and a minimal code example.

### Validation

- docs/sdk-api.md exists and covers all 7 sections
- All function signatures match actual source code
- Code examples use correct parameter names from source

### Dependencies
- Blocked by: Phase 2
- Blocks: Phase 4

---

## Phase 4: Review & Link Checking

### Objective
Verify internal doc links, cross-reference new docs from README, and ensure code examples match actual API.

### Agent: technical-writer
### Parallel: No

### Files to Modify

- `README.md` — Add references to new docs/install.md and docs/sdk-api.md in relevant sections
- `docs/user-manual.md` — Verify internal section links ([Section Name](#anchor)) resolve correctly

### Implementation Details

1. Read README.md and check all relative links to docs/ files
2. Read docs/user-manual.md and verify all internal markdown links work
3. Verify code examples in docs/ match actual API (parameter names, types)
4. Ensure docs/install.md and docs/sdk-api.md are listed in any table of contents

### Validation

- All internal doc links resolve (no broken relative links)
- Code examples in docs/ match actual function signatures

### Dependencies
- Blocked by: Phase 3
- Blocks: None

---

## File Inventory

| # | File | Phase | Purpose |
|---|------|-------|---------|
| 1 | `README.md` | 1, 4 | Fix stale versions; add doc references |
| 2 | `docs/user-manual.md` | 1, 4 | Fix stale versions; verify links |
| 3 | `docs/deployment-guide.md` | 1 | Audit and fix stale references |
| 4 | `docs/install.md` | 2 | New build/install guide |
| 5 | `docs/sdk-api.md` | 3 | New SDK API reference |

## Risk Classification

| Phase | Risk | Rationale |
|-------|------|-----------|
| 1 | LOW | Text substitution only; no structural changes |
| 2 | LOW | New file creation; existing patterns easy to follow |
| 3 | MEDIUM | Must read actual source to get signatures right; minor risk of signature mismatch |
| 4 | LOW | Link checking and cross-referencing only |

## Execution Profile

```
Execution Profile:
- Total phases: 4
- Parallelizable phases: 0 (all sequential — each phase informs the next)
- Sequential-only phases: 4
- Estimated parallel wall time: N/A (sequential only)
- Estimated sequential wall time: ~20-30 minutes

Note: All phases use the technical-writer agent sequentially.
```

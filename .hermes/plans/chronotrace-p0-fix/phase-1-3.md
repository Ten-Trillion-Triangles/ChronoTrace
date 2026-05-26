# Phase 1-3: Analysis and Evaluation — chronotrace-p0-fix

## Phase 1 Scope
Phase 1 (Memorise) establishes the goal and plan directory. It:
- Reads and internalizes the GOAL: "Get ChronoTrace to 100% ship-ready, resolving all production readiness gaps"
- Extracts the goal_slug: `chronotrace-p0-fix`
- Creates the plan directory at `.hermes/plans/chronotrace-p0-fix/`
- Stores the full request in memory with tags: `["kanban-lead-architect", "active-task", "chronotrace-p0-fix"]`
- Tier 3 = HIGH complexity, multiple workstreams

## Phase 2 Scope
Phase 2 (Decompose) breaks the request into components and steps. It:
- Identifies 33 functional requirements and 8 non-functional requirements
- Maps 6 components: KMP SDK, Server, K2 Plugin, Contract, TS SDK, schema migration
- Creates MASTER.md with Steps section containing checkboxes:
  - C1: Implement HttpTransport for KMP SDK
  - C2: Verify Docker-dependent tests with DOCKER_AVAILABLE=true
  - C3: Add graceful degradation to compiler plugin
  - C4: TLS (deferred to v0.2.0)
- Documents P0 gaps: KMP HTTP Transport (BROKEN), ClickHouse E2E Tests (PARTIAL), Compiler Plugin Recovery (BROKEN)

## Phase 3 Scope
Phase 3 (Evaluate) confirms tier and sets complexity:
- Confirmed Tier 3 — Elaborate: Multiple workstreams, strict conformance, high-stakes
- Complexity: HIGH
- Phase 3A is REQUIRED for sub-plan generation (Tier 3 = plan needed)
- Phase 3A creates 5 granular sub-plan files for downstream phases

## Granular Steps

### Step 1: Verify plan directory exists
```bash
ls -la /home/cage/Desktop/Workspaces/ChronoTrace/.hermes/plans/chronotrace-p0-fix/
# Expected: MASTER.md exists
```

### Step 2: Read MASTER.md
- Open `.hermes/plans/chronotrace-p0-fix/MASTER.md`
- Extract: requirements (P0/P1), components, steps (C1-C4), tools, P0 gap status

### Step 3: Identify P0 gaps from MASTER.md
- KMP HTTP Transport: BROKEN — no HttpTransport implementation
- ClickHouse E2E Tests: PARTIAL — guarded, not run in CI
- Compiler Plugin Recovery: BROKEN — no graceful degradation

### Step 4: Map steps to actual file targets
- C1 → `sdk-kmp/commonMain/.../transport/HttpTransport.kt`
- C2 → `chronotrace-server/src/test/` (ClickHouseStorageIntegrationTest, RetentionLifecycleIntegrationTest, SchemaMigrationTest)
- C3 → `chronotrace-kotlin-plugin/ChronoTraceIrGenerationExtension.kt`
- C4 → deferred (nginx/caddy reverse proxy, not v0.1.0 scope)

### Step 5: Identify verification criteria
- C1 verification: MockWebServer tests, HTTP POST to /api/v1/ingest, 503 retry loop
- C2 verification: `DOCKER_AVAILABLE=true ./gradlew test` — all Docker-gated tests pass
- C3 verification: plugin disabled → warning log → build succeeds (not fails)

## File Locations
- Plan file: `/home/cage/Desktop/Workspaces/ChronoTrace/.hermes/plans/chronotrace-p0-fix/MASTER.md`
- Sub-plans: `/home/cage/Desktop/Workspaces/ChronoTrace/.hermes/plans/chronotrace-p0-fix/phase-*.md`
- Workspace: `/home/cage/.hermes/kanban/boards/chronotrace-p0-fix/workspaces/t_8f055b48/`

## P0 Gap Details (from MASTER.md)

### Gap 1: KMP HTTP Transport (BLOCKING)
- Path: `sdk-kmp/commonMain/.../transport/`
- Problem: ChronoTransport interface exists with NoopTransport and RecordingTransport (test doubles), but no real HttpTransport
- Required: HTTP POST to configurable base URL, X-Api-Key header, retry on 503 with exponential backoff
- Spec: retry = 100ms * 2^attempt, max 3 attempts

### Gap 2: ClickHouse E2E Tests (BLOCKING)
- Path: `chronotrace-server/src/test/`
- Classes: ClickHouseStorageIntegrationTest (port 19999), RetentionLifecycleIntegrationTest, SchemaMigrationTest
- Problem: @DisabledIfEnvironmentVariable(named="DOCKER_AVAILABLE", matches="false") guards all tests
- Required: DOCKER_AVAILABLE=true in test runs, schema init, ingest/query flows, async purge state machine

### Gap 3: Compiler Plugin Recovery (BLOCKING)
- Path: `chronotrace-kotlin-plugin/ChronoTraceIrGenerationExtension.kt`
- Problem: Plugin breaks build if it fails or is disabled — no graceful degradation
- Required: try/catch boundary, warning log on failure, skip instrumentation, diagnostic for users

### Gap 4: TLS (NOT P0)
- Status: NOT PROVIDED — reverse proxy required
- Defer to v0.2.0 unless explicitly blocking
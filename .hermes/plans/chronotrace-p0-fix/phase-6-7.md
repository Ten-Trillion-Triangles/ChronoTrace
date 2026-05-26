# Phase 6-7: Execution and Verification — chronotrace-p0-fix

## Phase 6 Scope
Phase 6 (Execute) is the main implementation phase. The lead architect (Phase 6 worker) manages subagents that execute each task. Key principles:
- No free passes: Wrong or incomplete work must be fixed
- Revert if necessary: Fundamentally wrong work gets reverted and re-executed
- Stay on plan: Subagents don't drift from the plan
- Manage to completion: Not fire-and-forget

**CRITICAL: Tasks execute ONE AT A TIME. Task N fully completes before Task N+1 begins. No parallel dispatch.**

## Phase 7 Scope
Phase 7 (Verify) runs comprehensive verification after all Phase 6 tasks are complete:
- Full test run with DOCKER_AVAILABLE=true
- Integration verification
- Spec compliance check
- Bug scan
- Code quality audit
- Performance check
- Security check

## Phase 6 Execution Model

### Task execution order (from MASTER.md C1 → C2 → C3 → C4):
```
C1: Implement HttpTransport for KMP SDK
C2: Verify Docker-dependent tests
C3: Add graceful degradation to compiler plugin
C4: TLS (deferred to v0.2.0)
```

### C1: HttpTransport Implementation
**Task:** Implement `HttpTransport` for KMP SDK at `sdk-kmp/commonMain/.../transport/HttpTransport.kt`

**Acceptance Criteria:**
- HTTP POST to configurable base URL `/api/v1/ingest`
- Retry on 503 with exponential backoff (100ms * 2^attempt, max 3)
- API key via X-Api-Key header
- Configurable base URL

**Subagent command:**
```python
delegate_task(
    goal=f"""TASK: Implement HttpTransport for KMP SDK
GOAL: Get ChronoTrace to 100% ship-ready
CONTEXT:
- File: sdk-kmp/commonMain/.../transport/HttpTransport.kt
- Interface: ChronoTransport (already exists with NoopTransport, RecordingTransport)
- Requirements: HTTP POST to configurable base URL, retry on 503 with exp backoff (100ms*2^attempt, max3), X-Api-Key header
- Test with MockWebServer
- Use TDD: write failing test first, then implement
Execute. Report what was done, what files created/modified.""",
    max_iterations=5000,
    toolsets=["terminal", "file", "skills"]
)
```

### C2: Docker-dependent tests
**Task:** Verify Docker-dependent tests run with `DOCKER_AVAILABLE=true`

**Classes:**
- `ClickHouseStorageIntegrationTest` (port 19999)
- `RetentionLifecycleIntegrationTest` (async purge state machine: ACCEPTED→RUNNING→COMPLETED)
- `SchemaMigrationTest`

**Subagent command:**
```python
delegate_task(
    goal=f"""TASK: Verify Docker-dependent tests pass with DOCKER_AVAILABLE=true
GOAL: Get ChronoTrace to 100% ship-ready
CONTEXT:
- Run: DOCKER_AVAILABLE=true ./gradlew :chronotrace-server:test
- Classes: ClickHouseStorageIntegrationTest, RetentionLifecycleIntegrationTest, SchemaMigrationTest
- Fix any failures found
- Verify schema init works, ingest/query flows work, async purge state machine works
Execute. Report what was done, what files created/modified.""",
    max_iterations=5000,
    toolsets=["terminal", "file", "skills"]
)
```

### C3: Compiler plugin graceful degradation
**Task:** Add graceful degradation to `ChronoTraceIrGenerationExtension.kt`

**Acceptance Criteria:**
- Wrap IR extension execution in try/catch
- On failure: log warning, skip instrumentation, don't break build
- Add ChronoTracePluginDiagnostic for user-facing error messages

**Subagent command:**
```python
delegate_task(
    goal=f"""TASK: Add graceful degradation to compiler plugin
GOAL: Get ChronoTrace to 100% ship-ready
CONTEXT:
- File: chronotrace-kotlin-plugin/ChronoTraceIrGenerationExtension.kt
- Problem: Plugin breaks build if it fails or is disabled — no graceful degradation
- Required: try/catch boundary, warning log on failure, skip instrumentation, diagnostic for users
- Build warning instead of hard failure for recoverable errors
- Use TDD: write failing test first, then implement
Execute. Report what was done, what files created/modified.""",
    max_iterations=5000,
    toolsets=["terminal", "file", "skills"]
)
```

### C4: TLS (deferred)
- Status: deferred to v0.2.0
- No subagent required for v0.1.0 scope

## Phase 7 Verification Checkpoints

### 1. FULL TEST RUN
```bash
cd /home/cage/Desktop/Workspaces/ChronoTrace
DOCKER_AVAILABLE=true ./gradlew :chronotrace-server:test --tests "ClickHouseStorageIntegrationTest" --tests "RetentionLifecycleIntegrationTest" --tests "SchemaMigrationTest" -v --tb=short
```
All tests must pass. If any fail → Phase 10 will fail.

### 2. INTEGRATION VERIFICATION
- Run application and verify all components work together end-to-end
- Test main user flows: ingest via KMP SDK → server → ClickHouse
- Verify external integrations: Valkey (caching), ClickHouse (storage)

### 3. SPEC COMPLIANCE CHECK
From MASTER.md:
- C1: HttpTransport sends HTTP POST, retries on 503, X-Api-Key header → VERIFIED?
- C2: DOCKER_AVAILABLE=true tests pass, schema init, async purge → VERIFIED?
- C3: Plugin disabled → warning → build succeeds → VERIFIED?

### 4. BUG SCAN
- Run with edge case inputs
- Check for crashes, exceptions, error messages

### 5. CODE QUALITY AUDIT
- camelCase only (no snake_case in Kotlin/C-family)
- KDoc on all public/protected APIs
- Builder patterns for config objects
- No @ts-ignore, no raw console.log/printStackTrace

### 6. PERFORMANCE CHECK
- Application starts within reasonable time
- No obvious bottlenecks in critical paths

### 7. SECURITY CHECK
- No hardcoded secrets or API keys
- Authentication works correctly
- Input validation prevents injection

## Granular Steps

### Step 1: Read MASTER.md steps
```bash
cat /home/cage/Desktop/Workspaces/ChronoTrace/.hermes/plans/chronotrace-p0-fix/MASTER.md
# Extract C1, C2, C3, C4 tasks
```

### Step 2: Execute C1 (HttpTransport)
```python
result = delegate_task(goal="...", max_iterations=5000, toolsets=["terminal", "file", "skills"])
# Verify: grep "HttpTransport" sdk-kmp/ — should find implementation
# Verify: test with MockWebServer
```

### Step 3: Execute C2 (Docker tests)
```python
result = delegate_task(goal="...", max_iterations=5000, toolsets=["terminal", "file", "skills"])
# Verify: DOCKER_AVAILABLE=true ./gradlew test — all pass
```

### Step 4: Execute C3 (Plugin graceful degradation)
```python
result = delegate_task(goal="...", max_iterations=5000, toolsets=["terminal", "file", "skills"])
# Verify: plugin disabled → build warning, not failure
```

### Step 5: Phase 7 verification
```python
# Run all verification checkpoints listed above
# Document results in memory
```

### Step 6: Update MASTER.md Phase Status
```markdown
- Phase 6: COMPLETE
- Phase 7: COMPLETE
```

## Wiring Verification (BOARD-GUARDIAN SELF-CHECK)

Before completing Phase 6, verify:
```bash
git diff --stat  # shows .kt source files changed, not just test files
grep "expected-function" sourcefile  # finds implementation
grep "expected-call-site" sourcefile  # finds call site (wiring correct)
```

If only test files changed: FRAUD — archive and recover.
If function exists but call site missing: WIRED-BUT-NOT-CALLED — archive and recover.
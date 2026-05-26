# Phase 4-5: Skills and Review — chronotrace-p0-fix

## Phase 4 Scope
Phase 4 (Load Skills) determines which skills to load based on tier and goal, loads them, and passes them forward to Phase 6. It:
- Determines skills required for Tier 3 (this task is Tier 3)
- Loads skills via `skill_view(name=skill)` for each skill required
- Documents loaded skills in memory AND in kanban_complete metadata
- Passes skills to Phase 6 card creation via `--skill` flags

## Phase 5 Scope
Phase 5 (Hostile Review) runs 6 reviewers against the plan. Each reviewer:
- Spawns as a subagent via `delegate_task(max_iterations=5000, toolsets=["terminal","file","skills"])`
- Reviews for its focus area
- Returns PASS or BLOCK with specific issues

Mode determines branch:
- **AUTO mode**: AUTO-FIX LOOP — keep fixing until reviewer passes or 5 attempts exhausted
- **INTERACTIVE mode**: STOP on BLOCK → human decision (RETRY/SKIP/FAIL PHASE)

## Phase 4 Skills to Load (Tier 3)

Based on the goal (ChronoTrace production readiness) and Tier 3 requirements:

### Always Required (TTT standard):
- `writing-plans` — for sub-plan generation and implementation planning
- `subagent-driven-development` — for Phase 6 execution and Phase 5 reviewer spawning
- `test-driven-development` — for writing tests before implementing C1, C2, C3
- `ttt-code-styler` — for Phase 9 style enforcement (camelCase, KDoc, builder patterns, no snake_case)

### Domain-Specific:
- `systematic-debugging` — for ClickHouse integration test failures
- `requesting-code-review` — for Phase 5 apex-standards-enforcer and PR workflow

### Skills loaded for this task (from Phase 4):
```
writing-plans
subagent-driven-development
test-driven-development
ttt-code-styler
systematic-debugging
requesting-code-review
```

## Phase 5 Hostile Review Procedure

### Step 1: Validate sub-plan files exist before reviewers run
All 5 sub-plan files must exist and have ≥500 chars:
- `phase-1-3.md` — phases 1-3 scope and steps
- `phase-4-5.md` — phases 4-5 scope and hostile review procedure
- `phase-6-7.md` — phases 6-7 execution and verification
- `phase-8-9.md` — phases 8-9 summarisation and style
- `phase-10-11.md` — phases 10-11 compliance and human review

### Step 2: Add hostile review section to plan file
Before spawning reviewers, add to MASTER.md:
```markdown
## Phase 5: Hostile Review

### Reviewer Assignments
[6 reviewers listed with focus areas]
```

### Step 3: Spawn 6 reviewers SEQUENTIALLY (not parallel)

**Requirements Auditor** (requirements completeness)
- Focus: Are all P0 requirements captured? Any implied requirements missing? Are acceptance criteria testable?
- C1 acceptance: HttpTransport sends HTTP POST, retries on 503, uses X-Api-Key header
- C2 acceptance: DOCKER_AVAILABLE=true tests pass, ClickHouse schema init, async purge state machine
- C3 acceptance: Plugin disabled → warning → build succeeds, no hard failure

**Architecture Critic** (architecture soundness)
- Focus: Is the proposed architecture sound? Any bottlenecks, circular deps, over-engineering?
- KMP SDK: transport interface + NoopTransport + RecordingTransport + HttpTransport
- Server: ChronoStore + Valkey + ClickHouse + MCP tooling
- Plugin: ChronoTraceIrGenerationExtension with graceful degradation boundary

**Edge Case Hunter** (failure modes and edge cases)
- Focus: What are the failure modes? What happens at boundaries? Error handling complete?
- C1 edge cases: server returns 503 3x → then what? Invalid JSON response? Network timeout?
- C2 edge cases: ClickHouse unavailable on test start? Schema version mismatch? Retention async job stuck?
- C3 edge cases: plugin throws Exception vs Error? Plugin disabled via compiler flags vs build failure?

**Security Reviewer** (auth, validation, secrets, attack surface)
- Focus: Authentication, authorisation, input validation, secrets management, attack surface
- X-Api-Key header on HttpTransport, rate limiting, audit logging
- API key not logged in plain text, proper error messages (no stack traces to client)

**Test Coverage Analyst** (test coverage and regression risk)
- Focus: Are core paths covered? Are there integration tests? What would a regression break?
- C1 needs: MockWebServer tests for HTTP POST, 503 retry, exponential backoff
- C2 needs: Integration tests for ClickHouseStorageIntegrationTest, RetentionLifecycleIntegrationTest, SchemaMigrationTest
- C3 needs: Test with plugin disabled, plugin throws Exception, build succeeds with warning

**Apex Standards Enforcer** (snake_case, KDoc, builder patterns, TDD)
- Focus: snake_case violations, missing KDoc, non-builder configs, descriptive names, TDD compliance
- camelCase only (no snake_case in Kotlin/C-family)
- KDoc on all public/protected APIs
- Builder pattern for config objects (.setProperty(value))
- TDD: write failing test first, then implement
- No @ts-ignore, no raw console.log/printStackTrace

### Step 4: AUTO-FIX LOOP (AUTO mode only)
For each reviewer that BLOCKs:
1. Attempt 1: Fix specific issues raised → re-run reviewer
2. Attempt 2: If BLOCK again → fix again → re-run
3. ...
4. Attempt 5: If BLOCK again → STOP. Document failure. Phase 10 will fail → Phase 12 rework.

If reviewer PASSes → move to next reviewer.

### Step 5: Write [REVIEW_PASSED] marker
After all 6 reviewers PASS (or exhausted all attempts):
```markdown
[REVIEW_PASSED]
```

### Step 6: Verification checklist
- [ ] Plan file has `## Phase 5: Hostile Review` section header
- [ ] All 5 sub-plan files exist
- [ ] Each sub-plan ≥500 characters
- [ ] Section body ≥200 characters of substantive analysis per reviewer
- [ ] Review text references plan-specific content
- [ ] [REVIEW_PASSED] marker written (exact form, not [PASS] or "reviewed and approved")
- [ ] All 6 reviewers have PASS or documented exhaustion

## Granular Steps

### Step 1 (Phase 4): Determine skills to load
```python
skills_tier3 = [
    "writing-plans",           # always
    "subagent-driven-development",  # always
    "test-driven-development",     # always for Tier 2/3
    "ttt-code-styler",              # always for code tasks
    "systematic-debugging",         # domain-specific: ClickHouse debug
    "requesting-code-review",       # domain-specific: code review
]
```

### Step 2 (Phase 4): Load each skill
```python
for skill in skills_tier3:
    skill_view(name=skill)
```

### Step 3 (Phase 4): Store in memory
```python
hindsight_retain(
    content="LOADED SKILLS: writing-plans, subagent-driven-development, test-driven-development, ttt-code-styler, systematic-debugging, requesting-code-review",
    context="kanban-lead-architect: skills loaded for chronotrace-p0-fix",
    tags=["kanban-lead-architect", "skills-loaded", "chronotrace-p0-fix"]
)
```

### Step 4 (Phase 4): Pass skills to Phase 6 card
```bash
hermes kanban create "[LA-6] Phase 6: Execute — chronotrace-p0-fix" \
  --assignee default \
  --skill kanban-lead-architect \
  --skill writing-plans \
  --skill subagent-driven-development \
  --skill test-driven-development \
  --skill ttt-code-styler \
  --skill systematic-debugging \
  --skill requesting-code-review \
  --parent <LA-5_task_id> \
  --body "..."
```

### Step 5 (Phase 5): Validate sub-plan files
```bash
wc -c .hermes/plans/chronotrace-p0-fix/phase-*.md
# All 5 files must exist, each ≥500 chars
```

### Step 6 (Phase 5): Spawn each reviewer
```python
reviewers = [
    ("requirements-auditor", "requirements completeness"),
    ("architecture-critic", "architecture soundness"),
    ("edge-case-hunter", "failure modes and edge cases"),
    ("security-reviewer", "auth, validation, secrets, attack surface"),
    ("test-coverage-analyst", "test coverage and regression risk"),
    ("apex-standards-enforcer", "snake_case, KDoc, builder patterns, TDD"),
]

for reviewer_name, focus in reviewers:
    result = delegate_task(
        goal=f"Review chronotrace-p0-fix plan for {focus}. Plan file: .hermes/plans/chronotrace-p0-fix/MASTER.md. Return PASS if acceptable. Return BLOCK with specific issues if not.",
        max_iterations=5000,
        toolsets=["terminal", "file", "skills"]
    )
    # Handle PASS or BLOCK per mode (AUTO-FIX LOOP or INTERACTIVE clarify)
```
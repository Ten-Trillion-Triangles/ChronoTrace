# Phase 10-11: Compliance and Human Review — chronotrace-p0-fix

## Phase 10 Scope
Phase 10 (Automated Compliance) runs 6 systematic checks against the goal, plan file, and created work. ALL must pass for this phase to pass. This is the gate.

**Mode:** Automated (runs in both AUTO and INTERACTIVE modes)

**Pass → Phase 11 created, blocked with `review-required`**
**Fail → Phase 12 (Rework Controller) created, blocked with failure details**

## Phase 11 Scope
Phase 11 (Human Final Review) is only created after Phase 10 passes. It always blocks — no exceptions. Human reviewer:
1. **APPROVE**: Run `hermes kanban unblock <task_id>` → marks done, work accepted
2. **SEND BACK**: Comment `SEND BACK TO PHASE N` with reason → triggers Phase 12 rework

## Phase 10 Automated Compliance Checks

### CHECK 1 — GOAL COVERAGE
- Read GOAL: "Get ChronoTrace to 100% ship-ready, resolving all production readiness gaps"
- Check each P0 requirement against CREATED_FILES:
  - C1 (HttpTransport): File exists at `sdk-kmp/commonMain/.../transport/HttpTransport.kt`? Sends HTTP POST? Retries on 503? Uses X-Api-Key?
  - C2 (Docker tests): Tests run with DOCKER_AVAILABLE=true? All pass?
  - C3 (Plugin recovery): Graceful degradation implemented? Build succeeds when plugin disabled?
- Requirements skipped or partially implemented? → FAIL
- Features added not in goal (scope creep)? → FAIL
**Result:** PASS or FAIL with specific message

### CHECK 2 — PLAN COMPLIANCE
- Read MASTER.md at `.hermes/plans/chronotrace-p0-fix/MASTER.md`
- Every task in Steps section (C1, C2, C3, C4): completed or skipped with reason?
- C4 (TLS) deferred to v0.2.0 — valid reason documented?
- Skipped tasks: valid reason documented? → If not → FAIL
**Result:** PASS or FAIL with specific message

### CHECK 3 — PLAN REQUIREMENTS COMPLIANCE
Parse PLAN_REQUIREMENTS: "Get ChronoTrace to 100% ship-ready, resolving all production readiness gaps"
- "100% ship-ready" → all P0 gaps closed, all tests pass → If not → FAIL
- "production readiness gaps" → P0 gaps: KMP HTTP Transport, ClickHouse E2E Tests, Compiler Plugin Recovery → If any open → FAIL
- Tier 3 → complexity HIGH → sub-plans generated → If not → FAIL
- "plan file" → MASTER.md exists and is complete → If not → FAIL
**Result:** PASS or FAIL with specific message

### CHECK 4 — PHASE EVIDENCE
Verify each phase has required evidence:
- Phase 1: memory entry with "kanban-lead-architect" + "active-task" + "chronotrace-p0-fix"
- Phase 2: MASTER.md exists with decomposition (requirements, components, steps)
- Phase 3: MASTER.md has tier evaluation section (Tier 3, HIGH complexity)
- Phase 4: memory entry with "skills-loaded" + "chronotrace-p0-fix"
- Phase 5: [REVIEW_PASSED] marker in MASTER.md, ≥200 chars hostile review section
- Phase 6: all plan tasks checked off (C1, C2, C3 marked complete)
- Phase 7: tests pass (pytest/Docker test output)
- Phase 8: memory entry with "completed" + "chronotrace-p0-fix"
- Phase 9: memory entry with "styling-complete" + "chronotrace-p0-fix"
**Result:** PASS or FAIL with specific message (note which phase lacks evidence)

### CHECK 5 — TEST COVERAGE
- `sdk-kmp/` has test files for HttpTransport?
- `chronotrace-server/src/test/` has ClickHouseStorageIntegrationTest, RetentionLifecycleIntegrationTest, SchemaMigrationTest?
- Integration tests exist for component interactions?
- All tests pass with no regressions?
**Result:** PASS or FAIL with specific message

### CHECK 6 — CODE QUALITY
- No snake_case in C-family files (Kotlin)? → If found → FAIL
- No missing KDoc on public/protected APIs? → If missing → FAIL
- No @ts-ignore or equivalent suppressions? → If found → FAIL
- No exposed secrets or hardcoded credentials? → If found → FAIL
**Result:** PASS or FAIL with specific message

## Phase 10 Step-by-Step

### Step 1: Run all 6 checks
Document each check result with PASS/FAIL and specific message.

### Step 2: Determine overall result
- ALL 6 checks pass → overall PASS
- ANY check fails → overall FAIL

### Step 3: ON PASS

**Kanban complete Phase 10:**
```python
kanban_complete(
    task_id="{p10_task_id}",
    summary="Phase 10 PASSED. All 6 compliance checks passed.",
    metadata={
        "phase": 10,
        "goal_slug": "chronotrace-p0-fix",
        "result": "PASS",
        "checks": {
            "goal_coverage": "PASS",
            "plan_compliance": "PASS",
            "plan_requirements": "PASS",
            "phase_evidence": "PASS",
            "test_coverage": "PASS",
            "code_quality": "PASS"
        },
        "rework_cycle": null
    }
)
```

**Post structured handoff comment to Phase 11:**
```python
kanban_comment(
    task_id="{p11_task_id}",
    body=f"""PHASE 11 HUMAN FINAL REVIEW — chronotrace-p0-fix

AUTOMATED COMPLIANCE: Phase 10 PASSED — all 6 checks cleared.

GOAL: Get ChronoTrace to 100% ship-ready, resolving all production readiness gaps
TIER: 3

PHASE CHAIN:
Phase 1 (Memorise): COMPLETE
Phase 2 (Decompose): COMPLETE
Phase 3 (Evaluate): COMPLETE — Tier 3, HIGH complexity
Phase 4 (Load Skills): COMPLETE
Phase 5 (Hostile Review): COMPLETE — [REVIEW_PASSED] in plan file
Phase 6 (Execute): COMPLETE
Phase 7 (Verify): COMPLETE
Phase 8 (Summarize): COMPLETE
Phase 9 (Style): COMPLETE
Phase 10 (Automated Compliance): PASSED

EVIDENCE:
FILES CREATED: {list}
TEST RESULTS: {pass_count}/{total} passing
PLAN FILE: .hermes/plans/chronotrace-p0-fix/MASTER.md

BOARD-GUARDIAN VERDICT: Compliant. Ready for human final review.

YOUR DECISION:
1. APPROVE: Run `hermes kanban unblock {p11_task_id}` — marks done, work is accepted.
2. SEND BACK: Comment `SEND BACK TO PHASE N` with reason — triggers Phase 12 rework.

This card is in the REVIEW column. The board-guardian verified all 6 automated checks passed before surfacing it to you.
"""
)
```

**Block Phase 11 with `review-required`:**
```python
kanban_block(
    task_id="{p11_task_id}",
    reason="review-required: Phase 10 automated compliance PASSED. All 6 checks verified. Human final review required before work is accepted. APPROVE or SEND BACK TO PHASE N."
)
```

### Step 4: ON FAIL

**Post failure comment:**
```python
kanban_comment(
    task_id="{p10_task_id}",
    body=f"""PHASE 10 FAILED — AUTOMATED COMPLIANCE

Check results:
- goal_coverage: {result1}
- plan_compliance: {result2}
- plan_requirements: {result3}
- phase_evidence: {result4}
- test_coverage: {result5}
- code_quality: {result6}

Failed checks: {list of failed check names}
First failure: {first_failed_check} — {failure_message}

Phase 12 (Rework Controller) will now be created to handle regeneration.
"""
)
```

**Block Phase 10:**
```python
kanban_block(
    task_id="{p10_task_id}",
    reason=f"Phase 10 FAILED. Check '{first_failed_check}' failed: {failure_message}. Phase 12 (Rework Controller) is being created."
)
```

## Phase 11 Human Final Review Procedure

### Step 1: Compile phase completion summary
- Phase 1-10 all complete
- All P0 gaps closed (C1, C2, C3)
- Tests pass
- Plan file complete
- Compliance verified

### Step 2: Card lands in REVIEW column via `review-required`
The Phase 10 worker called `kanban_block` with `reason="review-required: ..."` — this routes the card to the REVIEW column.

### Step 3: Human reviewer reads the structured handoff comment (posted by Phase 10)

### Step 4: Human makes decision
1. **APPROVE**: `hermes kanban unblock {p11_task_id}` → card moves to done, work accepted
2. **SEND BACK**: Comment `SEND BACK TO PHASE N` with reason → Phase 12 rework triggered

## Granular Steps

### Step 1: Run 6 automated checks
```python
checks = {
    "goal_coverage": verify_goal_coverage(),
    "plan_compliance": verify_plan_compliance(),
    "plan_requirements": verify_plan_requirements(),
    "phase_evidence": verify_phase_evidence(),
    "test_coverage": verify_test_coverage(),
    "code_quality": verify_code_quality(),
}
all_pass = all(v == "PASS" for v in checks.values())
```

### Step 2: On PASS → Phase 11 created and blocked
```python
# Phase 11 card was pre-created during board setup
# Phase 10 worker only: completes Phase 10, comments on Phase 11, blocks Phase 11
```

### Step 3: On FAIL → Phase 12 created
```python
kanban_create(
    title="[LA-12] Phase 12: Rework Controller — chronotrace-p0-fix",
    assignee="default",
    body="""REWORK CONTROLLER — Phase 10 FAILED
...""",
)
```

## Completion Criteria

**Phase 10:**
- PASS: All 6 checks pass → Phase 11 card created
- FAIL: Document all failures → Phase 12 rework triggered

**Phase 11:**
- Card was pre-created by agent during board setup
- Blocked by Phase 10 worker on PASS
- Human approves or sends back
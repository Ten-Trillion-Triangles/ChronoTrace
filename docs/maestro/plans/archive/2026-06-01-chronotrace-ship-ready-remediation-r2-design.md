---
title: "ChronoTrace Ship-Ready Remediation — Round 2 Verification"
created: "2026-06-01T18:12:00Z"
status: "draft"
authors: ["TechLead", "User"]
type: "design"
design_depth: "quick"
task_complexity: "complex"
---

# ChronoTrace Ship-Ready Remediation — Round 2 Verification

## Problem Statement

The previous maestro orchestration session (`2026-06-01-chronotrace-ship-ready-remediation`) completed a 12-phase plan addressing 4 critical + 11 high + 12 medium + 25 low findings. The session was archived. The user has re-invoked `/maestro:orchestrate` with the intent: "Lets fix up and address every single issue found, build using TDD, test, prove fixed, and review if we've reached ship ready yet."

**Goal of Round 2**: Verify the previous session's work is actually persisted AND ship-ready, identify any gaps the previous session missed, and remediate them with TDD.

## Approach

**Selected approach**: Re-verify, then re-audit, then close any remaining gaps.

1. **Verify state** (done in setup): all hygiene dirs gone, clickhouse-user-config.xml restored, chronotrace-ir-core has 3 source files, sdk-ts dist rebuilt (9 scheduleReconnect occurrences), TLS wiring in place, TDD test files exist.
2. **Run the ship-readiness audit** (`chronotrace-ship-readiness-audit` workflow) to find the CURRENT state of issues.
3. **For each remaining finding**: write a failing test → apply the fix → verify green.
4. **Run the re-audit** to confirm SHIP-READY.

**Rejected alternatives**:

- **Restart from scratch**: throws away completed work. Risky and wasteful. The 12-phase plan was approved.
- **Trust the previous archive blindly**: would violate "fix every single issue" intent. The user explicitly said "review if we've reached ship ready yet" — re-audit is required.

## Risks

| Risk | Severity | Mitigation |
|------|----------|------------|
| Pre-existing test failures persist | MEDIUM | AuthTest 404 + ChronoTraceJvmE2eTest capture-depth — investigate root cause first |
| sdk-ts dist incomplete | LOW | Already verified 9 scheduleReconnect occurrences in webSocketTransport.js |
| chronotrace-ir-core variant refactor not done | MEDIUM | Documented deferred to 1.1.0; current 3 source files compile and tests pass |
| Re-audit returns NOT-SHIP-READY | MEDIUM | Use findings to drive a focused remediation plan |

## Success Criteria

1. `./gradlew :sdk-kmp:publishToMavenLocal :sdk-kmp:test :chronotrace-kotlin-plugin:test :chronotrace-kotlin-plugin-gradle:test :chronotrace-server:test :chronotrace-contract:test` all green.
2. `cd sdk-ts && npm test && npm run build` all green.
3. Pre-existing test failures (AuthTest 404, ChronoTraceJvmE2eTest capture-depth) are fixed.
4. Re-audit verdict: **SHIP-READY**.

# Phase 1: Foundation And Contracts

Last reviewed: 2026-03-11

## Goal

Turn the current baseline into a stable foundation with one canonical contract surface and one unambiguous SDK/server architecture.

## Current Status

Status: `Implemented`

### Already Landed

- Canonical shared Kotlin contract module is the wire-facing source of truth.
- Generated TS contract types are produced from the shared contract module and verified for drift.
- TS public API has been consolidated to one surface around `ChronoTrace` and `ChronoLogger`.
- KMP emits the same canonical wire-facing contract types as the server and TS SDK.
- TS artifact policy for `dist` and `node_modules` is documented and enforced through ignore rules.

### Remaining To Complete This Phase

- No new implementation work is required for this phase.
- Only maintenance updates are expected if later phases force contract expansion or public API clarification.

### Code Entry Points

- `chronotrace-contract/src/commonMain/kotlin/org/chronotrace/contract/ChronoContracts.kt`
- `sdk-ts/src/generated/contracts.ts`
- `sdk-kmp/src/commonMain/kotlin/com/chronotrace/sdk/ChronoRuntime.kt`

### Verification To Re-run

- `./gradlew :chronotrace-contract:verifyTypeScriptContracts`
- `./gradlew test :sdk-kmp:jvmTest`
- `cd sdk-ts && npm run check:contracts && npm test && npm run build`

## Workstreams

### Contract Consolidation

- Choose one canonical transport/event model shared by server, TS, and KMP.
- Normalize naming differences between:
  - Kotlin contracts in `chronotrace-contract`
  - TS SDK event/config types
  - runtime envelope shapes used by TS transports
- Finalize ID formats, timestamp formats, and sequence semantics.

### SDK Surface Cleanup

- Reduce overlapping abstractions in `sdk-ts` so there is one authoritative public API surface and one internal runtime path.
- Align KMP and TS public APIs around the same concepts:
  - init
  - withTrace
  - withSpan
  - startSpan/end
  - inject/extract headers
  - transport config
  - capture config

### Repository Hygiene

- Define generated-artifact policy for `sdk-ts/dist` and `sdk-ts/node_modules`.
- Decide whether generated output is committed or excluded.
- Add repo-level conventions for where future spec-complete modules will live.

## Required Outputs

- Updated contract matrix showing the final source of truth for every event type.
- A short API compatibility note for KMP and TS.
- A repo hygiene checklist that blocks future drift.

## Acceptance Criteria

- No duplicate or conflicting transport/event definitions remain undocumented.
- TS and KMP public APIs can be described side-by-side without ambiguity.
- Every later phase can reference a single canonical contract model.

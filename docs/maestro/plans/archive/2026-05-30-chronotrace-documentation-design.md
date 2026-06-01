# ChronoTrace Documentation — Design Document

## Problem Statement

The ChronoTrace project has substantial existing documentation (~3,100 lines across user-manual, deployment-guide, runbook, api/README, clickhouse-schema, tls-proxy). However, the docs have stale version references (0.1.0-SNAPSHOT →1.0.0, Kotlin2.1.0 → 2.2.21) and some Gradle plugin IDs are incorrect. The task is to audit existing docs, fix stale references, and produce install docs for building/installing ChronoTrace and its dependencies.

## Requirements

### Functional Requirements
1. **REQ-1**: All documentation must reflect current version 1.0.0, Kotlin2.2.21, and correct plugin IDs
2. **REQ-2**: Install/build documentation must cover all prerequisites, build steps, and running the server
3. **REQ-3**: SDK public API reference must cover all public entry points with correct signatures
4. **REQ-4**: Internal doc links must resolve correctly

### Non-Functional Requirements
- Documentation must be maintainable (easy to update on future version bumps)
- Code examples must be verified against actual source code

## Approach

### Selected Approach

**Iterative documentation improvement**: Audit and fix existing docs in place, create new targeted docs for missing content.

### Alternatives Considered

#### Greenfield rewrite
- **Description**: Replace all existing docs with freshly written versions
- **Rejected Because**: Existing docs are comprehensive and well-structured; rewrite would discard months of documented decisions

## Agent Team

| Phase | Agent(s) | Parallel | Deliverables |
|-------|----------|----------|--------------|
| 1 | technical-writer | No | Stale version references fixed in README, user-manual, deployment-guide |
| 2 | technical-writer | No | docs/install.md created |
| 3 | technical-writer | No | docs/sdk-api.md created |
| 4 | technical-writer | No | Link verification, doc cross-reference fixes |

## Risk Assessment

| Risk | Severity | Likelihood | Mitigation |
|------|----------|------------|------------|
| Missing additional stale references | LOW | LOW | Phase 1 includes full audit of all docs |
| New docs don't match actual API | MEDIUM | LOW | Agent reads actual source before writing |
| Breaking existing links | LOW | LOW | Phase 4 includes link verification |

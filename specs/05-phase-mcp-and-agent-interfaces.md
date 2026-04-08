# Phase 4: MCP And Agent Interfaces

Last reviewed: 2026-03-11

## Goal

Turn the current MCP-style endpoint into the final AI-facing query interface from the original spec.

## Current Status

Status: `Partial`

### Already Landed

- `/mcp` endpoint exists with `initialize`, `tools/list`, and `tools/call`.
- Tool descriptors and tool calls are wired through the baseline server.
- The in-memory query layer already exposes the data shapes future MCP tools will read from.

### Remaining To Complete This Phase

- Replace placeholder schemas with real per-tool JSON schemas.
- Finalize deterministic structured outputs for coding agents.
- Define paging, truncation, and compatibility behavior for the long-term MCP contract.
- Reconcile the current baseline `/mcp` behavior with the final agent-facing contract.

### Code Entry Points

- `chronotrace-server/src/main/kotlin/org/chronotrace/server/McpTooling.kt`
- `chronotrace-server/src/main/kotlin/org/chronotrace/server/McpModels.kt`
- `chronotrace-server/src/main/kotlin/org/chronotrace/server/ServerModule.kt`

### Verification To Re-run

- `./gradlew :chronotrace-server:test`
- MCP-specific contract tests once real schemas are introduced

## Workstreams

### Tool Contract Finalization

- Replace placeholder `inputSchema` and `outputSchema` values with real JSON schemas.
- Define the final argument and result shape for:
  - log search
  - log/frame lookup
  - trace hierarchy
  - adjacent stepping
  - remote rule management
  - purge management
  - system health

### Agent-Oriented Output Design

- Specify structured outputs first and text summaries second.
- Define truncation rules for large locals/call stacks.
- Define paging and limit behavior for tools returning collections.

### Source Correlation

- Define how file paths in frames map to local workspaces.
- Define how source lookup failures are surfaced to agents.
- Define what is guaranteed when source files are absent or moved.

### Compatibility

- Decide whether the current `/mcp` endpoint shape remains or becomes a compatibility shim.
- Document the final MCP transport and server behavior.

## Required Outputs

- Tool catalog with final schemas
- Agent usage contract
- Compatibility note for baseline `/mcp` behavior

## Acceptance Criteria

- Every tool has a real schema, not a placeholder.
- Tool outputs are deterministic enough for downstream coding agents.
- The server-side MCP contract is no longer ambiguous or baseline-only.

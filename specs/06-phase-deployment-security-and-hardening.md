# Phase 5: Deployment, Security, And Hardening

Last reviewed: 2026-03-11

## Goal

Complete the self-hosted production story while preserving the local-first developer workflow.

## Current Status

Status: `Minimal`

### Already Landed

- Root `docker-compose.yml` and server Dockerfile exist.
- Local developer bootstrap is possible with the current in-memory server baseline.
- Auth modes and deployment hardening concepts already exist in the spec set even though they are not implemented.
- SDK baselines now expose runtime health, queue pressure signals, dropped-event counters, and fatal-flush state that later operator-facing health work can build on.

### Remaining To Complete This Phase

- Define and implement local versus shared deployment modes.
- Finalize auth provider behavior and TLS expectations.
- Add operator-facing guidance for persistent services, secrets, metrics, queue pressure, and dropped-event visibility.
- Document compliance posture and deletion caveats against the real storage implementation.

### Code Entry Points

- `docker-compose.yml`
- `chronotrace-server/Dockerfile`
- `README.md`

### Verification To Re-run

- Container build and compose bootstrap checks
- Any auth/TLS and operational-health verification added during this phase

## Workstreams

### Auth And Security

- Finalize auth provider model:
  - none
  - apiKey
  - bearer
  - mTLS
- Define local-only no-auth rules versus remote/shared deployment requirements.
- Define TLS expectations and secret handling.

### Deployment Modes

- Define local single-node deployment.
- Define shared non-local deployment profile.
- Define required infrastructure wiring for persistent services.

### Operational Hardening

- Define server health checks, metrics, queue pressure visibility, and dropped-event accounting.
- Define failure recovery expectations and restart behavior.
- Define configuration surfaces needed by operators.

### Compliance And Data Handling

- Define operator-facing defaults for retention and purge behavior.
- Define redaction responsibilities between SDKs and server.
- Define the documented limits of deletion guarantees.

## Required Outputs

- Deployment profile matrix
- Auth and TLS design doc
- Operator checklist
- Hardening checklist

## Acceptance Criteria

- Local and shared deployments are clearly separated by config and safety rules.
- The deployment story includes persistent services, not just the baseline Dockerfile.
- ChronoTrace has its own observability and operational safeguards documented.

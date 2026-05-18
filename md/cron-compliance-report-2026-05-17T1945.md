# SPEC Compliance Report — ChronoTrace
**Generated:** 2026-05-17T19:45 UTC
**Board:** chronotrace (53 done, 0 running, 0 ready)

---

## Rule Compliance Matrix

| Rule | Status | Evidence |
|------|--------|----------|
| Rule 1: End-to-end tested | ✅ PASS | 146 server tests + 7 KMP JVM + 82 TS SDK = 235/235 passing |
| Rule 2: No stubs/mocks | ✅ PASS | 0 stub/fake/mock in production code (thread 08) |
| Rule 3: All languages tested | ⚠️ PARTIAL | JVM+KMP+TS done; KMP JS/Wasm tests not run in this cycle |
| Rule 4: Docker works | ✅ PASS | 3 containers running; ClickHouse integration 5/5 via testcontainers |
| Rule 5: MCP server works | ✅ PASS | All 11 tools live-verified with real responses (thread 04) |
| Rule 6: No fakery | ❌ FAIL | In-memory audit log with explicit "production would..." comment; ephemeral key registry |

---

## Quality Bar

| Aspect | Status | Evidence |
|--------|--------|----------|
| README ≥50 lines | ❌ FAIL | 19 lines (thread 01) |
| API docs ≥100 lines | ✅ PASS | 734 lines |
| User manual ≥100 lines | ✅ PASS | 1,007 lines |
| No placeholder content | ❌ FAIL | Package name, config field, storage mode, retention contradictions (thread 07) |
| No TODO/FIXME/HACK | ✅ PASS | 0 occurrences in production code (thread 08) |
| Code quality | ❌ FAIL | Revoked keys authenticate; dynamic keys ephemeral; audit not durable (thread 09) |

---

## Critical Gaps Found

### CRITICAL — Security/Architecture (3)

1. **`revokeKey()` does not remove from `originalApiKeys`** — revoked keys authenticate until server restart
   - File: `ChronoStore.kt:266-286`
   - Fix: Add `originalApiKeys.remove(keyId)` in `revokeKey()`

2. **Dynamic keys not persisted** — keys created via `POST /api/v1/admin/keys` lost on restart
   - File: `ChronoStore.kt` in-memory `keyRegistry`/`originalApiKeys`
   - Fix: Persist to config file or DB-backed key store

3. **Audit log in-memory only** — `recordAuditEntry()` writes only to `CopyOnWriteArrayList`; comment says "in production this would also write to ClickHouse" but code doesn't
   - File: `ChronoStore.kt:333-336`
   - Fix: Implement ClickHouse audit insert path

### HIGH — Production Hardening (5)

4. **API keys/bearer tokens stored in plaintext** — no hashing
   - File: `ChronoStoreOptions.kt`
   - Fix: scrypt/argon2 hash at rest

5. **`bounceOnRejected=false` silently drops data** — no 503, no client retry triggered
   - File: `ChronoStoreOptions.kt`
   - Fix: Guard with startup warning

6. **Audit log unbounded** — `CopyOnWriteArrayList` grows until OOM
   - File: `ChronoStore.kt:63`
   - Fix: Ring buffer or periodic truncate

7. **No TLS configuration** — ClickHouse JDBC, Valkey, server all unencrypted
   - File: `ChronoStoreOptions.kt`
   - Fix: Add SSL/TLS options to all connections

8. **No horizontal scaling** — all state in-process; multiple instances = inconsistent state
   - File: `ChronoStore.kt` in-memory maps
   - Fix: Valkey-backed shared registry (documented as future work)

### MEDIUM — Correctness (6)

9. **README.md only 19 lines** — below 50-line minimum; no prerequisites, no version, no license, no examples
10. **Package name contradiction** — `@chronotrace/sdk` (user manual) vs `@chronotrace/sdk-ts` (sdk-ts README + RELEASE_NOTES)
11. **Config field contradiction** — `serverUrl` (user manual) vs `endpoint` (sdk-ts README); missing `appId`
12. **Storage mode default contradiction** — `memory` (RELEASE_NOTES) vs `FILE` (user manual)
13. **Frame retention default contradiction** — `7` (user manual) vs `30` (RELEASE_NOTES)
14. **Phantom GET /mcp** — user manual references endpoint not in API docs

---

## Quality Violations

| Issue | Severity | Location |
|-------|----------|----------|
| Revoked keys still authenticate | CRITICAL | ChronoStore.kt:266-286 |
| Dynamic keys lost on restart | CRITICAL | ChronoStore.kt (keyRegistry) |
| Audit log in-memory only | CRITICAL | ChronoStore.kt:333-336 |
| API keys plaintext | HIGH | ChronoStoreOptions.kt |
| Silent data drop on reject | HIGH | ChronoStoreOptions.kt |
| Audit log unbounded | HIGH | ChronoStore.kt:63 |
| No TLS configured | HIGH | ChronoStoreOptions.kt |
| No horizontal scaling | HIGH | ChronoStore.kt architecture |
| README <50 lines | HIGH | README.md |
| Package name mismatch | HIGH | docs/user-manual.md vs sdk-ts/README.md |
| Config field mismatch | HIGH | docs/user-manual.md vs sdk-ts/README.md |
| Storage mode contradiction | MEDIUM | RELEASE_NOTES vs user-manual.md |
| Retention days contradiction | MEDIUM | RELEASE_NOTES vs user-manual.md |
| Phantom GET /mcp | MEDIUM | docs/user-manual.md |

---

## Board State

```
Running: 0
Ready: 0
Todo: 0
Blocked: 0
Done: 53
Archived: 19
Total: 72
```

---

## Verdict: NON_COMPLIANT

**Reason:** Three CRITICAL security/architecture violations (revoked keys authenticate, ephemeral keys, in-memory audit), plus HIGH documentation contradictions that would break correct SDK integration, plus README below minimum length.

The project passes tests and Docker/MCP infrastructure, but the key management and audit architecture do not meet production-readiness requirements as specified in SPECIFICATIONS.md Rule 2 (no fakery/stubs) and implied production hardening requirements.

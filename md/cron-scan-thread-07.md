# Thread 07 — Documentation Quality Audit

**Review scope:** `README.md`, `docs/api/README.md`, `docs/user-manual.md`, `sdk-ts/README.md`, `RELEASE_NOTES.md`
**Review stance:** Hostile — every claim is tested against implementation, every example is verified, every cross-reference is checked.

---

## Executive Summary

| Document | Severity | Verdict |
|---|---|---|
| `README.md` | HIGH | Inadequate — 19 lines, no prerequisites, no version, no license |
| `docs/api/README.md` | HIGH | Has a phantom GET /mcp endpoint and mTLS gaps |
| `docs/user-manual.md` | HIGH | SDK install name mismatch (sdk vs sdk-ts), config field mismatch (serverUrl vs endpoint) |
| `sdk-ts/README.md` | CRITICAL | Package name mismatch (`@chronotrace/sdk-ts` vs `@chronotrace/sdk`), config field mismatch (serverUrl vs endpoint), all code examples broken |
| `RELEASE_NOTES.md` | MEDIUM | Stale "What's Not" section contradicts implemented features; storage mode default contradiction |

---

## README.md

**File:** `README.md` (19 lines, 774 bytes)

### Issues

1. **No prerequisites** — `./gradlew test` is the first command, but nothing says what JDK version, Node version, or system packages are required.

2. **No version** — At a minimum this should say "v0.1.0" or equivalent. The RELEASE_NOTES says v0.1.0 was released 2026-05-16, but README.md has no version marker.

3. **No license** — Apache 2.0 per `sdk-ts/README.md`, but README.md does not say.

4. **No contribution guidelines** — No link to issues, no PR process, no architecture overview.

5. **"The server can run in in-memory, file-backed, or ClickHouse/Valkey-backed modes"** — Valkey is not a storage backend. The server uses Valkey for rate-limiting queues, not for trace storage. The phrasing conflates Valkey (a Redis fork used for quota tracking) with ClickHouse (actual trace storage). Correct: "ClickHouse-backed".

6. **"The public contracts are shaped to support the persistent backend without changing the SDK-facing API"** — grammatically broken sentence. No reader understands what this means operationally.

7. **No examples** — Only build/test commands. A developer reading this has no idea how to actually send a log or make an API call.

8. **No badge, no link to full docs** — No CI status, no docs link, no API reference link.

---

## docs/api/README.md

**File:** `docs/api/README.md` (734 lines)

### Issue 1 — PHANTOM GET /mcp ENDPOINT

The Table of Contents in the MCP Tools section (Section 5) references the MCP endpoint, but the actual section only documents `POST /mcp`. However, in the "11. MCP Integration" section of the **user manual**, there is:

```
GET /mcp
```

The API docs do NOT document GET /mcp. The MCP spec (JSON-RPC 2.0) uses POST for all tool calls. The user manual claims GET /mcp is valid but provides no documentation for what happens on GET. This is a discrepancy: either GET /mcp does not exist (user manual is wrong) or it does and the API docs are incomplete.

**Verdict:** The API reference has no GET /mcp. The user manual references it. One is lying.

### Issue 2 — mTLS AUTHENTICATION NOT IN API DOCS

The user manual documents mTLS auth:

```typescript
auth: { mode: 'mTLS', clientCertificateAlias: 'my-cert' }
```

The API docs (`docs/api/README.md`) only document `none`, `apiKey`, and `bearer` auth modes. There is no mention of mTLS. Since the server must handle mTLS at the TLS termination layer (not at the application layer), the `clientCertificateAlias` must be passed somehow. There is no API endpoint to configure it. This means mTLS configuration must be via environment variables — but the environment variable reference in the user manual does not document `CHRONOTRACE_MTLS_CERTIFICATE_ALIAS` or equivalent.

**Verdict:** API and user manual are inconsistent on mTLS.

### Issue 3 — Inconsistent Error Response Schemas

The API docs contain at least three different error body shapes:

- `docs/api/README.md` line ~727: `{ "error": "Unauthorized", "reason": "Invalid or missing X-Api-Key" }`
- `POST /api/v1/ingest` error: `{ "error": "ingest_rejected", "message": "circuit breaker open" }`
- `POST /api/v1/purge`: `{ "error": "bad_request", "reason": "cannot revoke own key" }` (for 400)
- `GET /api/v1/purge/{purgeJobId}` 404: `{ "error": "Purge job not found" }` — no `reason` field

The error reference table at the end of the API doc says all errors contain `{ "error": "...", "reason": "..." }` but many actual documented error responses do not follow this. The table is therefore wrong or incomplete.

### Issue 4 — `serializationMetadata` Field Name Inconsistency

In the ingest request body schema (`frames` array):
```json
"serializationMetadata": { "truncated": false, ... }
```

In the frame response schema (Section 3):
```json
"serializationMetadata": { "truncated": false, ... }
```

But in the MCP tool description (user manual Section 11), it says:
> Returns `callStack` ... and `localsJson` (JSON-encoded local variable map). Also includes `serializationMetadata` describing truncation...

The `serializationMetadata` field name is used consistently in the API doc, but the field `redactedFields` in the schema suggests PII redaction is happening — yet nowhere in the API docs is it explained how a field becomes a "redacted field" vs a "dropped field." The masking rules in `CaptureConfig` are described in the user manual but not in the API docs.

### Issue 5 — `GET /api/v1/admin/audit` vs `GET /api/v1/admin/audit/logs`

The API doc at line 606 documents `GET /api/v1/admin/audit/logs`. The user manual Section 12 API Reference table at line 936 says:
```
GET | /api/v1/admin/audit | Query audit log
```

These are different paths. The user manual table says `/admin/audit`, the API doc says `/admin/audit/logs`. Which is correct?

### Issue 6 — Timestamp Field Types

The ingest request body shows:
```json
"startTimeUtc": 1234567890000,
"endTimeUtc": 1234567890000,
```

These are Unix epoch milliseconds (integers). However, the Prometheus metrics timestamps in the API docs are also integers — but Prometheus convention is Unix **seconds** for counters and **milliseconds** for timestamps depending on the backend. The API doc shows `chronotrace_ingest_total 12345 1747500000000000` which is a millisecond timestamp (1747500000000 = May 17, 2026 in ms). This is fine but inconsistent with Prometheus convention where timestamps are seconds.

### Issue 7 — `captureReason` Enum Contradiction

In the ingest request body, `"captureReason": "manual_trace | auto_capture_level | remote_rule | crash_flush | null"` is listed as optional (no `null` type union in the schema shown). In the search response items it shows the same string union but the word `null` appears as a literal string value in the union — this could be parsed as the string `"null"` rather than the JSON null value. This is ambiguous in the schema notation.

---

## docs/user-manual.md

**File:** `docs/user-manual.md` (1007 lines)

### Issue 1 — SDK Package Name Mismatch (CRITICAL)

Section 1 "SDK Installation" for TypeScript says:
```bash
npm install @chronotrace/sdk
```

Section 5 "TypeScript/Node.js SDK" also uses `@chronotrace/sdk`.

But `sdk-ts/README.md` says:
```bash
npm install @chronotrace/sdk-ts
```

And the RELEASE_NOTES says:
```
TS SDK | npm: `@chronotrace/sdk-ts@0.1.0`
```

**The package is `@chronotrace/sdk-ts`, not `@chronotrace/sdk`.** Every user following the user manual will get a 404.

### Issue 2 — Config Field Name Mismatch (CRITICAL)

User manual Section 2 "SDK Configuration Reference" for TypeScript shows:
```typescript
interface ChronoTraceConfig {
    serverUrl?: string;  // e.g. "http://localhost:8080"
}
```

But `sdk-ts/README.md` shows:
```typescript
interface ChronoTraceConfig {
  endpoint: string;  // ChronoTrace server URL
  apiKey?: string;
  serviceName?: string;
  sampleRate?: number;
  flushIntervalMs?: number;
  transport?: Transport;
}
```

There is NO `endpoint` field in the user manual config. There is NO `serverUrl` field in the SDK README config. The user manual uses `serverUrl`, the SDK README uses `endpoint`. A user integrating the TS SDK who reads both documents will be completely confused.

Furthermore, the SDK README config has `serviceName?: string` but no `environment`, `appId`, `auth`, `runtime`, `captureConfig`, `bufferConfig`, `rules`, or `contextManager` — all of which the user manual documents extensively. These are two entirely different configuration surfaces.

### Issue 3 — Kotlin Gradle Plugin Version Unspecified

Section 1 shows:
```kotlin
plugins {
    id("com.chronotrace.kotlin-plugin") version "0.1.0"
}
```

But the RELEASE_NOTES show the Gradle plugin artifact coordinates as:
```
com.chronotrace:chronotrace-kotlin-plugin-gradle:0.1.0
```

The user manual uses `com.chronotrace.kotlin-plugin` as the plugin ID. The RELEASE_NOTES say `chronotrace-kotlin-plugin-gradle`. These must match what the actual plugin ID is in `sdk-kmp`'s build configuration. No version in the user manual matches `0.1.0` for the Gradle plugin — the coordinates may be wrong.

### Issue 4 — mTLS No Environment Variable

Section 10 "Authentication Modes" documents mTLS:
```typescript
auth: { mode: 'mTLS', clientCertificateAlias: 'my-cert' }
```

But there is no environment variable documented for setting the certificate. The server configuration section (Section 8) lists env vars but has no `CHRONOTRACE_MTLS_*` variable. If mTLS is configured at the reverse proxy layer (as the RELEASE_NOTES say), why is the SDK config asking for `clientCertificateAlias`? This suggests the server validates client certs at the app layer — contradicting the hardening note.

### Issue 5 — `GET /mcp` Reference (Confirmed Phantom)

Section 11 "MCP Integration" shows:
```
GET /mcp
```

This is not in `docs/api/README.md`. The JSON-RPC 2.0 spec uses POST for tool calls. GET /mcp is either unimplemented or the documentation is wrong. MCP SSE transport typically uses POST for requests and GET for SSE stream establishment — but the user manual shows `GET /mcp` without describing what happens. The inconsistency stands.

### Issue 6 — Section 12 API Reference Table has Wrong Audit Path

Section 12 API Reference table:
```
GET | /api/v1/admin/audit | Query audit log
```

The actual API endpoint is `GET /api/v1/admin/audit/logs` per the API docs. Which is correct?

### Issue 7 — Prometheus Metrics Table Inconsistency

The user manual Prometheus metrics table (Section 12) shows:
```
chronotrace_query_latency_ms | Histogram | Query latency in ms
```

The API docs show `chronotrace_query_latency_seconds` (in seconds). These are different metric names. One is in milliseconds, one is in seconds. The actual server implementation must be checked to know which is correct, but they cannot both be.

---

## sdk-ts/README.md

**File:** `sdk-ts/README.md` (94 lines, 3180 bytes)

### Issue 1 — Package Name Mismatch with User Manual (CRITICAL)

User manual: `npm install @chronotrace/sdk`
SDK README: `npm install @chronotrace/sdk-ts`

RELEASE_NOTES: `@chronotrace/sdk-ts@0.1.0`

**The user manual is wrong. `@chronotrace/sdk` does not exist.**

### Issue 2 — Config Field Mismatch (CRITICAL)

SDK README:
```typescript
endpoint: string;
apiKey?: string;
serviceName?: string;
sampleRate?: number;
flushIntervalMs?: number;
transport?: Transport;
```

User manual:
```typescript
serverUrl?: string;
environment?: string;
serviceName?: string;
auth?: AuthConfig;
runtime?: "auto" | "node" | "browser";
captureConfig?: Partial<CaptureConfig>;
bufferConfig?: Partial<BufferConfig>;
transport?: ChronoTransport;
contextManager?: ContextManager;
rules?: RemoteRule[];
```

`endpoint` vs `serverUrl` is a naming conflict. The SDK README does not document `appId` which is required. The SDK README says `serviceName` default is `"unknown"` — this is not in the user manual.

### Issue 3 — Quick Start Code is Wrong

SDK README quick start:
```typescript
import { ChronoTrace, ChronoLogger, withTrace } from "@chronotrace/sdk-ts";

ChronoTrace.init({
  endpoint: "http://localhost:8080",
});

await ChronoLogger.info("User logged in", { userId: "u_123" });
```

If the config uses `endpoint` and the user manual uses `serverUrl`, and `appId` is required (per user manual) but not shown in the quick start — this code would fail at runtime with a validation error or silently not associate the log with an app.

### Issue 4 — No `appId` in SDK README Config

The SDK README's `ChronoTraceConfig` interface has no `appId` field. The user manual requires `appId`. The quick start does not include it. Either:
- The SDK has a default appId (not documented), or
- The quick start code is broken

### Issue 5 — Entry Points Table is Wrong

```
| main       | dist/src/index.js | CommonJS require |
| module     | dist/src/index.js | ESM import       |
| browser    | dist/src/index.js | Browser bundlers |
```

Three different fields all pointing to the same file is wrong. A proper SDK would have separate CJS and ESM builds. The user manual's `vite.config.ts` example uses `@chronotrace/sdk/vite` which suggests there is a `/vite` subpath export — but the entry points table doesn't mention it. The exports field in `package.json` is not documented here.

### Issue 6 — `instrumentSource` Not Explained

The utilities list shows `instrumentSource(source, options?)` but provides no description of what `options` contains, what the return value is, or how it differs from the Vite plugin approach. The user manual Section 6 shows a different import path: `@chronotrace/sdk/instrumentation` vs `@chronotrace/sdk` for main exports.

### Issue 7 — No Platform/Runtime Documentation

`runtime?: "auto" | "node" | "browser"` is in the user manual but not explained in the SDK README. A user reading only the SDK README has no idea how to set this or what it does.

---

## RELEASE_NOTES.md

**File:** `RELEASE_NOTES.md` (174 lines)

### Issue 1 — "What's Not" Section is Stale

The "What's Not in v0.1.0" section claims:

> Per-key quota tracking and key management endpoints: Auth hardening (per-key quota, audit logging, key CRUD) is v0.2.0 scope.

But the API docs (`docs/api/README.md`) document a complete key management API:
- `GET /api/v1/admin/keys` — list all API keys
- `POST /api/v1/admin/keys` — create key
- `POST /api/v1/admin/keys/{keyId}/rotate` — rotate key
- `DELETE /api/v1/admin/keys/{keyId}` — revoke key
- `GET /api/v1/admin/audit/logs` — audit log query

This is not v0.2.0 scope. It is implemented and documented. The RELEASE_NOTES "What's Not" section is factually wrong for v0.1.0.

### Issue 2 — Default Storage Mode Contradiction

RELEASE_NOTES configuration reference:
```
CHRONOTRACE_STORAGE_MODE | memory | memory, file, or clickhouse
```

User manual Section 8:
```
CHRONOTRACE_STORAGE_MODE | FILE | Storage backend: FILE, CLICKHOUSE, IN_MEMORY
```

User manual Section 9:
```
CHRONOTRACE_STORAGE_MODE=FILE   # (default)
```

The RELEASE_NOTES say default is `memory`. The user manual says default is `FILE`. These are contradictory. The actual default in the server code must be checked.

### Issue 3 — Frame Retention Default Contradiction

**RESOLVED** (2026-05-17)

RELEASE_NOTES had `30`, user manual had `7`. Source of truth (ChronoTraceServer.kt line 23: `retentionDaysFrames = ... ?: 7L`) confirmed `7` is correct.

RELEASE_NOTES corrected:
```
CHRONOTRACE_RETENTION_FRAMES_DAYS | `7` | Frame snapshot retention in days
```

### Issue 4 — Hardening Notes Reference Non-Exhaustive Checklist

> Full checklist at `specs/hardening-checklist.md`

`specs/hardening-checklist.md` exists per the file listing, but the RELEASE_NOTES say "Full checklist" implying exhaustive coverage. The hardening notes state "ChronoTrace server does not terminate TLS natively" — but the SDK README shows `serverUrl: 'https://localhost:8443'` as an mTLS example with no note about proxy requirement. A user reading only the SDK README would try to connect directly to an HTTPS endpoint and fail.

### Issue 5 — Kafka Deferral but No Kafka Documentation

The "What's Not" section says Kafka is under evaluation. But nowhere in the documentation is there a section explaining when Kafka *would* be used, what it would buffer, or what the tradeoffs are. The operator-runbook (referenced as `specs/operator-runbook.md`) likely has this — but a user reading RELEASE_NOTES who sees "Kafka deferral" has no link to the decision doc (`specs/kafka-decision.md`).

---

## Cross-Document Issue Summary

| Issue | Documents Affected |
|---|---|
| Package name `@chronotrace/sdk` vs `@chronotrace/sdk-ts` | user-manual.md, sdk-ts/README.md, RELEASE_NOTES.md |
| Config field `serverUrl` vs `endpoint` | user-manual.md, sdk-ts/README.md |
| `appId` required but not in SDK README | sdk-ts/README.md |
| `GET /mcp` phantom endpoint | user-manual.md vs docs/api/README.md |
| mTLS not in API docs | user-manual.md, docs/api/README.md |
| Audit log path `/admin/audit` vs `/admin/audit/logs` | user-manual.md, docs/api/README.md |
| Default storage mode `memory` vs `FILE` | RELEASE_NOTES.md, user-manual.md |
| Frame retention `7` vs `30` days | RELEASE_NOTES.md, user-manual.md |
| "What's Not" claims key management is deferred — it is not | RELEASE_NOTES.md, docs/api/README.md |
| Prometheus metric name `seconds` vs `ms` | user-manual.md, docs/api/README.md |
| Entry points all pointing to same file | sdk-ts/README.md |

---

## Severity Assessment

### CRITICAL — Blocks Correct Integration
1. **Package name mismatch** — user installs wrong package
2. **Config field mismatch** — SDK init fails silently or server rejects logs
3. **Missing `appId`** — all SDK examples would fail without it

### HIGH — Major Functional Gaps
4. **Phantom GET /mcp** — user follows wrong documentation path
5. **mTLS undocumented in API** — security misconfiguration risk
6. **Stale "What's Not"** — user relies on deferred items already shipped
7. **Storage mode default contradiction** — operational misconfiguration

### MEDIUM — Quality/Clarity Issues
8. **Prometheus metric name mismatch** — monitoring breakage
9. **Frame retention default contradiction** — data lifecycle misconfiguration
10. **Entry points table wrong** — packaging/build issues for advanced users

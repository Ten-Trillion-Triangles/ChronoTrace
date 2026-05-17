# ChronoTrace — Project Specifications

## Definition

This document is the authoritative technical specification for ChronoTrace, derived from exhaustive scanning of the codebase. It defines what the project is, how it works, what it expects, and what remains unknown.

---

## Project Description

### What It Is

ChronoTrace is a distributed tracing and observability platform with three core components:

1. **Tracing SDKs** (Kotlin Multiplatform + TypeScript) that capture logs, spans, and frame snapshots (local variable snapshots) from application code
2. **A Server** (Kotlin/Ktor) that ingests, stores, and serves trace data with support for ClickHouse or file-based storage
3. **An MCP Server** (Model Context Protocol) that exposes trace query tools for AI/tooling integration

### What Problem It Solves

ChronoTrace solves the problem of capturing rich debugging context (logs, call stacks, local variables) in distributed applications, storing it efficiently, and querying it through multiple interfaces (HTTP REST, WebSocket, MCP). The key differentiator is **frame snapshots** — the ability to capture local variable state at any point in the call stack, not just entry/exit points.

### Target Users

Application developers using Kotlin (JVM/JS/Wasm) or TypeScript (Node.js/browser) who want distributed tracing with rich local variable capture.

---

## Developer Expectations

### Core Capabilities (auto-derived from code)

**SDK Capabilities:**
- Log message emission with structured fields and severity levels (TRACE → FATAL)
- Span/trace creation with automatic context propagation via W3C `traceparent` headers or Chrono-specific headers
- Automatic frame snapshot capture on ERROR/FATAL log levels (autoCaptureLevels)
- Manual frame capture via `withTrace` / `withSpan` / `startSpan` APIs
- Local variable capture at trace boundaries via compiler plugin (Kotlin) or Vite transformer (TypeScript)
- Field redaction/masking for sensitive data (passwords, tokens)
- Buffering with configurable overflow strategies (DROP_OLDEST, DROP_NEWEST, BLOCK_CALLER)
- Flush on fatal errors via JVM shutdown hook

**Server Capabilities:**
- Ingest trace data (logs, spans, frame snapshots) via HTTP or WebSocket
- Search logs with filters (time range, appId, environment, level, traceId, spanId, text query)
- Retrieve full trace views (spans + logs + frames) by traceId
- Navigate frames temporally (step forward/backward through time)
- Manage API keys with rotation and revocation
- Rate limiting per API key with sliding window enforcement
- Audit logging of all protected endpoint calls
- Async purge jobs for data lifecycle management
- Prometheus-compatible metrics endpoint
- MCP tool interface (11 tools) for AI integration

**SDK-to-Server Interaction:**
- HTTP transport with retry on 503 (exponential backoff, max 3 retries)
- WebSocket transport for bidirectional communication (including remote rule push)
- Remote rules: CEL-like expressions pushed from server, evaluated client-side
- Context propagation via `injectHeaders` / `extractHeaders`

### Documentation Requirements

**API Documentation:**
- API must have their own set of documentation files, separate from this specification.

**User Manual:**
- User manual must be implemented covering SDK usage, server configuration, and MCP integration.

```
chronotrace-contract/
  └── Shared data model (Kotlin serialization contracts + TypeScript generator)

chronotrace-kotlin-plugin/
  └── K2 compiler IR plugin for local variable injection

chronotrace-kotlin-plugin-gradle/
  └── Gradle plugin that applies the K2 compiler plugin

sdk-kmp/ (Kotlin Multiplatform SDK)
  ├── commonMain/ — Platform-agnostic SDK (ChronoTrace, ChronoRuntime, ChronoCapture, buffers, transport)
  ├── jvmMain/    — JVM-specific (ThreadLocal context, stack trace capture, shutdown hook)
  ├── jsMain/     — JS/Wasm-specific (module-level context, V8 stack parsing)
  └── wasmJsMain/ — Wasm-specific

sdk-ts/ (TypeScript SDK)
  ├── src/ — Main SDK (client, capture, context, buffer, transport, remoteRules, instrumentation, vite)
  ├── dist/ — Compiled output
  └── generated/contracts.ts — Auto-generated from chronotrace-contract

chronotrace-server/
  └── Ktor/Netty server (ChronoStore, ServerModule, MCP tooling, metrics, auth)
```

**Storage backends** (ChronoStore):
- `InMemoryChronoStorage` — In-memory lists (no persistence)
- `FileChronoStorage` — JSON file persistence at `CHRONOTRACE_DATA_DIR`
- `ClickHouseChronoStorage` — JDBC-based ClickHouse with TTL, async inserts, circuit breaker queue

**Purge state backends** (ChronoPurgeState):
- `InMemoryChronoPurgeState` — In-memory (test/dev)
- `ValkeyChronoPurgeState` / `LazyValkeyChronoPurgeState` — Valkey/Redis persistence

---

## User Expectations

### What Users Can Do

**Via SDK:**
- Initialize `ChronoTrace.init(config)` to start tracing
- Emit logs: `ChronoLogger.info("message", { fields })` with automatic frame capture on ERROR/FATAL
- Create spans: `withSpan("operation", () => { ... })` with automatic context and local capture
- Inject/extract trace context into HTTP headers for distributed trace propagation
- Configure field redaction patterns, buffer sizes, flush intervals
- Evaluate remote rules pushed from server to trigger capture conditionally

**Via HTTP/REST API:**
- `POST /api/v1/ingest` — Send batches of logs, spans, frames
- `POST /api/v1/logs/search` — Query logs with filters
- `GET /api/v1/traces/{traceId}` — Retrieve full trace (spans + logs + frames)
- `POST /api/v1/remote-rules` — Create/更新 remote rules
- `POST /api/v1/purge` — Create async data purge jobs
- `GET /health`, `GET /metrics` — Monitoring endpoints

**Via Admin API:**
- `GET/POST /api/v1/admin/keys` — Manage API keys
- `GET /api/v1/admin/audit/logs` — Query audit trail

**Via MCP (JSON-RPC 2.0):**
- `search_logs`, `get_log`, `get_frame_snapshot`, `get_trace`, `step_frames`
- `list_remote_rules`, `upsert_remote_rule`, `delete_remote_rule`
- `create_purge_job`, `get_purge_job`, `get_system_health`

### Installation/Deployment

**Server (Docker Compose):**
```yaml
chronotrace-server:
  image: chronotrace-server (Dockerfile build)
  ports: 8080:8080
  environment:
    - CHRONOTRACE_AUTH_MODE=none
    - CHRONOTRACE_STORAGE_MODE=clickhouse
    - CHRONOTRACE_CLICKHOUSE_JDBC_URL=jdbc:clickhouse://clickhouse:8123/default
    - CHRONOTRACE_VALKEY_HOST=valkey

clickhouse:
  image: clickhouse/clickhouse-server:25.1
  ports: 8123, 9000

valkey:
  image: valkey/valkey:8.0
  port: 6379
```

**Kotlin SDK (Maven):**
```kotlin
dependencies {
    implementation("com.chronotrace:sdk-kmp-jvm:0.1.0")
}
// Apply Gradle plugin for compiler instrumentation:
plugins.id("org.chronotrace.kotlin-plugin")
```

**TypeScript SDK (npm):**
```bash
npm install @chronotrace/sdk-ts
# Vite plugin for instrumentation:
// vite.config.ts: import { createChronoTraceVitePlugin } from '@chronotrace/sdk-ts/vite'
```

---

## Technical Requirements

### Languages & Frameworks (auto-derived)

**Kotlin:**
- Kotlin 2.2.21 (JVM, Multiplatform, Wasm/JS)
- kotlinx-serialization 1.8.0
- kotlinx-coroutines-core 1.10.2
- kotlinx-datetime 0.7.1

**Server:**
- Ktor 3.1.1 (server-core, server-netty, server-websockets, content-negotiation, call-logging, status-pages)
- Netty (JVM network layer)
- ClickHouse JDBC 0.9.1
- Jedis 5.2.0 (Valkey client)
- Logback 1.5.18 + kotlin-logging 7.0.14

**TypeScript:**
- TypeScript (strict mode, ES2022, CommonJS output)
- Vitest (test runner)
- Native `fetch` API (no external HTTP client)
- Native `WebSocket` API

**Build:**
- Gradle 8.x (Kotlin DSL)
- Node.js 20+ (TS SDK build + CI)
- Docker + docker-compose

### Build System (auto-derived)

**Kotlin/Gradle:**
- Root: `gradle build` — builds all modules
- `gradle :chronotrace-server:installDist` — builds server distribution
- `gradle :chronotrace-contract:generateTypeScriptContracts` — generates sdk-ts/generated/contracts.ts
- `gradle :chronotrace-kotlin-plugin:jar` — builds compiler plugin JAR
- `gradle :sdk-kmp:publishToMavenLocal` — publishes KMP SDK to Maven local repository

**TypeScript/npm:**
- `npm run build` (tsc) — compiles sdk-ts/src → sdk-ts/dist
- `npm run check:contracts` — verifies generated contracts match Kotlin source
- `npm run test` (vitest run) — runs SDK tests

**CI/CD:**
- GitHub Actions: `.github/workflows/ci.yml` (build + test + contract check), `.github/workflows/release.yml` (docker + npm + Maven publish)

### Platform Targets

- **Server**: JVM (Linux x64, Docker container)
- **Kotlin SDK**: JVM (target 17+), JavaScript (Node.js/browser via IR), WebAssembly/JS
- **TypeScript SDK**: Node.js (18+), browser (ES2022)

### Data Storage (auto-derived from DB/integration scan)

**ClickHouse (primary production storage):**
- JDBC driver 0.9.1
- Tables: `logs`, `spans`, `frame_snapshots` — MergeTree engine
- ORDER BY: `(app_id, timestamp_utc, sequence_id)` for logs/frames; `(app_id, start_time_utc, span_id)` for spans
- TTL-based retention (logs: 30 days, spans: 30 days, frames: 7 days default)
- Async inserts supported (configurable)
- Circuit breaker: bounded `LinkedBlockingQueue` with configurable capacity + timeout

**Valkey (purge job state only):**
- Jedis 5.2.0 client
- Key pattern: `chronotrace:purge:${purgeJobId}` and `chronotrace:purge:ids` (SET)
- Lazy initialization (doesn't block server startup if Valkey is down)

**File storage (development):**
- JSON file at `CHRONOTRACE_DATA_DIR/chronotrace_store.json`

**In-memory (testing):**
- No persistence; pure in-memory lists with CopyOnWriteArrayList for audit

---

## Deployment Requirements

**Container deployment (Docker):**
- Server: `eclipse-temurin:25-jre` base image; runs `./gradlew :chronotrace-server:installDist` then executes installDist binary
- Multi-platform images: `linux/amd64, linux/arm64` (via release workflow)
- Docker Hub: `chronotrace/chronotrace-server` with semver tags

**Non-Docker deployment:**
- Server binary at `chronotrace-server/build/install/chronotrace-server/bin/chronotrace-server`
- Requires Java 25 runtime

**Environment variables (required for production):**
- `CHRONOTRACE_STORAGE_MODE=clickhouse` (or `FILE` for dev)
- `CHRONOTRACE_CLICKHOUSE_JDBC_URL` (when using ClickHouse)
- `CHRONOTRACE_VALKEY_HOST` (for purge job persistence)
- `CHRONOTRACE_AUTH_MODE=none|apiKey|bearer` (default: `none`)

**License:** MIT (likely, not yet formally decided)

**Open/Closed Source:** Open source (MIT license)

---

## Project Rules

**From AGENTS.md:** No AGENTS.md at project root — standard Kotlin/TypeScript conventions apply (ktlint, ESLint, kotlinx-serialization)

**Mandatory development rules:**
1. All features in the project must be tested, and verified working end to end.
2. Dependencies may be installed as long as they don't require sudo.
3. You may not modify the home folder on this system outside of the project's repo and workspace.
4. All supported languages for traces must be fully tested and proven working.
5. Deployment is not considered a requirement for production ready — however, Docker must work and the MCP server must be tested and proven working.

**Code style:**
- Kotlin: ktlint + detekt enforced via Gradle
- TypeScript: ESLint + Vitest, strict mode, skipLibCheck: true
- Serialization: kotlinx-serialization JSON (all types must be serializable)

**Test patterns:**
- Kotlin: `kotlin.test` framework with `@Test` annotations; Ktor `testApplication` for HTTP integration; `@Testcontainers` for Docker-based tests
- TypeScript: Vitest with `describe`/`it` blocks; MCP tests dynamically skip if server unreachable (via `SKIP_MCP_INTEGRATION` env var)
- No tests for: chronotrace-contract, chronotrace-kotlin-plugin, chronotrace-kotlin-plugin-gradle

**Key architectural rules observed:**
- All SDK data types defined in `chronotrace-contract` — single source of truth for wire format
- TypeScript contracts auto-generated from Kotlin serializers (never hand-edited in `generated/contracts.ts`)
- Server is stateless (no session affinity); all state in ChronoStore
- SDKs buffer locally and flush to server (never block the calling thread on network failure)
- Remote rules evaluated client-side in TypeScript SDK; server stores and distributes rules but does not evaluate them

---

## Knowledge Gaps (Open)

The following items require deeper investigation or architectural decision:

1. **ClickHouse async insert behavior**: When `asyncInsert=true` and `bounceOnRejected=false`, what happens when async inserts are rejected? Does the server handle this differently than when `bounceOnRejected=true` (which returns 503)?

2. **WebSocket transport protocol**: The server has `/api/v1/ingest/ws` but the `webSocketTransport.ts` was not fully read. What is the exact WebSocket protocol for ingest? How does the server acknowledge messages? How does the server send `Command` messages (upsert_rule, delete_rule) to the client?

3. **Trace ID normalization on server**: SDK normalizes traceId to 32 lowercase hex chars and spanId to 16. Does the server enforce or expect this normalization, or does it accept any string format?

4. **Kotlin plugin compilation scope**: `sdk-kmp/build.gradle.kts` adds `dependsOn(chronoTraceCompilerPluginJar)` to ALL `KotlinCompilationTask` tasks. This means the plugin loads even for test compilation, metadata generation, etc. Is this intentional, or should it be scoped to production compilation only?

5. **Release model**: None planned yet — will be defined when the framework is production ready and tested.

6. **RuleDeliveryConfirmation flow**: Presumed planned but not yet implemented.
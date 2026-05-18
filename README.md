# ChronoTrace

AI-native temporal logging with frame-level local variable capture. Capture logs, spans, and variable state across distributed services — then query it via HTTP REST, WebSocket, or MCP.

## What it does

ChronoTrace captures rich debugging context (logs, call stacks, local variables) in application code and serves it through multiple interfaces. The key differentiator is **frame snapshots** — capturing local variable state at any point in the call stack, not just entry/exit boundaries.

## Repository structure

```
chronotrace-contract/          # Shared Kotlin data contracts (serialization)
chronotrace-server/             # Ktor server (ingest, query, MCP tooling, metrics)
sdk-kmp/                        # Kotlin Multiplatform SDK (JVM/JS/Wasm)
sdk-ts/                         # TypeScript SDK (Node.js + browser)
chronotrace-kotlin-plugin/     # K2 compiler IR plugin for local variable injection
chronotrace-kotlin-plugin-gradle/  # Gradle plugin that applies the compiler plugin
```

## Prerequisites

| Component | Requirement |
|-----------|-------------|
| Kotlin SDK (JVM) | Java 17+ runtime |
| Kotlin SDK (JS/Wasm) | Node.js 18+ |
| TypeScript SDK | Node.js 18+, npm 10+ |
| Server (dev) | Java 25+, Gradle 8.x |
| Server (Docker) | Docker + Docker Compose |
| ClickHouse storage | ClickHouse 25.x (via Docker Compose) |
| Valkey purge state | Valkey/Redis 8.x (via Docker Compose) |

## Version

Current: **0.1.0-SNAPSHOT**

Published to Maven Local via `gradle :sdk-kmp:publishToMavenLocal`.

## Quick start

### 1. Build everything

```bash
./gradlew build
```

### 2. Run the server

In-memory mode (no external dependencies):

```bash
./gradlew :chronotrace-server:run
```

File-backed mode:

```bash
CHRONOTRACE_STORAGE_MODE=file \
CHRONOTRACE_DATA_DIR=/tmp/chronotrace \
./gradlew :chronotrace-server:run
```

ClickHouse + Valkey mode (via Docker Compose):

```bash
docker compose up -d
CHRONOTRACE_STORAGE_MODE=clickhouse \
CHRONOTRACE_CLICKHOUSE_JDBC_URL=jdbc:clickhouse://localhost:8123/default \
CHRONOTRACE_VALKEY_HOST=localhost \
./gradlew :chronotrace-server:run
```

Server listens on `http://localhost:8080`.

### 3. Run SDK tests

Kotlin SDK:

```bash
./gradlew :sdk-kmp:test
```

TypeScript SDK:

```bash
cd sdk-ts && npm install && npm test
```

## Kotlin SDK usage

### Gradle setup

```kotlin
plugins {
    id("org.chronotrace.kotlin-plugin")
}

dependencies {
    implementation("com.chronotrace:sdk-kmp-jvm:0.1.0-SNAPSHOT")
}
```

### Initialize and emit logs

```kotlin
import com.chronotrace.sdk.ChronoTrace
import com.chronotrace.sdk.ChronoLogger

fun main() {
    ChronoTrace.init(
        appId = "my-service",
        serverUrl = "http://localhost:8080"
    )

    ChronoLogger.info("User logged in", mapOf("userId" to 12345))
    ChronoLogger.error("Connection failed", mapOf("host" to "db.prod.internal"))
}
```

### Create spans with context

```kotlin
import com.chronotrace.sdk.withSpan
import com.chronotrace.sdk.ChronoTrace

// Trace an operation with automatic context propagation
val result = withSpan("fetch-user-profile", mapOf("userId" to userId)) {
    // Work inside here is traced with frame capture
    database.queryUser(userId)
}
```

### Propagate trace context across services

```kotlin
// Inject headers into an outgoing HTTP call
val headers = ChronoTrace.injectHeaders()
val response = httpClient.get("http://other-service/api") {
    headers.forEach { (k, v) -> header(k, v) }
}
```

## TypeScript SDK usage

### Install

```bash
npm install @chronotrace/sdk-ts
```

### Vite plugin (for instrumentation)

```typescript
// vite.config.ts
import { defineConfig } from 'vite'
import { createChronoTraceVitePlugin } from '@chronotrace/sdk-ts/vite'

export default defineConfig({
  plugins: [createChronoTraceVitePlugin({
    appId: 'my-web-app',
    serverUrl: 'http://localhost:8080'
  })]
})
```

### Initialize and emit logs

```typescript
import { ChronoTrace, ChronoLogger } from '@chronotrace/sdk-ts'

ChronoTrace.init({
  appId: 'my-web-app',
  serverUrl: 'http://localhost:8080'
})

ChronoLogger.info('User action', { action: 'checkout', itemCount: 3 })
ChronoLogger.error('API failed', { status: 503, endpoint: '/api/v1/users' })
```

### Span tracing

```typescript
import { withSpan } from '@chronotrace/sdk-ts'

const result = await withSpan('process-payment', { orderId: 'ORD-789' }, async () => {
  // Automatically captures local variables on error
  const charge = await paymentProvider.charge(orderId)
  return charge
})
```

## Server endpoints

| Method | Path | Description |
|---------|------|-------------|
| POST | `/api/v1/ingest` | Ingest logs, spans, frame snapshots (HTTP) |
| POST | `/api/v1/ingest/ws` | Ingest via WebSocket |
| POST | `/api/v1/logs/search` | Search logs with filters |
| GET | `/api/v1/traces/{traceId}` | Retrieve full trace |
| POST | `/api/v1/remote-rules` | Create/update remote rules |
| POST | `/api/v1/purge` | Create async purge job |
| GET | `/health` | Health check |
| GET | `/metrics` | Prometheus metrics |
| GET/POST | `/api/v1/admin/keys` | Manage API keys |
| GET | `/api/v1/admin/audit/logs` | Query audit trail |

MCP server: 11 tools exposed at `mcp/v1` (search_logs, get_log, get_frame_snapshot, get_trace, step_frames, list_remote_rules, upsert_remote_rule, delete_remote_rule, create_purge_job, get_purge_job, get_system_health).

## Environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `CHRONOTRACE_AUTH_MODE` | `none` | `none`, `apiKey`, or `bearer` |
| `CHRONOTRACE_STORAGE_MODE` | `inMemory` | `inMemory`, `file`, or `clickhouse` |
| `CHRONOTRACE_DATA_DIR` | — | Path for file storage |
| `CHRONOTRACE_CLICKHOUSE_JDBC_URL` | — | JDBC URL for ClickHouse |
| `CHRONOTRACE_VALKEY_HOST` | — | Host for Valkey (purge job state) |
| `CHRONOTRACE_PORT` | `8080` | Server listen port |

## Build artifacts

| Artifact | Command |
|----------|---------|
| All modules | `./gradlew build` |
| Server distribution | `./gradlew :chronotrace-server:installDist` |
| Compiler plugin JAR | `./gradlew :chronotrace-kotlin-plugin:jar` |
| TS contracts | `./gradlew :chronotrace-contract:generateTypeScriptContracts` |
| KMP SDK to Maven Local | `./gradlew :sdk-kmp:publishToMavenLocal` |
| TypeScript SDK | `cd sdk-ts && npm install && npm run build` |

## Storage backends

- **inMemory** — No persistence; pure in-memory lists. Good for tests.
- **file** — JSON file at `$CHRONOTRACE_DATA_DIR/chronotrace_store.json`.
- **clickhouse** — JDBC-based ClickHouse with TTL, async inserts, circuit breaker queue. Production storage.

Purge job state: in-memory (dev/test) or Valkey/Redis (production).
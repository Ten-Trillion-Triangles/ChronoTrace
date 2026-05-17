# Scan: External Integrations

## Files Scanned

### Kotlin Server Source Files
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoStore.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ServerModule.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/AuthTypes.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoStoreOptions.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoTraceServer.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoStoreBackend.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/McpTooling.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/McpModels.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ServerMetrics.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoPurgeState.kt
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/src/main/kotlin/org/chronotrace/server/ChronoStorage.kt

### Gradle Build Files
- /home/cage/Desktop/Workspaces/ChronoTrace/chronotrace-server/build.gradle.kts
- /home/cage/Desktop/Workspaces/ChronoTrace/sdk-kmp/build.gradle.kts

### TypeScript Transport Files
- /home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/transports/httpTransport.ts
- /home/cage/Desktop/Workspaces/ChronoTrace/sdk-ts/src/transports/webSocketTransport.ts

### Docker
- /home/cage/Desktop/Workspaces/ChronoTrace/docker-compose.yml

---

## Database Integrations

### ClickHouse (Primary Storage)
- **Driver**: `com.clickhouse:clickhouse-jdbc:0.9.1` (JDBC)
- **Database**: `chronotrace` (default, configurable)
- **JDBC URL**: Configured via `CHRONOTRACE_CLICKHOUSE_JDBC_URL`
- **Authentication**: Optional username/password via `CHRONOTRACE_CLICKHOUSE_USERNAME` / `CHRONOTRACE_CLICKHOUSE_PASSWORD`

**Tables Created (Auto-bootstrap)**:
- `logs` - MergeTree engine, ORDER BY (app_id, timestamp_utc, sequence_id), TTL-based retention
- `spans` - MergeTree engine, ORDER BY (app_id, start_time_utc, span_id), TTL-based retention
- `frame_snapshots` - MergeTree engine, ORDER BY (app_id, timestamp_utc, sequence_id), TTL-based retention

**Configuration Class**: `ClickHouseConfig` (in ChronoStoreOptions.kt)
- `jdbcUrl`: Full JDBC connection string
- `database`: Target database name
- `username`/`password`: Optional credentials
- `connectTimeoutMs`: Connection timeout (default 5000ms)
- `ingestQueueCapacity`: Bounded queue size for circuit breaker (default 0 = sync)
- `ingestQueueTimeoutMs`: Queue offer timeout (default 5000ms)
- `asyncInsert`: Append `?async_insert=1&wait_for_async_insert=0` to JDBC URL
- `bounceOnRejected`: Return HTTP 503 when queue full (default true)

### File Storage (Fallback)
- JSON file-based storage at configurable `dataDir`
- No external dependencies
- Used when `CHRONOTRACE_STORAGE_MODE=file` or `memory`

### In-Memory Storage (Fallback)
- Pure in-memory storage for testing
- No external dependencies

---

## Cache/Messaging

### Valkey (Purge Job State)
- **Driver**: `redis.clients:jedis:5.2.0` (Jedis client)
- **Usage**: Stores purge job state and queue (not used for data storage)
- **Connection**: Configured via `CHRONOTRACE_VALKEY_HOST` / `CHRONOTRACE_VALKEY_PORT`
- **Authentication**: Optional password via `CHRONOTRACE_VALKEY_PASSWORD`
- **Database**: Configurable via `CHRONOTRACE_VALKEY_DATABASE` (default 0)
- **Key Prefix**: `chronotrace` (default), configurable via `CHRONOTRACE_VALKEY_KEY_PREFIX`

**Valkey Key Patterns**:
- `${keyPrefix}:purge:${purgeJobId}` - Individual purge job JSON
- `${keyPrefix}:purge:ids` - Set of all purge job IDs

**Configuration Class**: `ValkeyConfig` (in ChronoStoreOptions.kt)

**Implementation**: `ValkeyChronoPurgeState` class using `JedisPooled` connection pool
- Lazy initialization via `LazyValkeyChronoPurgeState` wrapper
- Purge state is non-blocking; Valkey unavailability does not prevent ingest

**Health Check**: Ping to Valkey, returns `true`/`false`/`null`

### No Kafka/Message Queue Integration
- No message queue found for event streaming
- Ingest uses direct ClickHouse JDBC inserts (sync or with bounded queue circuit breaker)

---

## Network Clients

### HTTP Server (Ktor)
- **Framework**: `io.ktor:ktor-server-netty-jvm:3.1.1`
- **Netty**: JVM network server for HTTP/WebSocket handling
- **Content Negotiation**: `io.ktor:ktor-server-content-negotiation-jvm` with kotlinx.serialization
- **WebSockets**: `io.ktor:ktor-server-websockets-jvm`
- **Call Logging**: `io.ktor:ktor-server-call-logging-jvm`
- **Status Pages**: `io.ktor:ktor-server-status-pages-jvm`

**Server Ports**:
- Default: 8080 (configurable via `PORT` env var)
- Binds to `CHRONOTRACE_BIND_HOST` (default 127.0.0.1, Docker: 0.0.0.0)

### HTTP Clients (SDK - TypeScript)
**httpTransport.ts**:
- Uses native `fetch` API (or custom `fetchImpl`)
- Configurable `headers` for API keys
- Retry logic: exponential backoff on 503 (max 3 retries by default)
- Base delay: 100ms

**webSocketTransport.ts**:
- Uses native `WebSocket` API (or custom `webSocketFactory`)
- Supports bidirectional communication
- JSON message framing
- Command handler callback for server-initiated messages

### No External HTTP Clients on Server
- Server does NOT make outbound HTTP calls
- SDK clients connect TO the server

---

## Authentication

### Auth Modes
Three authentication modes supported (configured via `CHRONOTRACE_AUTH_MODE`):

1. **`none`** - No authentication, all requests allowed
2. **`apiKey`** - API key authentication via `X-Api-Key` header
3. **`bearer`** - Bearer token authentication via `Authorization: Bearer <token>` header

### API Key Authentication
**Header**: `X-Api-Key: <keyValue>`

**Key Management** (Admin endpoints):
- `GET /api/v1/admin/keys` - List all keys (admin only)
- `POST /api/v1/admin/keys` - Create new key (admin only)
- `POST /api/v1/admin/keys/{keyId}/rotate` - Rotate key (admin only)
- `DELETE /api/v1/admin/keys/{keyId}` - Revoke key (admin only)

**Key Configuration**:
- Static keys via `CHRONOTRACE_API_KEYS` env var (comma-separated)
- Keys created via API have generated secure random values (32 bytes, URL-safe base64)
- Key metadata stored in `ChronoStore.keyRegistry`

**Key Data Model** (`ApiKeyMetadata`):
- `keyId`: Stable identifier (same as keyValue for static keys)
- `keyValue`: Secret value (only returned on create/rotate)
- `role`: "admin" or "client"
- `quota`: Optional rate limit (`ApiKeyQuota` with `limit` + `windowSeconds`)
- `appId`: Optional app-scoped restriction
- `createdAtUtc`, `rotatedAtUtc`, `revokedAtUtc`: Lifecycle timestamps

### Bearer Token Authentication
**Header**: `Authorization: Bearer <token>`

**Configuration**: `CHRONOTRACE_BEARER_TOKENS` env var (comma-separated list)

**Key ID Format**: `bearer:<token>`

### Quota/Rate Limiting
- Per-key sliding window rate limiting
- Configurable `limit` requests per `windowSeconds`
- HTTP 429 response with `Retry-After`, `X-RateLimit-*` headers when exceeded
- Quota tracked in-memory via `QuotaTracker` class

### Audit Logging
- All protected endpoint calls logged to in-memory `auditLog` (CopyOnWriteArrayList)
- `AuditLogEntry` records: timestamp, keyId, action, endpoint, method, outcome, statusCode, durationMs, appId, sdkInstanceId, traceId, ipAddress
- Admin endpoint `GET /api/v1/admin/audit/logs` for querying (admin only)

---

## SDK Bindings

### Kotlin Multiplatform SDK (sdk-kmp)
**Build Dependencies** (commonMain):
- `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2`
- `org.jetbrains.kotlinx:kotlinx-datetime:0.7.1`
- `org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0`

**No external SDK bindings** - Pure Kotlin/MPP library with no third-party network/db clients.

### TypeScript SDK (sdk-ts)
**HTTP Transport** (httpTransport.ts):
- Uses native `fetch` API
- No external HTTP client dependency

**WebSocket Transport** (webSocketTransport.ts):
- Uses native `WebSocket` API
- No external WebSocket library

**No additional third-party SDK bindings** in the SDK itself.

### Server Dependencies
**ClickHouse JDBC**: `com.clickhouse:clickhouse-jdbc:0.9.1`
- Direct JDBC for all database operations
- Uses `DriverManager.getConnection()` with Properties

**Jedis**: `redis.clients:jedis:5.2.0`
- `JedisPooled` for Valkey connection pooling

**Ktor**: Full server stack (see Network Clients above)

**TestContainers** (test only):
- `org.testcontainers:clickhouse:1.21.4`
- `org.testcontainers:junit-jupiter:1.21.4`

---

## Configuration Required

### Environment Variables

**Server Binding**:
| Variable | Default | Description |
|----------|---------|-------------|
| `CHRONOTRACE_BIND_HOST` | `127.0.0.1` | Server bind address |
| `PORT` | `8080` | Server port |
| `CHRONOTRACE_AUTH_MODE` | `none` | Auth mode: `none`, `apiKey`, `bearer` |
| `CHRONOTRACE_STORAGE_MODE` | `FILE` | Storage mode: `FILE`, `CLICKHOUSE` |

**Data Storage**:
| Variable | Default | Description |
|----------|---------|-------------|
| `CHRONOTRACE_DATA_DIR` | null | File storage directory |
| `CHRONOTRACE_RETENTION_LOGS_DAYS` | `30` | Log retention in days |
| `CHRONOTRACE_RETENTION_SPANS_DAYS` | `30` | Span retention in days |
| `CHRONOTRACE_RETENTION_FRAMES_DAYS` | `7` | Frame retention in days |

**ClickHouse**:
| Variable | Default | Description |
|----------|---------|-------------|
| `CHRONOTRACE_CLICKHOUSE_JDBC_URL` | null | Full JDBC URL (e.g., `jdbc:clickhouse://host:8123/default`) |
| `CHRONOTRACE_CLICKHOUSE_DATABASE` | `chronotrace` | Database name |
| `CHRONOTRACE_CLICKHOUSE_USERNAME` | null | Database username |
| `CHRONOTRACE_CLICKHOUSE_PASSWORD` | null | Database password |
| `CHRONOTRACE_CLICKHOUSE_CONNECT_TIMEOUT_MS` | `5000` | Connection timeout |
| `CHRONOTRACE_CLICKHOUSE_INGEST_QUEUE_CAPACITY` | `0` | Bounded queue size (0=sync) |
| `CHRONOTRACE_CLICKHOUSE_INGEST_QUEUE_TIMEOUT_MS` | `5000` | Queue offer timeout |
| `CHRONOTRACE_ASYNC_INSERT` | `false` | Enable async inserts |
| `CHRONOTRACE_BOUNCE_ON_REJECTED` | `true` | Return 503 when queue full |

**Valkey**:
| Variable | Default | Description |
|----------|---------|-------------|
| `CHRONOTRACE_VALKEY_HOST` | null | Valkey host |
| `CHRONOTRACE_VALKEY_PORT` | `6379` | Valkey port |
| `CHRONOTRACE_VALKEY_DATABASE` | `0` | Valkey database number |
| `CHRONOTRACE_VALKEY_PASSWORD` | null | Valkey password |
| `CHRONOTRACE_VALKEY_KEY_PREFIX` | `chronotrace` | Key prefix for namespacing |

**Authentication**:
| Variable | Default | Description |
|----------|---------|-------------|
| `CHRONOTRACE_API_KEYS` | empty | Comma-separated API keys for `apiKey` auth mode |
| `CHRONOTRACE_BEARER_TOKENS` | empty | Comma-separated bearer tokens for `bearer` auth mode |

### Docker Compose Services
```yaml
chronotrace-server:
  - CHRONOTRACE_AUTH_MODE: none
  - CHRONOTRACE_BIND_HOST: 0.0.0.0
  - CHRONOTRACE_STORAGE_MODE: clickhouse
  - CHRONOTRACE_CLICKHOUSE_JDBC_URL: jdbc:clickhouse://clickhouse:8123/default
  - CHRONOTRACE_CLICKHOUSE_DATABASE: chronotrace
  - CHRONOTRACE_VALKEY_HOST: valkey
  - CHRONOTRACE_VALKEY_PORT: 6379

clickhouse:
  - image: clickhouse/clickhouse-server:25.1
  - ports: 8123, 9000

valkey:
  - image: valkey/valkey:8.0
  - port: 6379
```

---

## Summary

**External Service Dependencies**:
1. **ClickHouse** - Primary data storage (JDBC)
2. **Valkey** - Purge job state/cache (Jedis/Redis protocol)

**No other external service integrations found** - No Kafka, no other databases, no OAuth providers, no cloud services.

**SDK Clients**: TypeScript SDK uses only native browser APIs (fetch, WebSocket) with no third-party network dependencies.

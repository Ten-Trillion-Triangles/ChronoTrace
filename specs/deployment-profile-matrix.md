# Deployment Profile Matrix

Last reviewed: 2026-05-16

## Overview

ChronoTrace supports two deployment profiles that reflect the boundary between developer-local workflows and shared production infrastructure. The profile determines which services are required, which auth rules apply, and what operational safeguards are mandatory.

| Dimension | Local Single-Node | Shared / Non-Local |
|---|---|---|
| Use case | Local dev, CI, demos | Production multi-tenant or team-shared |
| Storage backend | FILE (default) | CLICKHOUSE |
| Auth | `none` | `apiKey`, `bearer`, or mTLS |
| TLS | Optional / off | Required |
| Infrastructure | Host-local JVM + filesystem | Valkey + ClickHouse cluster |
| Horizontal scale | Single process only | Stateless server replicas behind load balancer |
| Secret delivery | Plain-text env vars acceptable for local | Secrets manager / vault required |

---

## Local Single-Node Profile

**Profile name:** `local`

### When to use
- Local development and debugging
- CI pipelines where the server runs ephemeral and in-memory
- One-off demo or PoC environments with no sensitive data

### Infrastructure requirements
- JVM 25+ (Eclipse Temurin recommended)
- 512 MB RAM minimum, 1 GB recommended
- Filesystem with adequate free space for the data directory (default `/var/chronotrace/data`)
- No external services required — storage is file-backed or in-memory

### Docker Compose (in-memory)
```yaml
services:
  chronotrace-server:
    build:
      context: .
      dockerfile: chronotrace-server/Dockerfile
    ports:
      - "8080:8080"
    environment:
      CHRONOTRACE_AUTH_MODE: none
      CHRONOTRACE_BIND_HOST: 0.0.0.0
      CHRONOTRACE_STORAGE_MODE: file
      CHRONOTRACE_DATA_DIR: /var/chronotrace/data
```

### Docker Compose (file-backed persistent)
```yaml
services:
  chronotrace-server:
    build:
      context: .
      dockerfile: chronotrace-server/Dockerfile
    ports:
      - "8080:8080"
    volumes:
      - chronotrace-data:/var/chronotrace/data
    environment:
      CHRONOTRACE_AUTH_MODE: none
      CHRONOTRACE_BIND_HOST: 0.0.0.0
      CHRONOTRACE_STORAGE_MODE: file
      CHRONOTRACE_DATA_DIR: /var/chronotrace/data
      CHRONOTRACE_RETENTION_LOGS_DAYS: 30
      CHRONOTRACE_RETENTION_SPANS_DAYS: 30
      CHRONOTRACE_RETENTION_FRAMES_DAYS: 7

volumes:
  chronotrace-data:
```

### Auth rules for local
- `CHRONOTRACE_AUTH_MODE: none` is the default and is **only acceptable** on private networks behind a firewall.
- Do not expose a no-auth server directly to the internet or untrusted networks.
- No API key validation, no bearer token checks, no TLS certificate validation.

### Operational notes
- Retention enforcement runs in-process; old records are pruned on ingest.
- No queue pressure signals because there is no external queue (Valkey not used in file mode).
- Log volume is bounded by disk and retention settings.
- No purge job state externalization — jobs are stored in memory and lost on restart.

---

## Shared / Non-Local Profile

**Profile name:** `shared`

### When to use
- Production environments with multiple SDK clients or cross-team usage
- Environments where data must survive server restarts
- Multi-region or highly available deployments

### Infrastructure requirements

#### Valkey (required)
- Version 8.0+
- Persistence: AOF recommended for production
- Memory: 256 MB minimum, scale with retention windows and event volume
- Network: reachable from the ChronoTrace server on `CHRONOTRACE_VALKEY_HOST:PORT`
- Authentication: password optional but strongly recommended

```
# docker-compose excerpt for Valkey
valkey:
  image: valkey/valkey:8.0
  command: valkey-server --appendonly yes --requirepass ${VALKEY_PASSWORD}
  ports:
    - "6379:6379"
  volumes:
    - valkey-data:/data

volumes:
  valkey-data:
```

#### ClickHouse (required)
- Version 25.1+ (using clickhouse/clickhouse-server:25.1)
- Single-node is sufficient for most production workloads
- Memory: 4 GB minimum for meaningful production use; 16 GB+ recommended for high-throughput
- Storage: SSD-backed storage; ClickHouse is I/O sensitive
- Network: reachable on `CHRONOTRACE_CLICKHOUSE_JDBC_URL` (default `jdbc:clickhouse://clickhouse:8123/default`)
- Authentication: username + password strongly recommended

```
# docker-compose excerpt for ClickHouse
clickhouse:
  image: clickhouse/clickhouse-server:25.1
  ports:
    - "8123:8123"
    - "9000:9000"
  environment:
    CLICKHOUSE_DB: chronotrace
    CLICKHOUSE_PASSWORD: ${CLICKHOUSE_PASSWORD}
  volumes:
    - clickhouse-data:/var/lib/clickhouse

volumes:
  clickhouse-data:
```

### Docker Compose (full shared stack)
```yaml
services:
  chronotrace-server:
    build:
      context: .
      dockerfile: chronotrace-server/Dockerfile
    ports:
      - "8080:8080"
    depends_on:
      - clickhouse
      - valkey
    environment:
      CHRONOTRACE_AUTH_MODE: apiKey
      CHRONOTRACE_BIND_HOST: 0.0.0.0
      CHRONOTRACE_STORAGE_MODE: clickhouse
      CHRONOTRACE_CLICKHOUSE_JDBC_URL: jdbc:clickhouse://clickhouse:8123/default
      CHRONOTRACE_CLICKHOUSE_DATABASE: chronotrace
      CHRONOTRACE_CLICKHOUSE_USERNAME: ${CLICKHOUSE_USERNAME}
      CHRONOTRACE_CLICKHOUSE_PASSWORD: ${CLICKHOUSE_PASSWORD}
      CHRONOTRACE_CLICKHOUSE_CONNECT_TIMEOUT_MS: 5000
      CHRONOTRACE_VALKEY_HOST: valkey
      CHRONOTRACE_VALKEY_PORT: 6379
      CHRONOTRACE_VALKEY_DATABASE: 0
      CHRONOTRACE_VALKEY_PASSWORD: ${VALKEY_PASSWORD}
      CHRONOTRACE_VALKEY_KEY_PREFIX: chronotrace
      CHRONOTRACE_API_KEYS: ${CHRONOTRACE_API_KEYS}
      CHRONOTRACE_RETENTION_LOGS_DAYS: 30
      CHRONOTRACE_RETENTION_SPANS_DAYS: 30
      CHRONOTRACE_RETENTION_FRAMES_DAYS: 7
```

### Auth configuration

#### apiKey mode
```bash
CHRONOTRACE_AUTH_MODE=apiKey
CHRONOTRACE_API_KEYS=key1,key2,key3  # comma-separated list of valid keys
```
Clients send the key in the `X-Api-Key` header:
```http
GET /health HTTP/1.1
X-Api-Key: key1
```

#### bearer mode
```bash
CHRONOTRACE_AUTH_MODE=bearer
CHRONOTRACE_BEARER_TOKENS=token1,token2  # comma-separated list of valid tokens
```
Clients send the token in the `Authorization` header:
```http
GET /health HTTP/1.1
Authorization: Bearer token1
```

#### mTLS mode
```bash
CHRONOTRACE_AUTH_MODE=mTLS
CHRONOTRACE_TLS_CERT_PATH=/etc/chronotrace/server.crt
CHRONOTRACE_TLS_KEY_PATH=/etc/chronotrace/server.key
CHRONOTRACE_TLS_CA_PATH=/etc/chronotrace/ca.crt
```
Clients must present a valid certificate signed by the configured CA.

### TLS requirements for shared
- TLS is **strongly recommended** for all non-local deployments.
- Terminate TLS at a reverse proxy (e.g., Caddy, nginx, Traefik) or at a cloud load balancer.
- The ChronoTrace server itself does **not** terminate TLS by default.
- Minimum TLS version: 1.2. Prefer TLS 1.3.
- Perfect forward secrecy is required for production.

### Horizontal scaling
- The ChronoTrace server is stateless in shared mode (all state in ClickHouse/Valkey).
- Run multiple replicas behind a load balancer with sticky sessions not required.
- SDK clients should implement retry with backoff; `MAX_RETRY_DELAY_MS` SDK config applies.
- No built-in rate limiting at the server layer — rely on the auth layer (apiKey quotas) or an upstream API gateway.

---

## Profile Comparison Summary

| Feature | Local | Shared |
|---|---|---|
| Ingest endpoint | `POST /api/v1/ingest` | `POST /api/v1/ingest` + auth |
| Search endpoint | `POST /api/v1/logs/search` | `POST /api/v1/logs/search` + auth |
| WebSocket ingest | `WS /api/v1/ingest/ws` | `WS /api/v1/ingest/ws` + auth |
| Health endpoint | `GET /health` | `GET /health` (no auth) |
| Purge endpoint | `POST /api/v1/purge` | `POST /api/v1/purge` + auth |
| Remote rules | `GET/POST /api/v1/remote-rules` | `GET/POST /api/v1/remote-rules` + auth |
| MCP endpoint | `POST /mcp` | `POST /mcp` + auth |
| Metrics | Not exposed | Implement via reverse proxy scraping |
| Queue pressure visibility | Not applicable | Via Valkey `LLEN` / SDK health signals |
| Dropped event accounting | In-process only | Valkey purge state + SDK health signals |
| Failure recovery | Restart resets state (file mode) | Restart reattaches to existing ClickHouse/Valkey |
| Retention | In-process pruning | In-process pruning |
| Data durability | Disk (file mode) | ClickHouse + Valkey AOF |

---

## Decision Guide

1. **Team size = 1, no sensitive data, no persistence needed** → local profile, `none` auth, file mode.
2. **Team > 1, or data must survive restarts, or you need purge job visibility** → shared profile, `apiKey` or `bearer` auth, clickhouse mode.
3. **High throughput, multi-region, compliance data** → shared profile + managed ClickHouse (Altinity Cloud, ClickHouse Cloud) + Valkey (ElastiCache, MemoryDB).
4. **Internet-exposed deployment** → shared profile + TLS termination + mTLS auth.
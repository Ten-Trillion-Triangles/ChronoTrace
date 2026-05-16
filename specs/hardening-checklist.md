# Hardening Checklist

Last reviewed: 2026-05-16

This checklist captures the security and operational hardening requirements for ChronoTrace in shared/non-local production deployments. Local single-node deployments with `auth_mode=none` are out of scope for most of these items but the TLS and secret sections still apply if the server is reachable from any network other than localhost.

---

## TLS

- [ ] **Terminate TLS at a reverse proxy or load balancer.** The ChronoTrace Ktor server does not terminate TLS natively. Deploy behind Caddy, nginx, Traefik, or a cloud LB.
- [ ] Enforce **TLS 1.2 minimum**, preferably TLS 1.3.
- [ ] Enable **perfect forward secrecy (PFS)** — use ECDHE or DHE cipher suites.
- [ ] Configure an **HSTS policy** on the reverse proxy for browser-accessible endpoints.
- [ ] If using WebSocket ingest (`WS /api/v1/ingest/ws`), ensure the proxy passes the `Sec-WebSocket-Protocol: json` header and terminates WSS.
- [ ] Validate that the `/.well-known/acme-challenge/` path is accessible if using Let's Encrypt.

---

## Secret Handling

- [ ] **Never commit secrets to version control.** Use `.env.example` (checked in) for template, `.env` (in `.gitignore`) for actual values.
- [ ] Inject secrets via **environment variables** at container runtime. Never bake secrets into images.
- [ ] For Kubernetes: use **Kubernetes Secrets** mounted as env vars or volumes, or integrate with an external secrets manager (HashiCorp Vault, AWS Secrets Manager, GCP Secret Manager).
- [ ] Rotate secrets on a schedule: ClickHouse password, Valkey password, API keys.
- [ ] After rotation, restart the ChronoTrace server process to pick up new credentials.

### Secrets used by ChronoTrace

| Secret | Env var | Notes |
|---|---|---|
| ClickHouse password | `CHRONOTRACE_CLICKHOUSE_PASSWORD` | Only if ClickHouse auth is enabled |
| Valkey password | `CHRONOTRACE_VALKEY_PASSWORD` | Only if Valkey auth is enabled |
| API key list | `CHRONOTRACE_API_KEYS` | Only in `apiKey` auth mode |
| Bearer token list | `CHRONOTRACE_BEARER_TOKENS` | Only in `bearer` auth mode |
| TLS server key | `CHRONOTRACE_TLS_KEY_PATH` | Only in `mTLS` auth mode |

---

## Auth Provider Configuration

### `none` (local only)
- [ ] Server must be bound to a **trusted network interface** (`127.0.0.1` or `localhost`) for local dev.
- [ ] If running in Docker, bind to `127.0.0.1` (not `0.0.0.0`) unless the container network is explicitly firewalled from untrusted access.
- [ ] **Never** expose a no-auth ChronoTrace server directly to the internet.

### `apiKey`
- [ ] Generate cryptographically random keys (minimum 256-bit entropy).
- [ ] Store keys in a secrets manager, not in plain-text config files.
- [ ] Distribute keys to SDK clients via secure channel.
- [ ] Rotate keys without downtime by using a comma-separated list: add the new key before removing the old one.
- [ ] SDK clients must send the key in the `X-Api-Key` header on every request.
- [ ] Consider per-application keys to allow key revocation per SDK client.

### `bearer`
- [ ] Tokens should be JWTs signed by an external identity provider, or opaque tokens from a secrets manager.
- [ ] Token validation must happen at the ChronoTrace server layer or at a gateway that forwards the validated identity.
- [ ] Reject requests with missing or malformed `Authorization: Bearer` headers.

### `mTLS`
- [ ] Distribute CA certificates to all SDK clients.
- [ ] Validate that client certificates are signed by the configured CA.
- [ ] Ensure certificate expiry monitoring is in place.

---

## Network / Transport

- [ ] Place ChronoTrace server behind a **network firewall** or security group that permits only required inbound ports (8080 for HTTP, optionally 8443 for HTTPS via proxy).
- [ ] ClickHouse and Valkey ports (8123, 9000, 6379) must **not** be directly reachable from outside the container network.
- [ ] Use **network isolation** (Docker networks, Kubernetes namespaces) to separate ChronoTrace from other workloads.
- [ ] If deploying on a cloud provider, use **private subnets** for database connectivity and restrict egress appropriately.

---

## Input Validation

- [ ] The `fields_json`, `call_stack_json`, `locals_json`, and `serialization_metadata_json` fields accepted by the ingest endpoint are JSON blobs — they must be validated as valid JSON before insertion.
- [ ] `SearchLogsRequest` parameters (`startTimeUtc`, `endTimeUtc`, `limit`) must be validated: time ranges should not exceed 90 days in a single query, `limit` is coerced to 1–500.
- [ ] Purge selectors must be validated against the supported field allowlist — currently `appId`, `environment`, `traceId`, `spanId` in ClickHouse mode.
- [ ] Long field values in logs (`message`, `fields_json`) are stored as-is; ensure SDK clients truncate oversized fields before sending (SDKs do this by default with `MAX_MESSAGE_LENGTH` and `MAX_FIELDS_JSON_LENGTH`).

---

## Data Redaction

- [ ] **SDK side**: The TypeScript and KMP SDKs redact fields marked in `SensitiveFieldConfig` before sending. This happens client-side — the server does **not** re-redact received data.
- [ ] **Server side**: The server stores `fields_json` as a raw JSON string. If redaction is missed in the SDK, it will be stored. Operators should audit SDK redaction configuration.
- [ ] Purge (`POST /api/v1/purge`) removes data permanently from ClickHouse. There is **no** undelete. Run purge jobs against specific selectors (appId, environment) to target redaction requests.
- [ ] The server does not encrypt data at rest. Rely on ClickHouse encryption at the filesystem/disk level or on the storage layer (LUKS, cloud provider EBS encryption).

---

## Operational Safeguards

### Retention enforcement
- [ ] Set `CHRONOTRACE_RETENTION_LOGS_DAYS`, `CHRONOTRACE_RETENTION_SPANS_DAYS`, and `CHRONOTRACE_RETENTION_FRAMES_DAYS` appropriately for your data classification and compliance requirements.
- [ ] Retention runs on every ingest in the server process — there is no background cron. Records older than the retention window are pruned at the time of the next ingest event.
- [ ] For high-volume deployments, consider adding a scheduled enforcement job to prevent unbounded growth between ingest bursts.

### Purge job state (shared mode only)
- [ ] Purge job state is stored in Valkey. If Valkey is unavailable, purge job submissions will fail and existing job status queries will return errors.
- [ ] Monitor Valkey connectivity as part of the operational health stack.

### Graceful shutdown
- [ ] On SIGTERM, the Ktor Netty server initiates a graceful shutdown (no new connections accepted, in-flight requests completed up to the timeout).
- [ ] SDK clients flush pending buffers on SIGTERM — ensure the SDK shutdown hook is called before the process exits.

### Crash behavior
- [ ] If the JVM crashes, in-flight events in SDK buffers will be lost. SDKs expose `fatalFlush` state for observability.
- [ ] File mode storage: data is persisted to disk on every ingest. A crash may lose the last partial write.
- [ ] ClickHouse mode: data is committed in a transaction per batch. A crash may lose the last in-flight batch if the connection was mid-commit.

---

## Logging and Monitoring

- [ ] Server logs are written to stdout (via Logback `logback-classic`) — capture via Docker log drivers or a log collector (Fluentd, Vector, Promtail).
- [ ] Access logs are enabled via Ktor `CallLogging` plugin — include these in your log pipeline for security auditing.
- [ ] Set log level to `INFO` in production. `DEBUG` produces very high volume and should only be used during active troubleshooting.
- [ ] The `/health` endpoint returns structured JSON — scrape this with your monitoring system (Prometheus + Grafana, DataDog, etc.).

---

## Dependency Updates

- [ ] Subscribe to security advisories for: Eclipse Temurin (JRE), ClickHouse JDBC driver, Ktor, Netty, Jedis (Valkey client), kotlinx.serialization.
- [ ] Pin base images in Dockerfile to a specific tag, not `latest`, to control when updates are applied.
- [ ] Establish a monthly review cycle for dependency updates.

---

## Compliance Notes

- ChronoTrace stores application log data, which may include PII depending on SDK configuration. Operators are responsible for:
  - Ensuring SDK redaction is correctly configured before ingest.
  - Setting retention windows that meet data retention policies.
  - Using purge jobs to respond to deletion/erasure requests.
- There is **no** built-in encryption of data at rest. Use storage-layer encryption if compliance requires it.
- The purge operation in ClickHouse removes data permanently. Test purge on a non-production database before running against production.
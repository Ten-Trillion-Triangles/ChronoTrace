# ChronoTrace Production Deployment Guide

This guide covers deploying ChronoTrace in production environments: Docker, Kubernetes, and bare-metal. It is intended for DevOps engineers, SREs, and release managers preparing ChronoTrace for production workloads.

**Version:** 1.0.0
**Server:** Kotlin/Ktor (Netty engine), JDK 21

---

## Table of Contents

1. [System Requirements](#1-system-requirements)
2. [Docker Deployment](#2-docker-deployment)
3. [Kubernetes Deployment](#3-kubernetes-deployment)
4. [Bare-Metal Deployment](#4-bare-metal-deployment)
5. [Environment Variables Reference](#5-environment-variables-reference)
6. [TLS Configuration](#6-tls-configuration)
7. [Secrets Management](#7-secrets-management)
8. [ClickHouse Sizing](#8-clickhouse-sizing)
9. [SDK Memory Configuration](#9-sdk-memory-configuration)
10. [Monitoring Setup](#10-monitoring-setup)
11. [Backup and Restore](#11-backup-and-restore)

---

## 1. System Requirements

### Minimum Requirements

| Resource | Minimum | Recommended |
|----------|---------|-------------|
| CPU | 2 cores | 4+ cores |
| Memory (JVM heap) | 512 MB | 2 GB |
| Disk (file storage) | 10 GB | 50+ GB SSD |
| JDK | 21 | 21 (LTS) |

### Supported Platforms

- Linux (amd64, arm64)
- Docker containers (Linux base image)
- Kubernetes 1.27+
- Bare-metal or VMs running Linux

> **Note:** ChronoTrace is developed and tested on Linux. Windows/macOS deployments are unsupported for production use.

---

## 2. Docker Deployment

### Base Image

The official `Dockerfile` uses `eclipse-temurin:21-jdk`:

```dockerfile
FROM eclipse-temurin:21-jdk

WORKDIR /app
COPY . /app

RUN chmod +x ./gradlew && \
    ./gradlew :chronotrace-server:installDist \
    --no-configuration-cache \
    -Dorg.gradle.java.home=/opt/java/openjdk

CMD ["./chronotrace-server/build/install/chronotrace-server/bin/chronotrace-server"]
```

To build the image:

```bash
docker build -f chronotrace-server/Dockerfile -t chronotrace/server:1.0.0 .
```

### Resource Limits

Configure CPU and memory limits based on your expected ingest load. The JVM default heap is controlled by `org.gradle.jvmargs` in `gradle.properties` (`-Xmx2g`). For production, set explicit limits:

```yaml
# docker-compose.yml excerpt
services:
  chronotrace-server:
    deploy:
      resources:
        limits:
          cpus: '2'
          memory: 2G
        reservations:
          cpus: '1'
          memory: 1G
```

### Health Check Configuration

ChronoTrace exposes a `/health` endpoint (no authentication required). Configure your container health check to use it:

```yaml
healthcheck:
  test: ["CMD", "curl", "-f", "http://localhost:8080/health"]
  interval: 30s
  timeout: 10s
  retries: 3
  start_period: 30s
```

The health response includes `status`, `storageMode`, `version`, and `uptimeSeconds`:

```json
{
  "status": "ok",
  "storageMode": "clickhouse",
  "version": "1.0.0",
  "uptimeSeconds": 86400
}
```

### Volume Mounting

For file-based storage, mount a persistent volume to preserve trace data across restarts:

```yaml
volumes:
  - chronotrace-data:/var/lib/chronotrace/data
```

### Production docker-compose.yml

The following is a production-oriented configuration with ClickHouse and Valkey:

```yaml
services:
  chronotrace-server:
    image: chronotrace/server:1.0.0
    ports:
      - "8080:8080"
    environment:
      CHRONOTRACE_AUTH_MODE: apiKey
      CHRONOTRACE_STORAGE_MODE: clickhouse
      CHRONOTRACE_BIND_HOST: 0.0.0.0
      PORT: 8080
      CHRONOTRACE_CLICKHOUSE_JDBC_URL: jdbc:clickhouse://clickhouse:8123/default
      CHRONOTRACE_CLICKHOUSE_DATABASE: chronotrace
      CHRONOTRACE_CLICKHOUSE_USERNAME: default
      CHRONOTRACE_CLICKHOUSE_PASSWORD: "${CHRONOTRACE_CLICKHOUSE_PASSWORD}"
      CHRONOTRACE_CLICKHOUSE_CONNECT_TIMEOUT_MS: 10000
      CHRONOTRACE_CLICKHOUSE_INGEST_QUEUE_CAPACITY: 10000
      CHRONOTRACE_ASYNC_INSERT: "true"
      CHRONOTRACE_BOUNCE_ON_REJECTED: "true"
      CHRONOTRACE_RETENTION_LOGS_DAYS: 30
      CHRONOTRACE_RETENTION_SPANS_DAYS: 30
      CHRONOTRACE_RETENTION_FRAMES_DAYS: 7
      CHRONOTRACE_VALKEY_HOST: valkey
      CHRONOTRACE_VALKEY_PORT: 6379
      CHRONOTRACE_VALKEY_PASSWORD: "${CHRONOTRACE_VALKEY_PASSWORD}"
      CHRONOTRACE_WS_IDLE_TIMEOUT_MS: 60000
    volumes:
      - chronotrace-data:/var/lib/chronotrace/data
    deploy:
      resources:
        limits:
          cpus: '2'
          memory: 2G
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/health"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 30s
    depends_on:
      clickhouse:
        condition: service_healthy
      valkey:
        condition: service_started

  clickhouse:
    image: clickhouse/clickhouse-server:25.1
    ports:
      - "18123:8123"
      - "19000:9000"
    volumes:
      - clickhouse-data:/var/lib/clickhouse
      - ./clickhouse-user-config.xml:/etc/clickhouse-server/users.d/custom-user.xml:ro
    environment:
      CLICKHOUSE_DB: default
    ulimits:
      nofile:
        soft: 262144
        hard: 262144

  valkey:
    image: valkey/valkey:8.0
    ports:
      - "16379:6379"
    volumes:
      - valkey-data:/data
    command: valkey-server --maxmemory 256mb --maxmemory-policy allkeys-lru

volumes:
  chronotrace-data:
  clickhouse-data:
  valkey-data:
```

---

## 3. Kubernetes Deployment

### Resource Sizing

| Workload Profile | CPU Request | CPU Limit | Memory Request | Memory Limit |
|-----------------|-------------|-----------|----------------|--------------|
| Light (< 1000 logs/min) | 500m | 1000m | 512Mi | 1Gi |
| Medium (1000–10000 logs/min) | 1 | 2 | 1Gi | 2Gi |
| Heavy (> 10000 logs/min) | 2 | 4 | 2Gi | 4Gi |

### Pod Disruption Budget

To ensure high availability during node maintenance:

```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: chronotrace-server-pdb
spec:
  maxUnavailable: 1
  selector:
    matchLabels:
      app: chronotrace-server
```

### ConfigMap

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: chronotrace-server-config
data:
  CHRONOTRACE_AUTH_MODE: "apiKey"
  CHRONOTRACE_STORAGE_MODE: "clickhouse"
  CHRONOTRACE_BIND_HOST: "0.0.0.0"
  PORT: "8080"
  CHRONOTRACE_CLICKHOUSE_JDBC_URL: "jdbc:clickhouse://clickhouse.default.svc.cluster.local:8123/default"
  CHRONOTRACE_CLICKHOUSE_DATABASE: "chronotrace"
  CHRONOTRACE_CLICKHOUSE_USERNAME: "default"
  CHRONOTRACE_CLICKHOUSE_CONNECT_TIMEOUT_MS: "10000"
  CHRONOTRACE_CLICKHOUSE_INGEST_QUEUE_CAPACITY: "10000"
  CHRONOTRACE_ASYNC_INSERT: "true"
  CHRONOTRACE_BOUNCE_ON_REJECTED: "true"
  CHRONOTRACE_RETENTION_LOGS_DAYS: "30"
  CHRONOTRACE_RETENTION_SPANS_DAYS: "30"
  CHRONOTRACE_RETENTION_FRAMES_DAYS: "7"
  CHRONOTRACE_WS_IDLE_TIMEOUT_MS: "60000"
```

### Secret (TLS Keystore)

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: chronotrace-server-secret
type: Opaque
stringData:
  CHRONOTRACE_CLICKHOUSE_PASSWORD: "your-clickhouse-password"
  CHRONOTRACE_VALKEY_PASSWORD: "your-valkey-password"
  TLS_KEYSTORE_PASSWORD: "your-keystore-password"
```

### Secret (API Keys)

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: chronotrace-server-apikeys
type: Opaque
stringData:
  CHRONOTRACE_API_KEYS: "ctk_prod_key_1,ctk_prod_key_2,ctk_prod_key_3"
```

### Deployment YAML

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: chronotrace-server
  labels:
    app: chronotrace-server
spec:
  replicas: 2
  selector:
    matchLabels:
      app: chronotrace-server
  template:
    metadata:
      labels:
        app: chronotrace-server
    spec:
      containers:
        - name: chronotrace-server
          image: chronotrace/server:1.0.0
          ports:
            - containerPort: 8080
              name: http
          envFrom:
            - configMapRef:
                name: chronotrace-server-config
            - secretRef:
                name: chronotrace-server-secret
            - secretRef:
                name: chronotrace-server-apikeys
          resources:
            requests:
              cpu: 1
              memory: 1Gi
            limits:
              cpu: 2
              memory: 2Gi
          readinessProbe:
            httpGet:
              path: /health
              port: 8080
            initialDelaySeconds: 10
            periodSeconds: 10
            failureThreshold: 3
          livenessProbe:
            httpGet:
              path: /health
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 30
            failureThreshold: 5
          volumeMounts:
            - name: chronotrace-data
              mountPath: /var/lib/chronotrace/data
      volumes:
        - name: chronotrace-data
          persistentVolumeClaim:
            claimName: chronotrace-data-pvc
```

### Service

```yaml
apiVersion: v1
kind: Service
metadata:
  name: chronotrace-server
spec:
  type: ClusterIP
  ports:
    - port: 8080
      targetPort: 8080
      protocol: TCP
      name: http
  selector:
    app: chronotrace-server
```

For external access, use a `LoadBalancer` service or an Ingress controller.

### Horizontal Pod Autoscaler

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: chronotrace-server-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: chronotrace-server
  minReplicas: 2
  maxReplicas: 10
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
    - type: Pods
      pods:
        metric:
          name: chronotrace_ingest_queue_depth
        target:
          type: AverageValue
          averageValue: "1000"
```

### TLS Secret (Keystore)

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: chronotrace-tls
type: Opaque
data:
  keystore.jks: <base64-encoded-keystore.jks>
```

Mount the keystore as a volume and set the following environment variables:

```yaml
env:
  - name: TLS_KEYSTORE_PATH
    value: "/mnt/tls/keystore.jks"
  - name: TLS_KEYSTORE_PASSWORD
    valueFrom:
      secretKeyRef:
        name: chronotrace-tls
        key: keystore-password
  - name: TLS_KEY_ALIAS
    value: "chronotrace"
```

---

## 4. Bare-Metal Deployment

### System Requirements

| Resource | Minimum | Recommended |
|----------|---------|-------------|
| CPU | 2 cores | 4+ cores |
| Memory | 1 GB | 4 GB |
| Disk | 20 GB | 100+ GB SSD |
| OS | Ubuntu 22.04+ / RHEL 9+ | Ubuntu 22.04+ / RHEL 9+ |

### User and Permissions Setup

Create a dedicated service user:

```bash
# Create chronotrace user and group
sudo groupadd -r chronotrace
sudo useradd -r -g chronotrace -d /var/lib/chronotrace -s /usr/sbin/nologin chronotrace

# Create data directory
sudo mkdir -p /var/lib/chronotrace/data
sudo chown -R chronotrace:chronotrace /var/lib/chronotrace
```

### systemd Service Configuration

Create `/etc/systemd/system/chronotrace.service`:

```ini
[Unit]
Description=ChronoTrace Server
Documentation=https://github.com/your-org/chronotrace
After=network.target

[Service]
Type=simple
User=chronotrace
Group=chronotrace
WorkingDirectory=/var/lib/chronotrace
Environment=JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
EnvironmentFile=/etc/chronotrace/environment
ExecStart=/var/lib/chronotrace/bin/chronotrace-server
Restart=on-failure
RestartSec=10
StandardOutput=journal
StandardError=journal
LimitNOFILE=65536

# Graceful shutdown: give it 30 seconds to finish in-flight requests
TimeoutStopSec=30

[Install]
WantedBy=multi-user.target
```

Create `/etc/chronotrace/environment`:

```bash
# Server binding
CHRONOTRACE_BIND_HOST=0.0.0.0
PORT=8080
CHRONOTRACE_AUTH_MODE=apiKey

# Storage
CHRONOTRACE_STORAGE_MODE=clickhouse
CHRONOTRACE_DATA_DIR=/var/lib/chronotrace/data
CHRONOTRACE_CLICKHOUSE_JDBC_URL=jdbc:clickhouse://localhost:8123/default
CHRONOTRACE_CLICKHOUSE_DATABASE=chronotrace
CHRONOTRACE_CLICKHOUSE_USERNAME=default
CHRONOTRACE_CLICKHOUSE_PASSWORD=your_password
CHRONOTRACE_CLICKHOUSE_CONNECT_TIMEOUT_MS=10000
CHRONOTRACE_CLICKHOUSE_INGEST_QUEUE_CAPACITY=10000
CHRONOTRACE_ASYNC_INSERT=true
CHRONOTRACE_BOUNCE_ON_REJECTED=true

# Retention
CHRONOTRACE_RETENTION_LOGS_DAYS=30
CHRONOTRACE_RETENTION_SPANS_DAYS=30
CHRONOTRACE_RETENTION_FRAMES_DAYS=7

# Valkey (rate limiting)
CHRONOTRACE_VALKEY_HOST=localhost
CHRONOTRACE_VALKEY_PORT=6379
CHRONOTRACE_VALKEY_PASSWORD=your_valkey_password

# WebSocket
CHRONOTRACE_WS_IDLE_TIMEOUT_MS=60000

# TLS (enable for HTTPS — see Section 6)
# TLS_KEYSTORE_PATH=/var/lib/chronotrace/tls/keystore.jks
# TLS_KEYSTORE_PASSWORD=your_keystore_password
# TLS_KEY_ALIAS=chronotrace
# CHRONOTRACE_TLS_SSL_PORT=8443
```

Enable and start the service:

```bash
sudo systemctl daemon-reload
sudo systemctl enable chronotrace
sudo systemctl start chronotrace

# Check status
sudo systemctl status chronotrace

# View logs
sudo journalctl -u chronotrace -f
```

### Log Rotation

Configure log rotation for ChronoTrace logs. Create `/etc/logrotate.d/chronotrace`:

```
/var/log/chronotrace/*.log {
    daily
    rotate 14
    compress
    delaycompress
    missingok
    notifempty
    create 0640 chronotrace chronotrace
    sharedscripts
    postrotate
        systemctl reload chronotrace > /dev/null 2>&1 || true
    endscript
}
```

---

## 5. Environment Variables Reference

### Server Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `8080` | HTTP server port (plain HTTP) |
| `CHRONOTRACE_TLS_SSL_PORT` | (same as `PORT`) | HTTPS port when TLS is configured |
| `CHRONOTRACE_BIND_HOST` | `127.0.0.1` | Interface to bind to. Use `0.0.0.0` for all interfaces |
| `CHRONOTRACE_AUTH_MODE` | `none` | Auth mode: `none`, `apiKey`, `bearer` |
| `CHRONOTRACE_STORAGE_MODE` | `file` | Storage backend: `memory`, `file`, `clickhouse` |
| `CHRONOTRACE_DATA_DIR` | (current dir) | Directory for file-based storage |
| `CHRONOTRACE_WS_IDLE_TIMEOUT_MS` | `60000` | WebSocket idle timeout in ms. Set `0` to disable |

### Authentication

| Variable | Default | Description |
|----------|---------|-------------|
| `CHRONOTRACE_API_KEYS` | (none) | Comma-separated list of API keys for `apiKey` auth mode |
| `CHRONOTRACE_BEARER_TOKENS` | (none) | Comma-separated list of bearer tokens for `bearer` auth mode |

### Retention (TTL in days)

| Variable | Default | Description |
|----------|---------|-------------|
| `CHRONOTRACE_RETENTION_LOGS_DAYS` | `30` | Log record retention period |
| `CHRONOTRACE_RETENTION_SPANS_DAYS` | `30` | Span record retention period |
| `CHRONOTRACE_RETENTION_FRAMES_DAYS` | `7` | Frame snapshot retention period |

### ClickHouse Storage

| Variable | Default | Description |
|----------|---------|-------------|
| `CHRONOTRACE_CLICKHOUSE_JDBC_URL` | (none) | JDBC URL, e.g. `jdbc:clickhouse://localhost:8123/default` |
| `CHRONOTRACE_CLICKHOUSE_DATABASE` | `chronotrace` | Database name |
| `CHRONOTRACE_CLICKHOUSE_USERNAME` | (none) | Username |
| `CHRONOTRACE_CLICKHOUSE_PASSWORD` | (none) | Password |
| `CHRONOTRACE_CLICKHOUSE_CONNECT_TIMEOUT_MS` | `5000` | Connection timeout in ms |
| `CHRONOTRACE_CLICKHOUSE_INGEST_QUEUE_CAPACITY` | `0` | Async ingest queue size. `0` = synchronous (blocking) ingest |
| `CHRONOTRACE_CLICKHOUSE_INGEST_QUEUE_TIMEOUT_MS` | `5000` | Queue offer timeout in ms |
| `CHRONOTRACE_ASYNC_INSERT` | `false` | Use ClickHouse async inserts (`true`/`false`) |
| `CHRONOTRACE_BOUNCE_ON_REJECTED` | `true` | Circuit breaker: reject new batches when queue is full. Set `false` to disable |

### Valkey (Rate Limiting)

| Variable | Default | Description |
|----------|---------|-------------|
| `CHRONOTRACE_VALKEY_HOST` | (none) | Valkey/Redis host |
| `CHRONOTRACE_VALKEY_PORT` | `6379` | Port |
| `CHRONOTRACE_VALKEY_DATABASE` | `0` | Database number |
| `CHRONOTRACE_VALKEY_PASSWORD` | (none) | Password |
| `CHRONOTRACE_VALKEY_KEY_PREFIX` | `chronotrace` | Key prefix for all ChronoTrace keys |

### TLS / HTTPS

| Variable | Default | Description |
|----------|---------|-------------|
| `TLS_KEYSTORE_PATH` | (none) | Path to JKS keystore file |
| `TLS_KEYSTORE_PASSWORD` | (none) | Keystore password |
| `TLS_KEY_ALIAS` | `chronotrace` | Alias of the server key entry in the keystore |
| `TLS_TRUSTSTORE_PATH` | (none) | Path to JKS truststore file (for mTLS) |
| `TLS_TRUSTSTORE_PASSWORD` | (none) | Truststore password |
| `CHRONOTRACE_TLS_SSL_PORT` | (same as `PORT`) | HTTPS port when TLS is configured |

See [Section 6 — TLS Configuration](#6-tls-configuration) for the full TLS setup guide.

---

## 6. TLS Configuration

ChronoTrace supports TLS natively — when both `TLS_KEYSTORE_PATH` and `TLS_KEYSTORE_PASSWORD` are set, the server binds an additional HTTPS connector and serves traffic over TLS without needing a reverse proxy. The TLS material is loaded once at startup and used to build a Netty `KeyManagerFactory` and (optionally) a `TrustManagerFactory` for mTLS.

### Environment Variables

| Variable | Required | Description |
|----------|----------|-------------|
| `TLS_KEYSTORE_PATH` | Yes (to enable TLS) | Path to a JKS keystore containing the server certificate + private key |
| `TLS_KEYSTORE_PASSWORD` | Yes (to enable TLS) | Password for the keystore |
| `TLS_KEY_ALIAS` | No (default `chronotrace`) | Alias of the key entry inside the keystore |
| `TLS_TRUSTSTORE_PATH` | No | Path to a JKS truststore (required only for mTLS) |
| `TLS_TRUSTSTORE_PASSWORD` | No | Password for the truststore |
| `CHRONOTRACE_TLS_SSL_PORT` | No (default = `PORT`) | Port the HTTPS connector binds to |

When TLS is enabled, the server prints a single line on startup confirming the configuration:

```
ChronoTrace: HTTPS enabled on port 8443 (keyStore=/var/lib/chronotrace/tls/keystore.jks, keyAlias=chronotrace)
```

When TLS is **not** enabled:

```
ChronoTrace: HTTP only on port 8080 (set TLS_KEYSTORE_PATH and TLS_KEYSTORE_PASSWORD to enable HTTPS)
```

### Verifying TLS is Wired

A unit test (`TlsWiringTest`) in the `chronotrace-server` module exercises the full end-to-end path: it generates a JKS with `keytool`, builds a `TlsConfig` from the env vars, applies it to a `NettyApplicationEngine.Configuration`, and asserts that the HTTPS connector on the engine has the loaded `KeyStore` / `TrustStore` / `keyAlias` / `port`. Run it with:

```bash
./gradlew :chronotrace-server:test --tests "*TlsWiringTest*"
```

### Generating a Self-Signed Keystore for Dev

JDK's `keytool` can generate a self-signed keystore. The minimal command for local dev is:

```bash
keytool -genkeypair -alias chronotrace -keyalg RSA -keysize 2048 \
  -dname "CN=localhost,O=ChronoTrace,C=US" \
  -keystore chronotrace.jks -storepass changeit -validity 365
```

For production, use a CA-signed certificate (see [Importing a CA-Signed Certificate](#importing-a-ca-signed-certificate)) and an explicit `-keypass`:

```bash
keytool -genkeypair \
  -alias chronotrace \
  -keyalg RSA \
  -keysize 2048 \
  -validity 365 \
  -keystore /var/lib/chronotrace/tls/keystore.jks \
  -storepass changeit \
  -keypass changeit \
  -dname "CN=chronotrace.example.com, OU=Observability, O=YourOrg, L=City, ST=State, C=US"
```

### Importing a CA-Signed Certificate

```bash
# Import the CA certificate chain
keytool -importcert \
  -trustcacerts \
  -alias ca-cert \
  -file /path/to/ca-chain.crt \
  -keystore /var/lib/chronotrace/tls/keystore.jks \
  -storepass changeit

# Import the server certificate and private key
keytool -importcert \
  -alias chronotrace \
  -file /path/to/server.crt \
  -keystore /var/lib/chronotrace/tls/keystore.jks \
  -storepass changeit
```

### Configuring TLS in ChronoTrace

Set the following environment variables to enable TLS:

```bash
TLS_KEYSTORE_PATH=/var/lib/chronotrace/tls/keystore.jks
TLS_KEYSTORE_PASSWORD=changeit
TLS_KEY_ALIAS=chronotrace
CHRONOTRACE_TLS_SSL_PORT=8443
```

For mutual TLS (mTLS), also configure the truststore:

```bash
TLS_TRUSTSTORE_PATH=/var/lib/chronotrace/tls/truststore.jks
TLS_TRUSTSTORE_PASSWORD=changeit
```

---

## 7. Secrets Management

### Kubernetes Secrets

Use Kubernetes `Secret` objects for sensitive values. Reference them in your Deployment via `envFrom`:

```yaml
envFrom:
  - secretRef:
      name: chronotrace-server-secret
```

Never store secrets in ConfigMaps.

### HashiCorp Vault Integration

For HashiCorp Vault, use an init container or a mutating webhook to populate environment variables at startup. Example approach using a Vault Agent sidecar:

```yaml
initContainers:
  - name: vault-agent
    image: hashicorp/vault:1.15
    env:
      - name: VAULT_ADDR
        value: "https://vault.example.com:8200"
    command: ["sh", "-c", "vault agent -config=/vault/config/agent.hcl"]
    volumeMounts:
      - name: vault-config
        mountPath: /vault/config
      - name: secrets
        mountPath: /vault/secrets
```

The agent writes the secrets to `/vault/secrets/` as environment variable files, which are then sourced by the ChronoTrace container.

### General Secrets Practices

- Rotate API keys and passwords regularly (e.g., every 90 days)
- Never commit secrets to version control
- Use different secrets per environment (dev, staging, prod)
- Audit secret access via your secrets provider's audit logs

---

## 8. ClickHouse Sizing

### Resource Guidelines

| Daily Log Volume | CPU | Memory | Disk (ClickHouse data) |
|------------------|-----|--------|------------------------|
| < 1 million | 2 cores | 4 GB | 50 GB |
| 1–10 million | 4 cores | 8 GB | 200 GB |
| 10–100 million | 8 cores | 16 GB | 1 TB |
| 100+ million | 16+ cores | 32+ GB | 5+ TB |

Disk should be SSD-backed for ingest performance. ClickHouse is I/O heavy during merges.

### Retention Storage Calculations

Formula to estimate storage:

```
Storage_per_day = (logs_per_day × avg_log_bytes) + (spans_per_day × avg_span_bytes) + (frames_per_day × avg_frame_bytes)
Total_storage = Storage_per_day × retention_days
```

Example for a medium workload:

- Logs: 10,000/min × 1440 min/day = 14.4M logs/day × ~500 bytes = ~7.2 GB/day
- Spans: ~1M spans/day × ~300 bytes = ~300 MB/day
- Frames: ~100K frames/day × ~5 KB = ~500 MB/day
- **Total: ~8 GB/day** with 30-day retention = **~240 GB**

Add 20% buffer for compression variation and overhead. Monitor `/metrics` `chronotrace_ingest_total` and storage capacity metrics.

### Backup Recommendations

ClickHouse supports native backup via `ALTER TABLE ... FREEZE` and `BACKUP` commands. Recommended approach:

```bash
# Native ClickHouse backup (table freeze)
clickhouse-client --query "ALTER TABLE chronotrace.logs FREEZE"

# Or use clickhouse-backup tool for full backups with off-site transfer
clickhouse-backup create --tables "chronotrace.*"
```

Schedule daily backups. Retention: keep 7 daily, 4 weekly, 12 monthly backups.

For file-based storage, simply back up the `CHRONOTRACE_DATA_DIR` directory using `rsync` or a similar tool.

---

## 9. SDK Memory Configuration

### Buffer Settings

The ChronoTrace SDK buffers records locally before sending to the server. Configure the buffer via `ChronoConfig` (Kotlin) or `ChronoTraceConfig` (TypeScript):

```typescript
// TypeScript
ChronoTrace.init({
    appId: 'my-app',
    serverUrl: 'http://chronotrace.example.com:8080',
    bufferConfig: {
        maxMemoryMB: 50,         // Max memory for buffered records
        flushIntervalMs: 2000,  // Flush every 2 seconds
        overflowStrategy: 'DROP_OLDEST',
    },
});
```

```kotlin
// Kotlin
ChronoTrace.init(ChronoConfig(
    appId = "my-app",
    serverUrl = "http://chronotrace.example.com:8080",
    bufferConfig = BufferConfig(
        maxMemoryMB = 50,
        flushIntervalMs = 2000,
        overflowStrategy = OverflowStrategy.DROP_OLDEST,
    ),
))
```

### Flush on Shutdown

The JVM SDK registers a shutdown hook that calls `flushFatal()` on process exit, ensuring all buffered records are sent before termination. For high-throughput services, also call `ChronoTrace.shutdown()` explicitly before graceful shutdown.

### JVM Memory Recommendations

For JVM-based services using ChronoTrace SDK:

| Service Profile | JVM Heap | Notes |
|----------------|----------|-------|
| Light | 512 MB | Low-volume services, short-lived processes |
| Medium | 1 GB | Standard production services |
| Heavy | 2 GB | High-volume services with large local variable captures |

Increase `maxMemoryMB` buffer config for high-throughput services, but monitor total memory usage to avoid OOM.

---

## 10. Monitoring Setup

### Prometheus Metrics Endpoint

ChronoTrace exposes Prometheus-format metrics at `GET /metrics` (no authentication required):

```
chronotrace_ingest_total{service="my-app"} 15234
chronotrace_ingest_errors_total{service="my-app"} 12
chronotrace_query_latency_ms_bucket{le="10"} 10234
chronotrace_query_latency_ms_bucket{le="50"} 14890
chronotrace_query_latency_ms_bucket{le="+Inf"} 15200
chronotrace_websocket_connections 42
chronotrace_ingest_queue_depth{service="my-app"} 0
```

Add to your `prometheus.yml`:

```yaml
scrape_configs:
  - job_name: 'chronotrace'
    static_configs:
      - targets: ['chronotrace-server:8080']
    metrics_path: /metrics
    scrape_interval: 15s
```

### Key Metrics to Monitor

| Metric | Alert Threshold | Description |
|--------|-----------------|-------------|
| `chronotrace_ingest_total` | Rate drop to 0 | Ingest has stopped |
| `chronotrace_ingest_errors_total` | > 1% of ingest total | Ingest error rate elevated |
| `chronotrace_ingest_queue_depth` | > 5000 (ClickHouse async) | Ingest queue backing up |
| `chronotrace_query_latency_ms` | p99 > 500ms | Query performance degraded |
| `chronotrace_websocket_connections` | Sudden drop to 0 | WebSocket clients disconnected |
| `process_memory_used` | > 90% of limit | Memory pressure |

### Recommended Alerting Rules

```yaml
groups:
  - name: chronotrace
    rules:
      - alert: ChronoTraceIngestDown
        expr: rate(chronotrace_ingest_total[5m]) == 0
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "ChronoTrace ingest has stopped"
      - alert: ChronoTraceHighErrorRate
        expr: rate(chronotrace_ingest_errors_total[5m]) / rate(chronotrace_ingest_total[5m]) > 0.01
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "ChronoTrace ingest error rate > 1%"
      - alert: ChronoTraceHighQueueDepth
        expr: chronotrace_ingest_queue_depth > 5000
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "ChronoTrace ingest queue is backing up"
      - alert: ChronoTraceHighQueryLatency
        expr: histogram_quantile(0.99, rate(chronotrace_query_latency_ms_bucket[5m])) > 500
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "ChronoTrace query p99 latency > 500ms"
```

---

## 11. Backup and Restore

### ClickHouse Backup Procedures

**Option 1: Native Freeze + Copy (recommended)**

```bash
# Trigger table freeze (creates a shadow copy in ClickHouse data directory)
clickhouse-client --query "ALTER TABLE chronotrace.logs FREEZE"
clickhouse-client --query "ALTER TABLE chronotrace.spans FREEZE"
clickhouse-client --query "ALTER TABLE chronotrace.frames FREEZE"

# Copy frozen data to backup location
cp -r /var/lib/clickhouse/data/default/chronotrace/shadow /backup/chronotrace-logs-$(date +%Y%m%d)
```

**Option 2: clickhouse-backup tool**

```bash
# Install clickhouse-backup
# Create a full backup
clickhouse-backup create --tables "chronotrace.*" chronotrace-full-$(date +%Y%m%d)

# List backups
clickhouse-backup list

# Restore from backup
clickhouse-backup restore chronotrace-full-20260527 --tables "chronotrace.*"
```

### File-Based Storage Backup

For file-based storage (`CHRONOTRACE_STORAGE_MODE=file`), back up the data directory:

```bash
# Create a tar archive of the data directory
tar -czf /backup/chronotrace-data-$(date +%Y%m%d).tar.gz /var/lib/chronotrace/data

# Or use rsync for continuous replication
rsync -av /var/lib/chronotrace/data/ user@backup-server:/backup/chronotrace/
```

### Recovery Procedures

**ClickHouse restore:**

```bash
# Stop the ChronoTrace server
kubectl scale deployment chronotrace-server --replicas=0

# Restore from backup
clickhouse-backup restore chronotrace-full-20260527 --tables "chronotrace.*"

# Restart ChronoTrace server
kubectl scale deployment chronotrace-server --replicas=2
```

**File storage restore:**

```bash
# Stop the ChronoTrace server
sudo systemctl stop chronotrace

# Restore from archive
tar -xzf /backup/chronotrace-data-20260527.tar.gz -C /var/lib/chronotrace/

# Fix permissions
sudo chown -R chronotrace:chronotrace /var/lib/chronotrace/data

# Start the ChronoTrace server
sudo systemctl start chronotrace
```

### Backup Schedule

| Backup Type | Frequency | Retention |
|------------|-----------|-----------|
| ClickHouse incremental | Hourly | 24 hours |
| ClickHouse full | Daily | 30 days |
| File storage snapshot | Daily | 30 days |
| Off-site replication | Daily | 90 days |

---

## Quick-Reference Cheat Sheet

```bash
# Start with Docker
docker run -d \
  -p 8080:8080 \
  -e CHRONOTRACE_AUTH_MODE=apiKey \
  -e CHRONOTRACE_STORAGE_MODE=file \
  -e CHRONOTRACE_API_KEYS=ctk_your_key \
  chronotrace/server:1.0.0

# Check health
curl http://localhost:8080/health

# Check metrics
curl http://localhost:8080/metrics

# View logs (journalctl)
sudo journalctl -u chronotrace -f

# Reload after config change
sudo systemctl reload chronotrace
```

---

*End of Deployment Guide*
# ChronoTrace Load Test Suite

This directory contains k6 load tests for the ChronoTrace server.

## Prerequisites

- k6 v0.44.0 or later installed
- ChronoTrace server running and reachable

## Installation

```bash
# Install k6 (if not already installed)
# Option 1: Download binary
curl -sL https://github.com/grafana/k6/releases/download/v0.44.0/k6-v0.44.0-linux-amd64.tar.gz | tar xz
./k6-v0.44.0-linux-amd64/k6 version

# Option 2: macOS
brew install k6

# Option 3: Docker
docker pull grafana/k6:latest
docker run --rm -v $(pwd):/scripts grafana/k6:latest run /scripts/smoke-test.js
```

## Running Tests

All three scripts read their target host/port from k6's `__ENV` mechanism, so
you can point them at any server without editing the file.

### Smoke Test

Basic sanity test to verify the server is functioning correctly.

```bash
# Default: hits http://localhost:8081
k6 run smoke-test.js

# Point at a different host
k6 run -e BASE_URL=http://chronotrace.internal:8081 smoke-test.js
```

**Configuration (overridable via env):**
- `K6_VUS` (default `10`) — virtual users
- `K6_DURATION` (default `1m`) — total test duration
- `BASE_URL` (default `http://localhost:8081`) — target server
- Verifies `/health` returns 200
- Verifies `/api/v1/ingest` accepts valid batches
- Threshold: p95 latency < 500ms

### Stress Test

Discovers the saturation point where error rate exceeds 1%.

```bash
k6 run stress-test.js
```

**Configuration (overridable via env):**
- `BASE_URL` (default `http://localhost:8081`)
- `K6_STAGES` (default ramp from 10 → 100 VUs over 3 minutes; format:
  `"30s:10;30s:20;30s:40;30s:60;30s:80;30s:100"`)
- `K6_BATCH_SIZE` (default `10`)
- `K6_SLEEP` (default `0.1` seconds between iterations)
- Detects and reports saturation point
- Threshold: error rate < 1%

### Ingest Test

Sustained load test for long-term stability verification.

```bash
k6 run ingest-test.js
```

**Configuration (overridable via env):**
- `K6_VUS` (default `50`) — virtual users
- `K6_DURATION` (default `5m`) — sustained load duration
- `BASE_URL` (default `http://localhost:8081`)
- `K6_BATCH_SIZE` (default `10`)
- `K6_SLEEP` (default `0.5` seconds between iterations)
- Sends batches with 10 frame snapshots each
- Threshold: drop rate < 0.1%, P99 latency < 2s

## API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/health` | GET | Health check endpoint |
| `/metrics` | GET | Prometheus metrics endpoint |
| `/api/v1/ingest` | POST | Batch event ingestion (consumes `IngestBatch`) |

## Ingest Payload Format

The payload posted to `/api/v1/ingest` must conform to the `IngestBatch`
schema defined in `chronotrace-contract/src/commonMain/kotlin/org/chronotrace/contract/ChronoContracts.kt`.
The exact JSON shape (matching what the TypeScript and Kotlin SDKs send):

```json
{
  "client": {
    "appId": "load-test",
    "environment": "test",
    "sdkInstanceId": "k6-smoke-4f0a1b2c3d4e5f60718293a4b5c6d7e8",
    "serviceName": "load-test"
  },
  "logs": [],
  "spans": [],
  "frameSnapshots": [
    {
      "frameId": "frame-0-1714492800000-abcd1234",
      "traceId": "4f0a1b2c3d4e5f60718293a4b5c6d7e8",
      "spanId": "7e8f9a0b1c2d3e4f5061728394a5b6c7",
      "appId": "load-test",
      "environment": "test",
      "sdkInstanceId": "k6-smoke-4f0a1b2c3d4e5f60718293a4b5c6d7e8",
      "serviceName": "load-test",
      "timestampUtc": 1714492800000,
      "sequenceId": 0,
      "captureReason": "auto_capture_level",
      "callStack": [
        {
          "functionName": "method0",
          "filePath": "LoadTestClass.kt",
          "lineNumber": 42,
          "columnNumber": 13
        }
      ],
      "localsJson": "{\"index\":0,\"timestamp\":1714492800000,\"payload\":\"load-test\"}"
    }
  ]
}
```

`logs` and `spans` may be omitted entirely or sent as `[]`; `frameSnapshots`
follows the same rule. The server response is `{"accepted": true}` on success
or a JSON error envelope on failure (`{"error":"...","message":"..."}`).

## Interpreting Results

### Smoke Test
- **PASS**: All health checks succeed, p95 latency < 500ms
- **FAIL**: Health checks fail or latency exceeds threshold

### Stress Test
- **Saturation Point**: The VU count where error rate exceeds 1%
- **Requests/sec at Saturation**: Throughput achieved at breaking point
- **Max Sustainable VUs**: VUs before error rate degradation

### Ingest Test
- **Drop Rate**: Percentage of events that failed to ingest
- **P99 Latency**: 99th percentile response time
- **PASS**: Drop rate < 0.1% and P99 < 2s

## Environment Variables

| Variable | Default | Scripts | Description |
|----------|---------|---------|-------------|
| `BASE_URL` | `http://localhost:8081` | all | Target server base URL |
| `K6_VUS` | scenario-specific | smoke, ingest | Virtual user count |
| `K6_DURATION` | scenario-specific | smoke, ingest | Total test duration |
| `K6_BATCH_SIZE` | `10` | ingest, stress | Frame snapshots per batch |
| `K6_SLEEP` | scenario-specific | ingest, stress | Sleep between iterations (s) |
| `K6_STAGES` | ramp 10→100 VUs | stress | Stage ramp definition |
| `SCENARIO` | script name | all | Tag used in `sdkInstanceId` for the run |

## Examples

```bash
# Run smoke test against a remote host with 20 VUs for 30s
k6 run -e BASE_URL=http://chronotrace.internal:8081 \
       -e K6_VUS=20 -e K6_DURATION=30s smoke-test.js

# Run stress test with a custom 60s ramp to 50 VUs
k6 run -e K6_STAGES="30s:10;60s:50" stress-test.js

# Run ingest test and export JSON results
k6 run --out json=ingest-results.json ingest-test.js

# Run all tests with Docker
docker run --rm -v $(pwd):/scripts grafana/k6:latest run /scripts/smoke-test.js
docker run --rm -v $(pwd):/scripts grafana/k6:latest run /scripts/stress-test.js
docker run --rm -v $(pwd):/scripts grafana/k6:latest run /scripts/ingest-test.js
```

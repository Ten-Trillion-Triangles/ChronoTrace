// Smoke test for the ChronoTrace server.
// Validates that the server can ingest a well-formed IngestBatch payload.
//
// The payload structure MUST match the IngestBatch schema defined in
//   chronotrace-contract/src/commonMain/kotlin/org/chronotrace/contract/ChronoContracts.kt
// and mirrored in
//   sdk-ts/src/generated/contracts.ts
//
// Concretely:
//   IngestBatch = {
//     client: ClientMetadata,
//     logs: LogRecord[],
//     spans: SpanRecord[],
//     frameSnapshots: FrameSnapshot[],
//   }

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// Read the target host/port from k6 environment variables so users can run
// this script against a server on any host. Falls back to the local dev
// default that matches the rest of the load-test scripts.
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';
const SCENARIO = __ENV.SCENARIO || 'smoke-test';

// Custom metrics
const healthCheckDuration = new Trend('health_check_duration');
const ingestLatency = new Trend('ingest_latency');
const ingestSuccessRate = new Rate('ingest_success_rate');
const healthSuccessRate = new Rate('health_success_rate');

export const options = {
  vus: Number(__ENV.K6_VUS) || 10,
  duration: __ENV.K6_DURATION || '1m',
  thresholds: {
    'http_req_duration[p(95)]': ['p(95)<500'],
    'health_success_rate': ['rate>0.99'],
    'ingest_success_rate': ['rate>0.99'],
  },
};

// 32 hex chars — matches the format produced by the SDK's newTraceId().
function randomId() {
  // k6 ships with crypto.getRandomValues in newer versions; fall back to Math.random
  // for older runners. The result is not used for any security-sensitive purpose.
  const buf = new Uint8Array(16);
  if (typeof crypto !== 'undefined' && typeof crypto.getRandomValues === 'function') {
    crypto.getRandomValues(buf);
  } else {
    for (let i = 0; i < buf.length; i++) {
      buf[i] = Math.floor(Math.random() * 256);
    }
  }
  return Array.from(buf, (b) => b.toString(16).padStart(2, '0')).join('');
}

const SDK_INSTANCE_ID = `k6-${SCENARIO}-${randomId()}`;

function buildFrameSnapshot(index) {
  return {
    frameId: `frame-${index}-${Date.now()}-${randomId().slice(0, 8)}`,
    traceId: `trace-${randomId()}`,
    spanId: `span-${randomId()}`,
    appId: 'load-test-smoke',
    environment: 'test',
    sdkInstanceId: SDK_INSTANCE_ID,
    serviceName: 'smoke-test',
    timestampUtc: Date.now() - Math.floor(Math.random() * 1000),
    sequenceId: index,
    captureReason: 'auto_capture_level',
    callStack: [
      {
        functionName: `method${index}`,
        filePath: 'LoadTestClass.kt',
        lineNumber: Math.floor(Math.random() * 100) + 1,
        columnNumber: Math.floor(Math.random() * 80) + 1,
      },
    ],
    localsJson: JSON.stringify({
      index,
      timestamp: Date.now(),
      payload: 'load-test',
    }),
  };
}

function createIngestPayload(batchSize = 10) {
  return {
    client: {
      appId: 'load-test-smoke',
      environment: 'test',
      sdkInstanceId: SDK_INSTANCE_ID,
      serviceName: 'smoke-test',
    },
    logs: [],
    spans: [],
    frameSnapshots: Array.from({ length: batchSize }, (_, i) => buildFrameSnapshot(i)),
  };
}

export default function () {
  // Test 1: Health endpoint
  const healthStart = Date.now();
  const healthRes = http.get(`${BASE_URL}/health`);
  healthCheckDuration.add(Date.now() - healthStart);

  const healthOk = check(healthRes, {
    'health endpoint returns 200': (r) => r.status === 200,
    'health endpoint has status field': (r) => {
      try {
        return JSON.parse(r.body).status !== undefined;
      } catch (e) {
        return false;
      }
    },
  });
  healthSuccessRate.add(healthOk);

  // Test 2: Ingest endpoint
  const payload = JSON.stringify(createIngestPayload(10));
  const params = {
    headers: {
      'Content-Type': 'application/json',
    },
  };

  const ingestStart = Date.now();
  const ingestRes = http.post(`${BASE_URL}/api/v1/ingest`, payload, params);
  ingestLatency.add(Date.now() - ingestStart);

  const ingestOk = check(ingestRes, {
    'ingest endpoint returns 200': (r) => r.status === 200,
    'ingest endpoint returns valid response': (r) => {
      try {
        const body = JSON.parse(r.body);
        return body.accepted === true;
      } catch (e) {
        return r.status === 200;
      }
    },
  });
  ingestSuccessRate.add(ingestOk);

  sleep(1);
}

function getMetric(metrics, name) {
  return metrics[name] && metrics[name].values ? metrics[name].values : {};
}

function textSummary(data, opts) {
  const indent = opts.indent || '';
  const output = [];
  const vusMetrics = getMetric(data.metrics, 'vus');
  const healthRateMetrics = getMetric(data.metrics, 'health_success_rate');
  const healthDurationMetrics = getMetric(data.metrics, 'health_check_duration');
  const ingestRateMetrics = getMetric(data.metrics, 'ingest_success_rate');
  const ingestLatencyMetrics = getMetric(data.metrics, 'ingest_latency');

  output.push(indent + '=== Smoke Test Summary ===');
  output.push(indent + 'VUs: ' + (vusMetrics.value || 0));
  output.push(indent + '');
  output.push(indent + 'Health Check:');
  output.push(indent + '  Success Rate: ' + ((healthRateMetrics.rate || 0) * 100).toFixed(2) + '%');
  output.push(indent + '  Avg Duration: ' + (healthDurationMetrics.avg || 0).toFixed(2) + 'ms');
  output.push(indent + '');
  output.push(indent + 'Ingest:');
  output.push(indent + '  Success Rate: ' + ((ingestRateMetrics.rate || 0) * 100).toFixed(2) + '%');
  output.push(indent + '  Avg Latency: ' + (ingestLatencyMetrics.avg || 0).toFixed(2) + 'ms');
  output.push(indent + '  P95 Latency: ' + (ingestLatencyMetrics['p(95)'] || 0).toFixed(2) + 'ms');
  output.push(indent + '  Max Latency: ' + (ingestLatencyMetrics.max || 0).toFixed(2) + 'ms');
  return output.join('\n');
}

export function handleSummary(data) {
  return {
    stdout: textSummary(data, { indent: ' ', enableColors: true }),
  };
}

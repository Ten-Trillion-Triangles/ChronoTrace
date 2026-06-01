// Stress test for the ChronoTrace server.
//
// Ramps VUs from a low base to a high ceiling while posting IngestBatch
// payloads, and reports the VU count at which the error rate exceeds 1%.
//
// Payload structure must match IngestBatch from
//   chronotrace-contract/src/commonMain/kotlin/org/chronotrace/contract/ChronoContracts.kt

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Gauge, Rate, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';
const SCENARIO = __ENV.SCENARIO || 'stress-test';

// Custom metrics
const ingestLatency = new Trend('ingest_latency');
const ingestErrors = new Counter('ingest_errors');
const ingestSuccessRate = new Rate('ingest_success_rate');
const requestsPerSecond = new Trend('requests_per_second');
const saturationPoint = new Gauge('saturation_vus');

let saturationFound = false;
let saturationVUs = 0;

// Stages can be overridden via the K6_STAGES env var, which is a
// semicolon-separated list of "<duration>:<target>" pairs (e.g.
// "30s:10;30s:20;30s:40"). This is the k6 "stages" array as a string.
const DEFAULT_STAGES = [
  { duration: '30s', target: 10 },
  { duration: '30s', target: 20 },
  { duration: '30s', target: 40 },
  { duration: '30s', target: 60 },
  { duration: '30s', target: 80 },
  { duration: '30s', target: 100 },
];

function parseStages(envValue) {
  if (!envValue) return DEFAULT_STAGES;
  return envValue.split(';').map((stage) => {
    const [duration, target] = stage.split(':');
    return { duration: duration.trim(), target: Number(target) };
  });
}

export const options = {
  stages: parseStages(__ENV.K6_STAGES),
  thresholds: {
    'http_req_failed rate': ['rate<0.01'],
    'ingest_errors': ['count<1000'],
  },
};

function randomId() {
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
    appId: 'load-test-stress',
    environment: 'test',
    sdkInstanceId: SDK_INSTANCE_ID,
    serviceName: 'stress-test',
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
      appId: 'load-test-stress',
      environment: 'test',
      sdkInstanceId: SDK_INSTANCE_ID,
      serviceName: 'stress-test',
    },
    logs: [],
    spans: [],
    frameSnapshots: Array.from({ length: batchSize }, (_, i) => buildFrameSnapshot(i)),
  };
}

export default function () {
  const currentVUs = __VU;
  const currentIteration = __ITER;

  const batchSize = Number(__ENV.K6_BATCH_SIZE) || 10;
  const payload = JSON.stringify(createIngestPayload(batchSize));
  const params = {
    headers: {
      'Content-Type': 'application/json',
    },
  };

  const startTime = Date.now();
  const res = http.post(`${BASE_URL}/api/v1/ingest`, payload, params);
  const latencyMs = Date.now() - startTime;

  ingestLatency.add(latencyMs);

  const isSuccess = check(res, {
    'ingest returns 200': (r) => r.status === 200,
    'ingest returns valid response': (r) => {
      try {
        const body = JSON.parse(r.body);
        return body.accepted === true;
      } catch (e) {
        return r.status === 200;
      }
    },
  });

  ingestSuccessRate.add(isSuccess);

  if (!isSuccess) {
    ingestErrors.add(1);
  }

  requestsPerSecond.add(1);

  if (!saturationFound) {
    const successRateValues = ingestSuccessRate.values || {};
    const errorRate = 1 - (successRateValues.rate || 0);
    if (errorRate > 0.01 && currentVUs > 10) {
      saturationFound = true;
      saturationVUs = currentVUs;
      saturationPoint.add(currentVUs);
    }
  }

  sleep(Number(__ENV.K6_SLEEP) || 0.1);
}

function getMetric(metrics, name) {
  return metrics[name] && metrics[name].values ? metrics[name].values : {};
}

export function handleSummary(data) {
  const successRateMetrics = getMetric(data.metrics, 'ingest_success_rate');
  const errorRate = successRateMetrics.rate || 0;
  const errorRatePercent = ((1 - errorRate) * 100).toFixed(2);

  const vusMaxMetrics = getMetric(data.metrics, 'vus_max');
  const httpReqsMetrics = getMetric(data.metrics, 'http_reqs');
  const ingestLatencyMetrics = getMetric(data.metrics, 'ingest_latency');

  const indent = '';
  let summary = indent + '=== Stress Test Summary ===\n';
  summary += indent + 'Max VUs: ' + (vusMaxMetrics.max || 0) + '\n';
  summary += indent + 'Total Requests: ' + (httpReqsMetrics.count || 0) + '\n';
  summary += indent + 'Request Rate: ' + (httpReqsMetrics.rate || 0).toFixed(2) + ' req/s\n';
  summary += indent + 'Error Rate: ' + errorRatePercent + '%\n';
  summary += indent + '\n';
  summary += indent + 'Latency Distribution:\n';
  summary += indent + '  Avg: ' + (ingestLatencyMetrics.avg || 0).toFixed(2) + 'ms\n';
  summary += indent + '  P50: ' + (ingestLatencyMetrics['p(50)'] || 0).toFixed(2) + 'ms\n';
  summary += indent + '  P95: ' + (ingestLatencyMetrics['p(95)'] || 0).toFixed(2) + 'ms\n';
  summary += indent + '  P99: ' + (ingestLatencyMetrics['p(99)'] || 0).toFixed(2) + 'ms\n';
  summary += indent + '  Max: ' + (ingestLatencyMetrics.max || 0).toFixed(2) + 'ms\n';

  if (saturationFound) {
    summary += indent + '\n';
    summary += indent + 'SATURATION DETECTED at ~' + saturationVUs + ' VUs\n';
    summary += indent + 'Error rate exceeded 1% threshold\n';
  } else {
    summary += indent + '\n';
    summary += indent + 'No saturation detected (error rate stayed below 1%)\n';
  }

  return { stdout: summary };
}

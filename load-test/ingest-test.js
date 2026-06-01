// Sustained-load ingest test for the ChronoTrace server.
//
// Sends well-formed IngestBatch payloads at a constant VU rate and tracks
// throughput, drop rate, and latency percentiles.
//
// Payload structure must match IngestBatch from
//   chronotrace-contract/src/commonMain/kotlin/org/chronotrace/contract/ChronoContracts.kt

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Gauge, Rate, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';
const SCENARIO = __ENV.SCENARIO || 'ingest-test';

// Custom metrics
const ingestLatency = new Trend('ingest_latency');
const ingestBatchSize = new Trend('ingest_batch_size');
const ingestErrors = new Counter('ingest_errors');
const ingestSuccessRate = new Rate('ingest_success_rate');
const totalEventsSent = new Counter('events_sent');
const totalEventsSuccess = new Counter('events_success');
const dropRate = new Gauge('drop_rate');

export const options = {
  vus: Number(__ENV.K6_VUS) || 50,
  duration: __ENV.K6_DURATION || '5m',
  thresholds: {
    'http_req_failed rate': ['rate<0.001'],
    'ingest_latency[p(99)]': ['p(99)<2000'],
    'drop_rate': ['value<0.001'],
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
    appId: 'load-test-ingest',
    environment: 'test',
    sdkInstanceId: SDK_INSTANCE_ID,
    serviceName: 'ingest-test',
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
      appId: 'load-test-ingest',
      environment: 'test',
      sdkInstanceId: SDK_INSTANCE_ID,
      serviceName: 'ingest-test',
    },
    logs: [],
    spans: [],
    frameSnapshots: Array.from({ length: batchSize }, (_, i) => buildFrameSnapshot(i)),
  };
}

let totalSent = 0;
let totalSuccessful = 0;

export default function () {
  const batchSize = Number(__ENV.K6_BATCH_SIZE) || 10;
  totalSent += batchSize;
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
  ingestBatchSize.add(batchSize);

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

  if (isSuccess) {
    totalSuccessful += batchSize;
    totalEventsSuccess.add(batchSize);
  } else {
    ingestErrors.add(1);
  }

  totalEventsSent.add(batchSize);

  if (totalSent > 0) {
    dropRate.add((totalSent - totalSuccessful) / totalSent);
  }

  sleep(Number(__ENV.K6_SLEEP) || 0.5);
}

function getMetric(metrics, name) {
  return metrics[name] && metrics[name].values ? metrics[name].values : {};
}

export function handleSummary(data) {
  const httpReqFailedMetrics = getMetric(data.metrics, 'http_req_failed');
  const errorRate = httpReqFailedMetrics.rate || 0;
  const successRate = 1 - errorRate;

  const httpReqsMetrics = getMetric(data.metrics, 'http_reqs');
  const vusMetrics = getMetric(data.metrics, 'vus');
  const batchSizeMetrics = getMetric(data.metrics, 'ingest_batch_size');
  const ingestLatencyMetrics = getMetric(data.metrics, 'ingest_latency');

  const indent = '';
  let summary = indent + '=== Ingest Load Test Summary ===\n';
  summary += indent + 'VUs: ' + (vusMetrics.value || 0) + '\n';
  summary += indent + 'Total Requests: ' + (httpReqsMetrics.count || 0) + '\n';
  summary += indent + 'Request Rate: ' + (httpReqsMetrics.rate || 0).toFixed(2) + ' req/s\n';
  summary += indent + '\n';
  summary += indent + 'Batch Statistics:\n';
  summary += indent + '  Avg Batch Size: ' + (batchSizeMetrics.avg || 0).toFixed(1) + '\n';
  summary += indent + '  Total Events Sent: ' + totalSent + '\n';
  summary += indent + '  Total Events Successful: ' + totalSuccessful + '\n';
  const dropRateValue = totalSent > 0 ? (totalSent - totalSuccessful) / totalSent : 0;
  summary += indent + '  Drop Rate: ' + (dropRateValue * 100).toFixed(4) + '%\n';
  summary += indent + '\n';
  summary += indent + 'Success Metrics:\n';
  summary += indent + '  Success Rate: ' + (successRate * 100).toFixed(3) + '%\n';
  summary += indent + '  Failure Rate: ' + (errorRate * 100).toFixed(3) + '%\n';
  summary += indent + '\n';
  summary += indent + 'Latency (ms):\n';
  summary += indent + '  Avg: ' + (ingestLatencyMetrics.avg || 0).toFixed(2) + 'ms\n';
  summary += indent + '  P50: ' + (ingestLatencyMetrics['p(50)'] || 0).toFixed(2) + 'ms\n';
  summary += indent + '  P95: ' + (ingestLatencyMetrics['p(95)'] || 0).toFixed(2) + 'ms\n';
  summary += indent + '  P99: ' + (ingestLatencyMetrics['p(99)'] || 0).toFixed(2) + 'ms\n';
  summary += indent + '  Max: ' + (ingestLatencyMetrics.max || 0).toFixed(2) + 'ms\n';

  const meetsDropRateThreshold = dropRateValue < 0.001;
  const meetsLatencyThreshold = (ingestLatencyMetrics['p(99)'] || 0) < 2000;

  summary += indent + '\n';
  summary += indent + 'Threshold Checks:\n';
  summary += indent + '  Drop Rate < 0.1%: ' + (meetsDropRateThreshold ? 'PASS' : 'FAIL') + '\n';
  summary += indent + '  P99 Latency < 2s: ' + (meetsLatencyThreshold ? 'PASS' : 'FAIL') + '\n';

  return { stdout: summary };
}

package org.chronotrace.server

import java.util.concurrent.atomic.AtomicLong

/**
 * Hand-rolled Prometheus-compatible metrics registry.
 *
 * Exposes metrics in Prometheus text format via the /metrics endpoint.
 * All counters are thread-safe via AtomicLong.
 *
 * Metrics exposed:
 *   chronotrace_ingest_total          — total ingest calls (IngestBatch) received
 *   chronotrace_ingest_errors_total   — ingest calls that threw an exception
 *   chronotrace_query_latency_seconds — histogram of query/search latencies in seconds
 *   chronotrace_queue_size            — current estimated queue depth (Valkey-backed purge jobs in ACCEPTED/RUNNING state)
 *   chronotrace_dropped_events_total  — events the server explicitly dropped (e.g. oversized batch)
 *   chronotrace_active_connections     — current active HTTP/WebSocket connections
 */
class ServerMetrics {

    // ── Counters ────────────────────────────────────────────────────────────

    private val ingestTotal = AtomicLong(0)
    private val ingestErrorsTotal = AtomicLong(0)
    private val droppedEventsTotal = AtomicLong(0)
    private val recordsDroppedDueToTtl = AtomicLong(0)
    private val rejectedFrames = AtomicLong(0)

    fun recordIngest() = ingestTotal.incrementAndGet()
    fun recordIngestError() = ingestErrorsTotal.incrementAndGet()
    fun recordDropped(count: Long = 1) = droppedEventsTotal.addAndGet(count)
    fun recordRecordsDroppedDueToTtl(count: Long = 1) = recordsDroppedDueToTtl.addAndGet(count)
    fun recordRejectedFrame() = rejectedFrames.incrementAndGet()

    // ── Active connections (gauge) ──────────────────────────────────────────

    private val activeConnections = AtomicLong(0)

    fun connectionOpened() = activeConnections.incrementAndGet()
    fun connectionClosed() = activeConnections.decrementAndGet()
    fun currentConnections(): Long = activeConnections.get()

    // ── Query latency histogram ─────────────────────────────────────────────
    // Buckets chosen for typical server-side query latency (1ms → 10s)

    private val queryLatencyBuckets = doubleArrayOf(
        0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0
    )
    private val queryLatencyCounts = LongArray(queryLatencyBuckets.size)
    private val queryLatencySum = AtomicLong(0L) // stored as microseconds for precision

    fun recordQueryLatency(latencyMs: Long) {
        val micros = latencyMs * 1000
        queryLatencySum.addAndGet(micros)
        val seconds = latencyMs / 1000.0
        var bucketIdx = queryLatencyBuckets.indexOfFirst { it >= seconds }
        if (bucketIdx < 0) bucketIdx = queryLatencyBuckets.lastIndex
        synchronized(queryLatencyCounts) {
            queryLatencyCounts[bucketIdx]++
        }
    }

    // ── Queue size (gauge) ──────────────────────────────────────────────────

    private val queueSize = AtomicLong(0)

    fun setQueueSize(size: Long) = queueSize.set(size)
    fun currentQueueSize(): Long = queueSize.get()

    // ── Prometheus text format export ──────────────────────────────────────

    fun toPrometheusFormat(): String {
        val sb = StringBuilder()
        val timestamp = System.currentTimeMillis() * 1000 // Prometheus expects microseconds

        sb.append("# HELP chronotrace_ingest_total Total ingest calls (IngestBatch) received by the server\n")
        sb.append("# TYPE chronotrace_ingest_total counter\n")
        sb.append("chronotrace_ingest_total $ingestTotal $timestamp\n\n")

        sb.append("# HELP chronotrace_ingest_errors_total Ingest calls that threw an exception\n")
        sb.append("# TYPE chronotrace_ingest_errors_total counter\n")
        sb.append("chronotrace_ingest_errors_total $ingestErrorsTotal $timestamp\n\n")

        sb.append("# HELP chronotrace_dropped_events_total Events explicitly dropped by the server (e.g. oversized batch)\n")
        sb.append("# TYPE chronotrace_dropped_events_total counter\n")
        sb.append("chronotrace_dropped_events_total $droppedEventsTotal $timestamp\n\n")

        sb.append("# HELP chronotrace_records_dropped_due_to_ttl Records dropped by ClickHouse TTL retention enforcement per TTL evaluation cycle\n")
        sb.append("# TYPE chronotrace_records_dropped_due_to_ttl counter\n")
        sb.append("chronotrace_records_dropped_due_to_ttl $recordsDroppedDueToTtl $timestamp\n\n")

        sb.append("# HELP chronotrace_rejected_frames_total FrameSnapshots rejected at ingest due to invalid localsJson\n")
        sb.append("# TYPE chronotrace_rejected_frames_total counter\n")
        sb.append("chronotrace_rejected_frames_total $rejectedFrames $timestamp\n\n")

        sb.append("# HELP chronotrace_active_connections Current number of active HTTP/WebSocket connections\n")
        sb.append("# TYPE chronotrace_active_connections gauge\n")
        sb.append("chronotrace_active_connections ${currentConnections()} $timestamp\n\n")

        sb.append("# HELP chronotrace_queue_size Estimated queue depth (Valkey-backed purge jobs in ACCEPTED or RUNNING state; 0 in file/memory mode)\n")
        sb.append("# TYPE chronotrace_queue_size gauge\n")
        sb.append("chronotrace_queue_size ${currentQueueSize()} $timestamp\n\n")

        // Query latency histogram
        sb.append("# HELP chronotrace_query_latency_seconds Latency of query/search operations in seconds\n")
        sb.append("# TYPE chronotrace_query_latency_seconds histogram\n")
        val cumulative = LongArray(queryLatencyBuckets.size)
        synchronized(queryLatencyCounts) {
            var running = 0L
            for (i in queryLatencyCounts.indices) {
                running += queryLatencyCounts[i]
                cumulative[i] = running
            }
            for (i in queryLatencyBuckets.indices) {
                sb.append("chronotrace_query_latency_seconds_bucket{le=\"${queryLatencyBuckets[i]}\"} ${cumulative[i]} $timestamp\n")
            }
            sb.append("chronotrace_query_latency_seconds_bucket{le=\"+Inf\"} $running $timestamp\n")
            sb.append("chronotrace_query_latency_seconds_sum ${queryLatencySum.get() / 1_000_000.0} $timestamp\n")
            sb.append("chronotrace_query_latency_seconds_count $running $timestamp\n")
        }

        return sb.toString()
    }
}
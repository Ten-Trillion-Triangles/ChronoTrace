package org.chronotrace.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the silent-dropped-events metric (chronotrace_dropped_events_total).
 *
 * When bounceOnRejected=false and the ClickHouse ingest queue is full, ClickHouseChronoStorage
 * should call serverMetrics.recordDropped(batch.logs.size + batch.spans.size + batch.frameSnapshots.size)
 * instead of throwing IngestRejectedException.
 */
class DroppedEventsMetricTest {

    @Test
    fun `recordDropped increments dropped_events_total counter`() {
        val metrics = ServerMetrics()
        metrics.recordDropped(5)
        val output = metrics.toPrometheusFormat()
        assertTrue(
            output.contains("chronotrace_dropped_events_total 5"),
            "dropped_events_total should be 5 after recording 5 drops, got: $output",
        )
    }

    @Test
    fun `recordDropped with default count increments by one`() {
        val metrics = ServerMetrics()
        metrics.recordDropped()
        val output = metrics.toPrometheusFormat()
        assertTrue(
            output.contains("chronotrace_dropped_events_total 1"),
            "dropped_events_total should be 1 after default recordDropped(), got: $output",
        )
    }

    @Test
    fun `recordDropped accumulates multiple calls`() {
        val metrics = ServerMetrics()
        metrics.recordDropped(3)
        metrics.recordDropped(7)
        metrics.recordDropped(2)
        val output = metrics.toPrometheusFormat()
        assertTrue(
            output.contains("chronotrace_dropped_events_total 12"),
            "dropped_events_total should be 12 after three calls, got: $output",
        )
    }

    @Test
    fun `dropped_events_total metric is present in Prometheus output`() {
        val metrics = ServerMetrics()
        val output = metrics.toPrometheusFormat()

        assertTrue(output.contains("# TYPE chronotrace_dropped_events_total counter"), "TYPE annotation must be present")
        assertTrue(output.contains("# HELP chronotrace_dropped_events_total"), "HELP annotation must be present")
        assertTrue(output.contains("chronotrace_dropped_events_total "), "metric value must be present")
    }

    @Test
    fun `recordDropped with zero events produces zero in metric`() {
        val metrics = ServerMetrics()
        metrics.recordDropped(0)
        val output = metrics.toPrometheusFormat()
        assertTrue(
            output.contains("chronotrace_dropped_events_total 0"),
            "dropped_events_total should be 0 after recording 0 drops, got: $output",
        )
    }

    @Test
    fun `recordDropped combined with ingest counter`() {
        val metrics = ServerMetrics()
        metrics.recordIngest()
        metrics.recordIngest()
        metrics.recordDropped(1)
        val output = metrics.toPrometheusFormat()

        assertTrue(output.contains("chronotrace_ingest_total 2"), "ingest_total should be 2")
        assertTrue(output.contains("chronotrace_dropped_events_total 1"), "dropped_events_total should be 1")
    }
}

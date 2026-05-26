package org.chronotrace.server

import org.chronotrace.contract.FrameSnapshot
import org.chronotrace.contract.IngestBatch
import org.chronotrace.contract.IngestResponse
import org.chronotrace.contract.LogRecord
import org.chronotrace.contract.SearchLogsRequest
import org.chronotrace.contract.SearchLogsResponse
import org.chronotrace.contract.SpanRecord
import org.chronotrace.contract.PurgeSelector
import org.chronotrace.contract.TraceView

data class StepFrameResult(
    val frames: List<FrameSnapshot>,
    val nextCursor: String?, // last frameId if more frames exist in that direction, null otherwise
)

interface ChronoStorage {
    /**
     * Ingests a batch of records with per-record validation.
     * Malformed records are rejected individually while valid records are accepted.
     * @return IngestResponse with lists of accepted record indices and rejected records with errors.
     */
    fun ingest(batch: IngestBatch): IngestResponse
    fun searchLogs(request: SearchLogsRequest): SearchLogsResponse
    fun getLog(logId: String): LogRecord?
    fun getFrame(frameId: String): FrameSnapshot?
    fun getFrameByLog(logId: String): FrameSnapshot?
    fun getTrace(traceId: String): TraceView
    /**
     * Step through frames adjacent to a reference frame.
     *
     * @param frameId The reference frame to step from (used when cursor is null).
     * @param direction Traversal direction: "forward" (ascending) or "backward" (descending).
     * @param count Maximum number of frames to return (1-25).
     * @param cursor Optional cursor frameId from a previous response. When provided, stepping
     *               begins after the cursor frame (not from frameId). This allows pagination
     *               by using the last returned frame's frameId as the cursor.
     * @return StepFrameResult containing frames in temporal order and a nextCursor if more
     *         frames exist in the requested direction.
     */
    fun stepFrame(frameId: String, direction: String, count: Int, cursor: String?): StepFrameResult
    fun counts(): StorageCounts
    fun countsBySelector(selector: PurgeSelector): StorageCounts
    fun health(): StorageHealth
    /**
     * Returns the cumulative count of records dropped by ClickHouse TTL retention enforcement
     * across all TTL evaluation cycles since server startup.
     * Queries system.events for 'RemovedByTTL' events on the data database.
     * Returns 0 for non-ClickHouse storage modes.
     */
    fun recordsDroppedDueToTtl(): Long
}

data class StorageCounts(
    val logs: Int,
    val spans: Int,
    val frames: Int,
)

data class StorageHealth(
    val storageMode: StorageMode,
    val clickhouseHealthy: Boolean? = null,
    val valkeyHealthy: Boolean? = null,
)

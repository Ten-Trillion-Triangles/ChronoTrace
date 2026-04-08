package org.chronotrace.server

import org.chronotrace.contract.FrameSnapshot
import org.chronotrace.contract.IngestBatch
import org.chronotrace.contract.LogRecord
import org.chronotrace.contract.SearchLogsRequest
import org.chronotrace.contract.SearchLogsResponse
import org.chronotrace.contract.SpanRecord
import org.chronotrace.contract.TraceView

interface ChronoStorage {
    fun ingest(batch: IngestBatch)
    fun searchLogs(request: SearchLogsRequest): SearchLogsResponse
    fun getLog(logId: String): LogRecord?
    fun getFrame(frameId: String): FrameSnapshot?
    fun getFrameByLog(logId: String): FrameSnapshot?
    fun getTrace(traceId: String): TraceView
    fun stepFrame(frameId: String, direction: String, count: Int): List<FrameSnapshot>
    fun counts(): StorageCounts
    fun health(): StorageHealth
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

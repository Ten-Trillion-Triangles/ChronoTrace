package org.chronotrace.server

import org.chronotrace.contract.FrameSnapshot
import org.chronotrace.contract.IngestBatch
import org.chronotrace.contract.LogRecord
import org.chronotrace.contract.PurgeJob
import org.chronotrace.contract.RemoteRule
import org.chronotrace.contract.SearchLogsRequest
import org.chronotrace.contract.SearchLogsResponse
import org.chronotrace.contract.SpanRecord
import org.chronotrace.contract.SystemHealth
import org.chronotrace.contract.TraceView

interface ChronoStoreBackend {
    fun ingest(batch: IngestBatch)
    fun searchLogs(request: SearchLogsRequest): SearchLogsResponse
    fun getLog(logId: String): LogRecord?
    fun getFrame(frameId: String): FrameSnapshot?
    fun getFrameByLog(logId: String): FrameSnapshot?
    fun getTrace(traceId: String): TraceView
    fun listRules(appId: String?): List<RemoteRule>
    fun upsertRule(rule: RemoteRule): RemoteRule
    fun deleteRule(ruleId: String): Boolean
    fun createPurgeJob(requestedBy: String, field: String, value: String): PurgeJob
    fun getPurgeJob(purgeJobId: String): PurgeJob?
    fun health(): SystemHealth
    fun stepFrame(frameId: String, direction: String, count: Int): List<FrameSnapshot>
}

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

import org.chronotrace.contract.RemoteRuleFeedback
import org.chronotrace.contract.RuleDeliveryStatus
import org.chronotrace.contract.IngestResponse

interface ChronoStoreBackend {
    fun ingest(batch: IngestBatch): IngestResponse
    fun searchLogs(request: SearchLogsRequest): SearchLogsResponse
    fun getLog(logId: String): LogRecord?
    fun getFrame(frameId: String): FrameSnapshot?
    fun getFrameByLog(logId: String): FrameSnapshot?
    fun getTrace(traceId: String): TraceView
    fun listRules(appId: String?): List<RemoteRule>
    fun upsertRule(rule: RemoteRule): RemoteRule
    fun deleteRule(ruleId: String): Boolean
    /**
     * Records rule delivery feedback from the SDK and updates triggeredCount/lastTriggeredUtc.
     * Idempotent: if the rule has already been marked triggered at the same timestamp, no-op.
     */
    fun recordRuleFeedback(feedback: RemoteRuleFeedback)
    fun createPurgeJob(requestedBy: String, field: String, value: String): PurgeJob
    fun getPurgeJob(purgeJobId: String): PurgeJob?
    fun health(): SystemHealth
    fun stepFrame(frameId: String, direction: String, count: Int, cursor: String?): StepFrameResult
    /** Estimated server-side queue depth (purge jobs in pending/running state). Returns 0 for file/memory mode. */
    fun queueSize(): Long
}
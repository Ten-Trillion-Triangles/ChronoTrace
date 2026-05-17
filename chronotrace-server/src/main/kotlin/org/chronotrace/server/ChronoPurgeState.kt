package org.chronotrace.server

import org.chronotrace.contract.PurgeJob

interface ChronoPurgeState {
    fun put(job: PurgeJob)
    fun get(purgeJobId: String): PurgeJob?
    fun listAll(): List<PurgeJob>
    fun count(): Int
    fun health(): Boolean?
    /** Estimated queue depth — number of purge jobs in the pending/running state. */
    fun queueSize(): Long
}
package org.chronotrace.server

import org.chronotrace.contract.PurgeJob

interface ChronoPurgeState {
    fun put(job: PurgeJob)
    fun get(purgeJobId: String): PurgeJob?
    fun count(): Int
    fun health(): Boolean?
}

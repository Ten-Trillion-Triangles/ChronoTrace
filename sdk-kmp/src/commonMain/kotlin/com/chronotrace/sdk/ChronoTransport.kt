package com.chronotrace.sdk

import org.chronotrace.contract.IngestBatch

interface ChronoTransport {
    suspend fun send(batch: IngestBatch)
}

object NoopTransport : ChronoTransport {
    override suspend fun send(batch: IngestBatch) = Unit
}

class RecordingTransport : ChronoTransport {
    private val sent = mutableListOf<IngestBatch>()

    override suspend fun send(batch: IngestBatch) {
        sent += batch
    }

    fun sentBatches(): List<IngestBatch> = sent.toList()
}

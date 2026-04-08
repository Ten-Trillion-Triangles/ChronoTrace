package org.chronotrace.server

import java.io.Closeable
import java.net.URI
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.chronotrace.contract.FrameSnapshot
import org.chronotrace.contract.IngestBatch
import org.chronotrace.contract.LogLevel
import org.chronotrace.contract.LogRecord
import org.chronotrace.contract.PurgeJob
import org.chronotrace.contract.PurgeJobStatus
import org.chronotrace.contract.PurgeSelector
import org.chronotrace.contract.RemoteRule
import org.chronotrace.contract.SearchLogsRequest
import org.chronotrace.contract.SearchLogsResponse
import org.chronotrace.contract.SpanRecord
import org.chronotrace.contract.SystemHealth
import org.chronotrace.contract.TraceView
import redis.clients.jedis.JedisPooled

class ChronoStore(
    private val authMode: String,
    private val options: ChronoStoreOptions = ChronoStoreOptions(),
) : ChronoStoreBackend, Closeable {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val rules = ConcurrentHashMap<String, RemoteRule>()
    private val storage: ChronoStorage
    private val purgeState: ChronoPurgeState
    private val purgeExecutor = Executors.newSingleThreadExecutor()

    init {
        val components = ChronoStoreFactory.create(options, json)
        storage = components.storage
        purgeState = components.purgeState
    }

    override fun ingest(batch: IngestBatch) = storage.ingest(batch)

    override fun searchLogs(request: SearchLogsRequest): SearchLogsResponse = storage.searchLogs(request)

    override fun getLog(logId: String): LogRecord? = storage.getLog(logId)

    override fun getFrame(frameId: String): FrameSnapshot? = storage.getFrame(frameId)

    override fun getFrameByLog(logId: String): FrameSnapshot? = storage.getFrameByLog(logId)

    override fun getTrace(traceId: String): TraceView = storage.getTrace(traceId)

    override fun listRules(appId: String?): List<RemoteRule> = rules.values
        .filter { appId == null || it.targetApps.isEmpty() || appId in it.targetApps }
        .sortedWith(compareByDescending<RemoteRule> { it.priority }.thenBy { it.ruleId })

    override fun upsertRule(rule: RemoteRule): RemoteRule {
        rules[rule.ruleId] = rule
        return rule
    }

    override fun deleteRule(ruleId: String): Boolean = rules.remove(ruleId) != null

    override fun createPurgeJob(requestedBy: String, field: String, value: String): PurgeJob {
        validatePurgeSelector(field)
        val job = PurgeJob(
            purgeJobId = "purge-${Instant.now().toEpochMilli()}-${purgeState.count() + 1}",
            requestedAtUtc = Instant.now().toEpochMilli(),
            requestedBy = requestedBy,
            selector = PurgeSelector(field = field, value = value),
            status = PurgeJobStatus.ACCEPTED,
        )
        purgeState.put(job)
        purgeExecutor.submit {
            runPurgeJob(job)
        }
        return job
    }

    override fun getPurgeJob(purgeJobId: String): PurgeJob? = purgeState.get(purgeJobId)

    override fun health(): SystemHealth {
        val counts = storage.counts()
        val health = storage.health()
        val storageMode = when (storage) {
            is ClickHouseChronoStorage -> "clickhouse"
            is FileChronoStorage -> "file"
            else -> "memory"
        }
        return SystemHealth(
            authMode = authMode,
            totalLogs = counts.logs,
            totalSpans = counts.spans,
            totalFrames = counts.frames,
            totalRules = rules.size,
            totalPurgeJobs = purgeState.count(),
            storageMode = storageMode,
            clickhouseHealthy = if (storage is ClickHouseChronoStorage) health.clickhouseHealthy else null,
            valkeyHealthy = if (storage is ClickHouseChronoStorage) health.valkeyHealthy ?: purgeState.health() else null,
        )
    }

    override fun stepFrame(frameId: String, direction: String, count: Int): List<FrameSnapshot> =
        storage.stepFrame(frameId, direction, count)

    override fun close() {
        purgeExecutor.shutdown()
        if (storage is Closeable) {
            storage.close()
        }
        if (purgeState is Closeable) {
            purgeState.close()
        }
    }

    private fun runPurgeJob(job: PurgeJob) {
        purgeState.put(job.copy(status = PurgeJobStatus.RUNNING))
        try {
            val stats = when (storage) {
                is ClickHouseChronoStorage -> storage.purge(job.selector)
                is FileChronoStorage -> storage.purge(job.selector)
                is InMemoryChronoStorage -> storage.purge(job.selector)
                else -> emptyMap()
            }
            purgeState.put(
                job.copy(
                    status = PurgeJobStatus.COMPLETED,
                    completedAtUtc = Instant.now().toEpochMilli(),
                    stats = stats,
                ),
            )
        } catch (error: Exception) {
            purgeState.put(
                job.copy(
                    status = PurgeJobStatus.FAILED,
                    completedAtUtc = Instant.now().toEpochMilli(),
                    stats = mapOf("error" to (error.message ?: "unknown error")),
                ),
            )
        }
    }

    private fun validatePurgeSelector(field: String) {
        if (options.storageMode == StorageMode.CLICKHOUSE) {
            validateClickHousePurgeSelector(field)
        }
    }
}

private object ChronoStoreFactory {
    fun create(options: ChronoStoreOptions, json: Json): StoreComponents {
        return when (options.storageMode) {
            StorageMode.FILE -> {
                if (options.dataDir == null) {
                    StoreComponents(storage = InMemoryChronoStorage(options), purgeState = InMemoryChronoPurgeState())
                } else {
                    val storage = FileChronoStorage(options.dataDir, options, json)
                    StoreComponents(storage = storage, purgeState = FileChronoPurgeState(storage))
                }
            }
            StorageMode.CLICKHOUSE -> {
                val clickHouse = requireNotNull(options.clickHouse) { "clickhouse mode requires ClickHouse config" }
                val valkey = requireNotNull(options.valkey) { "clickhouse mode requires Valkey config" }
                validateClickHouseConfig(clickHouse, valkey, options)
                val storage = ClickHouseChronoStorage(clickHouse, options, json)
                val purgeState = ValkeyChronoPurgeState(valkey, json)
                StoreComponents(storage = storage, purgeState = purgeState)
            }
        }
    }
}

private data class StoreComponents(
    val storage: ChronoStorage,
    val purgeState: ChronoPurgeState,
)

private class InMemoryChronoPurgeState : ChronoPurgeState {
    private val jobs = ConcurrentHashMap<String, PurgeJob>()

    override fun put(job: PurgeJob) {
        jobs[job.purgeJobId] = job
    }

    override fun get(purgeJobId: String): PurgeJob? = jobs[purgeJobId]

    override fun count(): Int = jobs.size

    override fun health(): Boolean? = true
}

private class InMemoryChronoStorage(
    private val options: ChronoStoreOptions,
) : ChronoStorage {
    private val logs = CopyOnWriteArrayList<LogRecord>()
    private val spans = CopyOnWriteArrayList<SpanRecord>()
    private val frames = CopyOnWriteArrayList<FrameSnapshot>()

    override fun ingest(batch: IngestBatch) {
        logs.addAll(batch.logs)
        spans.addAll(batch.spans)
        frames.addAll(batch.frameSnapshots)
        applyRetention()
    }

    override fun searchLogs(request: SearchLogsRequest): SearchLogsResponse {
        val startTimeUtc = request.startTimeUtc
        val endTimeUtc = request.endTimeUtc
        val appId = request.appId
        val environment = request.environment
        val level = request.level
        val traceId = request.traceId
        val spanId = request.spanId
        val textQuery = request.textQuery
        val hasFrame = request.hasFrame
        val filtered = logs
            .asSequence()
            .filter { startTimeUtc == null || it.timestampUtc >= startTimeUtc }
            .filter { endTimeUtc == null || it.timestampUtc <= endTimeUtc }
            .filter { appId == null || it.appId == appId }
            .filter { environment == null || it.environment == environment }
            .filter { level == null || it.level == level }
            .filter { traceId == null || it.traceId == traceId }
            .filter { spanId == null || it.spanId == spanId }
            .filter { textQuery == null || it.message.contains(textQuery, ignoreCase = true) }
            .filter { hasFrame == null || (it.linkedFrameId != null) == hasFrame }
            .sortedWith(compareBy<LogRecord> { it.timestampUtc }.thenBy { it.sequenceId })
            .take(request.limit.coerceIn(1, 500))
            .toList()
        return SearchLogsResponse(items = filtered)
    }

    override fun getLog(logId: String): LogRecord? = logs.firstOrNull { it.logId == logId }
    override fun getFrame(frameId: String): FrameSnapshot? = frames.firstOrNull { it.frameId == frameId }
    override fun getFrameByLog(logId: String): FrameSnapshot? = frames.firstOrNull { it.logId == logId }

    override fun getTrace(traceId: String): TraceView = TraceView(
        traceId = traceId,
        spans = spans.filter { it.traceId == traceId }.sortedBy { it.startTimeUtc },
        logs = logs.filter { it.traceId == traceId }.sortedWith(compareBy<LogRecord> { it.timestampUtc }.thenBy { it.sequenceId }),
        frameSnapshots = frames.filter { it.traceId == traceId }.sortedWith(compareBy<FrameSnapshot> { it.timestampUtc }.thenBy { it.sequenceId }),
    )

    override fun stepFrame(frameId: String, direction: String, count: Int): List<FrameSnapshot> {
        val ordered = frames.sortedWith(compareBy<FrameSnapshot> { it.timestampUtc }.thenBy { it.sequenceId })
        val index = ordered.indexOfFirst { it.frameId == frameId }
        if (index == -1) {
            return emptyList()
        }
        val safeCount = count.coerceIn(1, 25)
        return if (direction == "backward") {
            ordered.subList((index - safeCount).coerceAtLeast(0), index)
        } else {
            ordered.subList(index + 1, (index + 1 + safeCount).coerceAtMost(ordered.size))
        }
    }

    fun purge(selector: PurgeSelector): Map<String, String> {
        val beforeLogs = logs.size
        val beforeSpans = spans.size
        val beforeFrames = frames.size
        val predicate = buildPredicate(selector)
        logs.removeIf(predicate)
        spans.removeIf { predicate(logLike(it)) }
        frames.removeIf { predicate(logLike(it)) }
        return mapOf(
            "logsRemoved" to (beforeLogs - logs.size).toString(),
            "spansRemoved" to (beforeSpans - spans.size).toString(),
            "framesRemoved" to (beforeFrames - frames.size).toString(),
        )
    }

    override fun counts(): StorageCounts = StorageCounts(logs.size, spans.size, frames.size)

    override fun health(): StorageHealth = StorageHealth(storageMode = StorageMode.FILE, clickhouseHealthy = null, valkeyHealthy = null)

    private fun applyRetention() {
        val now = Instant.now().toEpochMilli()
        if (options.retentionDaysLogs > 0) {
            val threshold = now - TimeUnit.DAYS.toMillis(options.retentionDaysLogs)
            logs.removeIf { it.timestampUtc < threshold }
        }
        if (options.retentionDaysSpans > 0) {
            val threshold = now - TimeUnit.DAYS.toMillis(options.retentionDaysSpans)
            spans.removeIf { it.startTimeUtc < threshold }
        }
        if (options.retentionDaysFrames > 0) {
            val threshold = now - TimeUnit.DAYS.toMillis(options.retentionDaysFrames)
            frames.removeIf { it.timestampUtc < threshold }
        }
    }
}

private class FileChronoStorage(
    dataDir: java.nio.file.Path,
    private val options: ChronoStoreOptions,
    private val json: Json,
) : ChronoStorage {
    private val delegate = InMemoryChronoStorage(options)
    private val snapshotFile = dataDir.resolve("chronotrace_store.json")
    private val lock = ReentrantLock()
    private val logs = mutableListOf<LogRecord>()
    private val spans = mutableListOf<SpanRecord>()
    private val frames = mutableListOf<FrameSnapshot>()

    init {
        dataDir.toFile().mkdirs()
        load()
    }

    override fun ingest(batch: IngestBatch) {
        lock.withLock {
            logs.addAll(batch.logs)
            spans.addAll(batch.spans)
            frames.addAll(batch.frameSnapshots)
            delegate.ingest(
                IngestBatch(
                    client = batch.client,
                    logs = batch.logs,
                    spans = batch.spans,
                    frameSnapshots = batch.frameSnapshots,
                ),
            )
            persist()
        }
    }

    override fun searchLogs(request: SearchLogsRequest): SearchLogsResponse = delegate.searchLogs(request)
    override fun getLog(logId: String): LogRecord? = delegate.getLog(logId)
    override fun getFrame(frameId: String): FrameSnapshot? = delegate.getFrame(frameId)
    override fun getFrameByLog(logId: String): FrameSnapshot? = delegate.getFrameByLog(logId)
    override fun getTrace(traceId: String): TraceView = delegate.getTrace(traceId)
    override fun stepFrame(frameId: String, direction: String, count: Int): List<FrameSnapshot> = delegate.stepFrame(frameId, direction, count)
    override fun counts(): StorageCounts = delegate.counts()
    override fun health(): StorageHealth = StorageHealth(storageMode = StorageMode.FILE)

    fun purge(selector: PurgeSelector): Map<String, String> {
        val stats = delegate.purge(selector)
        lock.withLock {
            val predicate = buildPredicate(selector)
            logs.removeIf(predicate)
            spans.removeIf { predicate(logLike(it)) }
            frames.removeIf { predicate(logLike(it)) }
            persist()
        }
        return stats
    }

    private fun load() {
        if (!snapshotFile.toFile().exists()) {
            return
        }
        val snapshot = json.decodeFromString(StoreSnapshot.serializer(), snapshotFile.toFile().readText())
        logs.addAll(snapshot.logs)
        spans.addAll(snapshot.spans)
        frames.addAll(snapshot.frames)
        delegate.ingest(
            IngestBatch(
                client = snapshot.client ?: dummyClientMetadata(),
                logs = snapshot.logs,
                spans = snapshot.spans,
                frameSnapshots = snapshot.frames,
            ),
        )
    }

    private fun persist() {
        val snapshot = StoreSnapshot(
            client = dummyClientMetadata(),
            logs = logs.toList(),
            spans = spans.toList(),
            frames = frames.toList(),
        )
        snapshotFile.toFile().writeText(json.encodeToString(StoreSnapshot.serializer(), snapshot))
    }
}

private class FileChronoPurgeState(
    private val storage: FileChronoStorage,
) : ChronoPurgeState {
    private val jobs = ConcurrentHashMap<String, PurgeJob>()

    override fun put(job: PurgeJob) {
        jobs[job.purgeJobId] = job
    }

    override fun get(purgeJobId: String): PurgeJob? = jobs[purgeJobId]

    override fun count(): Int = jobs.size

    override fun health(): Boolean? = true
}

private class ClickHouseChronoStorage(
    private val config: ClickHouseConfig,
    private val options: ChronoStoreOptions,
    private val json: Json,
) : ChronoStorage, Closeable {
    companion object {
        val SUPPORTED_PURGE_FIELDS = setOf("appId", "environment", "traceId", "spanId")
    }

    init {
        bootstrap()
    }

    override fun ingest(batch: IngestBatch) {
        connection().use { connection ->
            connection.autoCommit = false
            insertLogs(connection, batch.logs)
            insertSpans(connection, batch.spans)
            insertFrames(connection, batch.frameSnapshots)
            connection.commit()
        }
    }

    override fun searchLogs(request: SearchLogsRequest): SearchLogsResponse {
        val startTimeUtc = request.startTimeUtc
        val endTimeUtc = request.endTimeUtc
        val appId = request.appId
        val environment = request.environment
        val level = request.level
        val traceId = request.traceId
        val spanId = request.spanId
        val textQuery = request.textQuery
        val hasFrame = request.hasFrame
        val sql = buildString {
            append(
                """
                SELECT log_id, app_id, environment, sdk_instance_id, service_name, trace_id, span_id, parent_span_id,
                timestamp_utc, sequence_id, level, message, fields_json, capture_reason, linked_frame_id
                FROM ${config.database}.logs
                WHERE 1=1
                """.trimIndent(),
            )
            if (startTimeUtc != null) append(" AND timestamp_utc >= ?")
            if (endTimeUtc != null) append(" AND timestamp_utc <= ?")
            if (appId != null) append(" AND app_id = ?")
            if (environment != null) append(" AND environment = ?")
            if (level != null) append(" AND level = ?")
            if (traceId != null) append(" AND trace_id = ?")
            if (spanId != null) append(" AND span_id = ?")
            if (textQuery != null) append(" AND positionCaseInsensitive(message, ?) > 0")
            if (hasFrame != null) {
                append(if (hasFrame) " AND linked_frame_id IS NOT NULL" else " AND linked_frame_id IS NULL")
            }
            append(" ORDER BY timestamp_utc, sequence_id LIMIT ?")
        }
        connection().use { connection ->
            connection.prepareStatement(sql).use { statement ->
                var index = 1
                if (startTimeUtc != null) statement.setLong(index++, startTimeUtc)
                if (endTimeUtc != null) statement.setLong(index++, endTimeUtc)
                if (appId != null) statement.setString(index++, appId)
                if (environment != null) statement.setString(index++, environment)
                if (level != null) statement.setString(index++, level.name)
                if (traceId != null) statement.setString(index++, traceId)
                if (spanId != null) statement.setString(index++, spanId)
                if (textQuery != null) statement.setString(index++, textQuery)
                statement.setInt(index, request.limit.coerceIn(1, 500))
                statement.executeQuery().use { rs ->
                    val items = buildList {
                        while (rs.next()) {
                            add(logFromRow(rs))
                        }
                    }
                    return SearchLogsResponse(items = items)
                }
            }
        }
    }

    override fun getLog(logId: String): LogRecord? = querySingle(
        "SELECT log_id, app_id, environment, sdk_instance_id, service_name, trace_id, span_id, parent_span_id, timestamp_utc, sequence_id, level, message, fields_json, capture_reason, linked_frame_id FROM ${config.database}.logs WHERE log_id = ? LIMIT 1",
        logId,
        ::logFromRow,
    )

    override fun getFrame(frameId: String): FrameSnapshot? = querySingle(
        "SELECT frame_id, trace_id, span_id, app_id, environment, sdk_instance_id, service_name, timestamp_utc, sequence_id, capture_reason, call_stack_json, locals_json, serialization_metadata_json, log_id FROM ${config.database}.frame_snapshots WHERE frame_id = ? LIMIT 1",
        frameId,
        ::frameFromRow,
    )

    override fun getFrameByLog(logId: String): FrameSnapshot? = querySingle(
        "SELECT frame_id, trace_id, span_id, app_id, environment, sdk_instance_id, service_name, timestamp_utc, sequence_id, capture_reason, call_stack_json, locals_json, serialization_metadata_json, log_id FROM ${config.database}.frame_snapshots WHERE log_id = ? LIMIT 1",
        logId,
        ::frameFromRow,
    )

    override fun getTrace(traceId: String): TraceView {
        val spans = queryMany(
            "SELECT span_id, trace_id, app_id, environment, service_name, operation_name, parent_span_id, start_time_utc, end_time_utc, status, attributes_json FROM ${config.database}.spans WHERE trace_id = ? ORDER BY start_time_utc",
            traceId,
            ::spanFromRow,
        )
        val logs = queryMany(
            "SELECT log_id, app_id, environment, sdk_instance_id, service_name, trace_id, span_id, parent_span_id, timestamp_utc, sequence_id, level, message, fields_json, capture_reason, linked_frame_id FROM ${config.database}.logs WHERE trace_id = ? ORDER BY timestamp_utc, sequence_id",
            traceId,
            ::logFromRow,
        )
        val frames = queryMany(
            "SELECT frame_id, trace_id, span_id, app_id, environment, sdk_instance_id, service_name, timestamp_utc, sequence_id, capture_reason, call_stack_json, locals_json, serialization_metadata_json, log_id FROM ${config.database}.frame_snapshots WHERE trace_id = ? ORDER BY timestamp_utc, sequence_id",
            traceId,
            ::frameFromRow,
        )
        return TraceView(traceId = traceId, spans = spans, logs = logs, frameSnapshots = frames)
    }

    override fun stepFrame(frameId: String, direction: String, count: Int): List<FrameSnapshot> {
        val current = getFrame(frameId) ?: return emptyList()
        val sql = if (direction == "backward") {
            "SELECT frame_id, trace_id, span_id, app_id, environment, sdk_instance_id, service_name, timestamp_utc, sequence_id, capture_reason, call_stack_json, locals_json, serialization_metadata_json, log_id FROM ${config.database}.frame_snapshots WHERE (timestamp_utc < ? OR (timestamp_utc = ? AND sequence_id < ?)) ORDER BY timestamp_utc DESC, sequence_id DESC LIMIT ?"
        } else {
            "SELECT frame_id, trace_id, span_id, app_id, environment, sdk_instance_id, service_name, timestamp_utc, sequence_id, capture_reason, call_stack_json, locals_json, serialization_metadata_json, log_id FROM ${config.database}.frame_snapshots WHERE (timestamp_utc > ? OR (timestamp_utc = ? AND sequence_id > ?)) ORDER BY timestamp_utc ASC, sequence_id ASC LIMIT ?"
        }
        val result = connection().use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setLong(1, current.timestampUtc)
                statement.setLong(2, current.timestampUtc)
                statement.setLong(3, current.sequenceId)
                statement.setInt(4, count.coerceIn(1, 25))
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(frameFromRow(rs))
                        }
                    }
                }
            }
        }
        return if (direction == "backward") result.reversed() else result
    }

    fun purge(selector: PurgeSelector): Map<String, String> {
        val column = when (selector.field) {
            "appId" -> "app_id"
            "environment" -> "environment"
            "traceId" -> "trace_id"
            "spanId" -> "span_id"
            else -> error("Unsupported purge selector for clickhouse mode: ${selector.field}")
        }
        connection().use { connection ->
            listOf("logs", "spans", "frame_snapshots").forEach { table ->
                connection.prepareStatement("ALTER TABLE ${config.database}.$table DELETE WHERE $column = ?").use { statement ->
                    statement.setString(1, selector.value)
                    statement.execute()
                }
            }
        }
        return mapOf("mutationField" to selector.field, "mutationValue" to selector.value)
    }

    override fun counts(): StorageCounts {
        connection().use { connection ->
            return StorageCounts(
                logs = countTable(connection, "logs"),
                spans = countTable(connection, "spans"),
                frames = countTable(connection, "frame_snapshots"),
            )
        }
    }

    override fun health(): StorageHealth {
        return try {
            connection().use { connection ->
                connection.prepareStatement("SELECT 1").use { statement ->
                    statement.executeQuery().use { rs -> rs.next() }
                }
            }
            StorageHealth(storageMode = StorageMode.CLICKHOUSE, clickhouseHealthy = true)
        } catch (_: Exception) {
            StorageHealth(storageMode = StorageMode.CLICKHOUSE, clickhouseHealthy = false)
        }
    }

    override fun close() = Unit

    private fun bootstrap() {
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE DATABASE IF NOT EXISTS ${config.database}")
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${config.database}.logs (
                        log_id String,
                        app_id String,
                        environment String,
                        sdk_instance_id String,
                        service_name String,
                        trace_id Nullable(String),
                        span_id Nullable(String),
                        parent_span_id Nullable(String),
                        timestamp_utc Int64,
                        sequence_id Int64,
                        level String,
                        message String,
                        fields_json String,
                        capture_reason Nullable(String),
                        linked_frame_id Nullable(String)
                    )
                    ENGINE = MergeTree()
                    ORDER BY (app_id, timestamp_utc, sequence_id)
                    TTL toDateTime(timestamp_utc / 1000) + INTERVAL ${options.retentionDaysLogs} DAY
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${config.database}.spans (
                        span_id String,
                        trace_id String,
                        app_id String,
                        environment String,
                        service_name String,
                        operation_name String,
                        parent_span_id Nullable(String),
                        start_time_utc Int64,
                        end_time_utc Nullable(Int64),
                        status String,
                        attributes_json String
                    )
                    ENGINE = MergeTree()
                    ORDER BY (app_id, start_time_utc, span_id)
                    TTL toDateTime(start_time_utc / 1000) + INTERVAL ${options.retentionDaysSpans} DAY
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ${config.database}.frame_snapshots (
                        frame_id String,
                        trace_id String,
                        span_id String,
                        app_id String,
                        environment String,
                        sdk_instance_id String,
                        service_name String,
                        timestamp_utc Int64,
                        sequence_id Int64,
                        capture_reason String,
                        call_stack_json String,
                        locals_json String,
                        serialization_metadata_json String,
                        log_id Nullable(String)
                    )
                    ENGINE = MergeTree()
                    ORDER BY (app_id, timestamp_utc, sequence_id)
                    TTL toDateTime(timestamp_utc / 1000) + INTERVAL ${options.retentionDaysFrames} DAY
                    """.trimIndent(),
                )
            }
        }
    }

    private fun connection(): Connection {
        val props = java.util.Properties()
        config.username?.let { props["user"] = it }
        config.password?.let { props["password"] = it }
        props["connect_timeout"] = config.connectTimeoutMs.toString()
        return DriverManager.getConnection(config.jdbcUrl, props)
    }

    private fun countTable(connection: Connection, table: String): Int {
        connection.prepareStatement("SELECT count() FROM ${config.database}.$table").use { statement ->
            statement.executeQuery().use { rs ->
                rs.next()
                return rs.getInt(1)
            }
        }
    }

    private fun insertLogs(connection: Connection, logs: List<LogRecord>) {
        if (logs.isEmpty()) return
        connection.prepareStatement(
            "INSERT INTO ${config.database}.logs (log_id, app_id, environment, sdk_instance_id, service_name, trace_id, span_id, parent_span_id, timestamp_utc, sequence_id, level, message, fields_json, capture_reason, linked_frame_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            logs.forEach { log ->
                statement.setString(1, log.logId)
                statement.setString(2, log.appId)
                statement.setString(3, log.environment)
                statement.setString(4, log.sdkInstanceId)
                statement.setString(5, log.serviceName)
                statement.setNullableString(6, log.traceId)
                statement.setNullableString(7, log.spanId)
                statement.setNullableString(8, log.parentSpanId)
                statement.setLong(9, log.timestampUtc)
                statement.setLong(10, log.sequenceId)
                statement.setString(11, log.level.name)
                statement.setString(12, log.message)
                statement.setString(13, json.encodeToString(MapSerializerStringString, log.fields))
                statement.setNullableString(14, log.captureReason?.name?.lowercase())
                statement.setNullableString(15, log.linkedFrameId)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun insertSpans(connection: Connection, spans: List<SpanRecord>) {
        if (spans.isEmpty()) return
        connection.prepareStatement(
            "INSERT INTO ${config.database}.spans (span_id, trace_id, app_id, environment, service_name, operation_name, parent_span_id, start_time_utc, end_time_utc, status, attributes_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            spans.forEach { span ->
                statement.setString(1, span.spanId)
                statement.setString(2, span.traceId)
                statement.setString(3, span.appId)
                statement.setString(4, span.environment)
                statement.setString(5, span.serviceName)
                statement.setString(6, span.operationName)
                statement.setNullableString(7, span.parentSpanId)
                statement.setLong(8, span.startTimeUtc)
                val endTimeUtc = span.endTimeUtc
                if (endTimeUtc == null) statement.setObject(9, null) else statement.setLong(9, endTimeUtc)
                statement.setString(10, span.status.name)
                statement.setString(11, json.encodeToString(MapSerializerStringString, span.attributes))
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun insertFrames(connection: Connection, frames: List<FrameSnapshot>) {
        if (frames.isEmpty()) return
        connection.prepareStatement(
            "INSERT INTO ${config.database}.frame_snapshots (frame_id, trace_id, span_id, app_id, environment, sdk_instance_id, service_name, timestamp_utc, sequence_id, capture_reason, call_stack_json, locals_json, serialization_metadata_json, log_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            frames.forEach { frame ->
                statement.setString(1, frame.frameId)
                statement.setString(2, frame.traceId)
                statement.setString(3, frame.spanId)
                statement.setString(4, frame.appId)
                statement.setString(5, frame.environment)
                statement.setString(6, frame.sdkInstanceId)
                statement.setString(7, frame.serviceName)
                statement.setLong(8, frame.timestampUtc)
                statement.setLong(9, frame.sequenceId)
                statement.setString(10, frame.captureReason.name.lowercase())
                statement.setString(11, json.encodeToString(ListSerializer(CallStackItemSerializer), frame.callStack))
                statement.setString(12, frame.localsJson)
                statement.setString(13, json.encodeToString(SerializationMetadataSerializer, frame.serializationMetadata))
                statement.setNullableString(14, frame.logId)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun logFromRow(rs: java.sql.ResultSet): LogRecord = LogRecord(
        logId = rs.getString("log_id"),
        appId = rs.getString("app_id"),
        environment = rs.getString("environment"),
        sdkInstanceId = rs.getString("sdk_instance_id"),
        serviceName = rs.getString("service_name"),
        traceId = rs.getStringOrNull("trace_id"),
        spanId = rs.getStringOrNull("span_id"),
        parentSpanId = rs.getStringOrNull("parent_span_id"),
        timestampUtc = rs.getLong("timestamp_utc"),
        sequenceId = rs.getLong("sequence_id"),
        level = LogLevel.valueOf(rs.getString("level")),
        message = rs.getString("message"),
        fields = json.decodeFromString(MapSerializerStringString, rs.getString("fields_json")),
        captureReason = rs.getStringOrNull("capture_reason")?.uppercase()?.let(org.chronotrace.contract.CaptureReason::valueOf),
        linkedFrameId = rs.getStringOrNull("linked_frame_id"),
    )

    private fun spanFromRow(rs: java.sql.ResultSet): SpanRecord = SpanRecord(
        spanId = rs.getString("span_id"),
        traceId = rs.getString("trace_id"),
        appId = rs.getString("app_id"),
        environment = rs.getString("environment"),
        serviceName = rs.getString("service_name"),
        operationName = rs.getString("operation_name"),
        parentSpanId = rs.getStringOrNull("parent_span_id"),
        startTimeUtc = rs.getLong("start_time_utc"),
        endTimeUtc = rs.getNullableLong("end_time_utc"),
        status = org.chronotrace.contract.SpanStatus.valueOf(rs.getString("status")),
        attributes = json.decodeFromString(MapSerializerStringString, rs.getString("attributes_json")),
    )

    private fun frameFromRow(rs: java.sql.ResultSet): FrameSnapshot = FrameSnapshot(
        frameId = rs.getString("frame_id"),
        traceId = rs.getString("trace_id"),
        spanId = rs.getString("span_id"),
        appId = rs.getString("app_id"),
        environment = rs.getString("environment"),
        sdkInstanceId = rs.getString("sdk_instance_id"),
        serviceName = rs.getString("service_name"),
        timestampUtc = rs.getLong("timestamp_utc"),
        sequenceId = rs.getLong("sequence_id"),
        captureReason = rs.getString("capture_reason").uppercase().let(org.chronotrace.contract.CaptureReason::valueOf),
        callStack = json.decodeFromString(ListSerializer(CallStackItemSerializer), rs.getString("call_stack_json")),
        localsJson = rs.getString("locals_json"),
        serializationMetadata = json.decodeFromString(SerializationMetadataSerializer, rs.getString("serialization_metadata_json")),
        logId = rs.getStringOrNull("log_id"),
    )

    private fun <T> querySingle(sql: String, param: String, mapper: (java.sql.ResultSet) -> T): T? {
        connection().use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, param)
                statement.executeQuery().use { rs ->
                    return if (rs.next()) mapper(rs) else null
                }
            }
        }
    }

    private fun <T> queryMany(sql: String, param: String, mapper: (java.sql.ResultSet) -> T): List<T> {
        connection().use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, param)
                statement.executeQuery().use { rs ->
                    return buildList {
                        while (rs.next()) {
                            add(mapper(rs))
                        }
                    }
                }
            }
        }
    }
}

private class ValkeyChronoPurgeState(
    private val config: ValkeyConfig,
    private val json: Json,
) : ChronoPurgeState, Closeable {
    private val jedis = JedisPooled(
        URI(
            buildString {
                append("redis://")
                if (config.password != null) {
                    append(":")
                    append(config.password)
                    append("@")
                }
                append(config.host)
                append(":")
                append(config.port)
                append("/")
                append(config.database)
            },
        ),
    )

    init {
        jedis.ping()
    }

    override fun put(job: PurgeJob) {
        jedis.set(key(job.purgeJobId), json.encodeToString(PurgeJob.serializer(), job))
        jedis.sadd(idsKey(), job.purgeJobId)
    }

    override fun get(purgeJobId: String): PurgeJob? {
        val payload = jedis.get(key(purgeJobId)) ?: return null
        return json.decodeFromString(PurgeJob.serializer(), payload)
    }

    override fun count(): Int = jedis.scard(idsKey()).toInt()

    override fun health(): Boolean? {
        return try {
            jedis.ping() == "PONG"
        } catch (_: Exception) {
            false
        }
    }

    override fun close() {
        jedis.close()
    }

    private fun key(id: String): String = "${config.keyPrefix}:purge:$id"
    private fun idsKey(): String = "${config.keyPrefix}:purge:ids"
}

private fun buildPredicate(selector: PurgeSelector): (LogRecord) -> Boolean = when (selector.field) {
    "appId" -> { it -> it.appId == selector.value }
    "environment" -> { it -> it.environment == selector.value }
    "traceId" -> { it -> it.traceId == selector.value }
    "spanId" -> { it -> it.spanId == selector.value }
    "message" -> { it -> it.message.contains(selector.value) }
    else -> { _ -> false }
}

internal fun validateClickHousePurgeSelector(field: String) {
    require(field in ClickHouseChronoStorage.SUPPORTED_PURGE_FIELDS) {
        "Unsupported purge selector for clickhouse mode: $field"
    }
}

private fun validateClickHouseConfig(
    clickHouse: ClickHouseConfig,
    valkey: ValkeyConfig,
    options: ChronoStoreOptions,
) {
    require(clickHouse.jdbcUrl.isNotBlank()) { "clickhouse mode requires a non-blank ClickHouse jdbcUrl" }
    require(clickHouse.database.isNotBlank()) { "clickhouse mode requires a non-blank ClickHouse database" }
    require(valkey.host.isNotBlank()) { "clickhouse mode requires a non-blank Valkey host" }
    require(options.retentionDaysLogs > 0) { "clickhouse mode requires positive retentionDaysLogs" }
    require(options.retentionDaysSpans > 0) { "clickhouse mode requires positive retentionDaysSpans" }
    require(options.retentionDaysFrames > 0) { "clickhouse mode requires positive retentionDaysFrames" }
}

private fun logLike(span: SpanRecord): LogRecord = LogRecord(
    logId = span.spanId,
    appId = span.appId,
    environment = span.environment,
    sdkInstanceId = span.appId,
    serviceName = span.serviceName,
    traceId = span.traceId,
    spanId = span.spanId,
    parentSpanId = span.parentSpanId,
    timestampUtc = span.startTimeUtc,
    sequenceId = 0,
    level = LogLevel.INFO,
    message = "",
)

private fun logLike(frame: FrameSnapshot): LogRecord = LogRecord(
    logId = frame.frameId,
    appId = frame.appId,
    environment = frame.environment,
    sdkInstanceId = frame.sdkInstanceId,
    serviceName = frame.serviceName,
    traceId = frame.traceId,
    spanId = frame.spanId,
    timestampUtc = frame.timestampUtc,
    sequenceId = frame.sequenceId,
    level = LogLevel.INFO,
    message = "",
)

@Serializable
private data class StoreSnapshot(
    val client: org.chronotrace.contract.ClientMetadata? = null,
    val logs: List<LogRecord> = emptyList(),
    val spans: List<SpanRecord> = emptyList(),
    val frames: List<FrameSnapshot> = emptyList(),
)

private fun dummyClientMetadata(): org.chronotrace.contract.ClientMetadata = org.chronotrace.contract.ClientMetadata(
    appId = "chronotrace-server",
    environment = "local",
    sdkInstanceId = "server",
    serviceName = "chronotrace-server",
)

private fun PreparedStatement.setNullableString(index: Int, value: String?) {
    if (value == null) {
        setObject(index, null)
    } else {
        setString(index, value)
    }
}

private fun java.sql.ResultSet.getStringOrNull(column: String): String? = getString(column)?.takeIf { !wasNull() }

private fun java.sql.ResultSet.getNullableLong(column: String): Long? {
    val value = getLong(column)
    return if (wasNull()) null else value
}

private val MapSerializerStringString = kotlinx.serialization.builtins.MapSerializer(
    kotlinx.serialization.serializer<String>(),
    kotlinx.serialization.serializer<String>(),
)

private val CallStackItemSerializer = org.chronotrace.contract.CallStackItem.serializer()
private val SerializationMetadataSerializer = org.chronotrace.contract.SerializationMetadata.serializer()

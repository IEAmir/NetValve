package dev.netvalve.repository

import dev.netvalve.log.LogEvent
import kotlinx.coroutines.flow.Flow

/** Cumulative per-app usage as persisted to Room (survives process death). */
data class AppUsageRecord(
    val uid: Int,
    val packageName: String,
    val totalUpload: Long,
    val totalDownload: Long,
    val lastActiveMillis: Long,
)

/** Bounded, on-device persistence for the log viewer + export. */
interface LogRepository {
    suspend fun insert(events: List<LogEvent>)
    fun recent(limit: Int): Flow<List<LogEvent>>
    suspend fun all(): List<LogEvent>
    suspend fun clear()
    /** Keep at most [maxRows], deleting the oldest beyond that (ring-buffer semantics). */
    suspend fun trimTo(maxRows: Int)
}

/** Durable per-app byte counters, checkpointed periodically by StatsCollector. */
interface StatsRepository {
    fun perAppTotals(): Flow<List<AppUsageRecord>>
    suspend fun current(): List<AppUsageRecord>
    suspend fun upsert(records: List<AppUsageRecord>)
    suspend fun reset()
}

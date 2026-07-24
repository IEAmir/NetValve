package dev.netvalve.repository

import dev.netvalve.data.db.AppUsageDao
import dev.netvalve.data.db.AppUsageEntity
import dev.netvalve.data.db.LogDao
import dev.netvalve.data.db.LogEntryEntity
import dev.netvalve.log.LogCategory
import dev.netvalve.log.LogEvent
import dev.netvalve.log.LogLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Room-backed [LogRepository]. Maps between domain [LogEvent] and entities. */
class RoomLogRepository(private val dao: LogDao) : LogRepository {

    override suspend fun insert(events: List<LogEvent>) {
        if (events.isEmpty()) return
        dao.insertAll(events.map { it.toEntity() })
    }

    override fun recent(limit: Int): Flow<List<LogEvent>> =
        // DAO returns newest-first; present chronologically for the viewer.
        dao.recent(limit).map { rows -> rows.asReversed().map { it.toEvent() } }

    override suspend fun all(): List<LogEvent> = dao.all().map { it.toEvent() }

    override suspend fun clear() = dao.clear()

    override suspend fun trimTo(maxRows: Int) = dao.trimTo(maxRows)

    private fun LogEvent.toEntity() = LogEntryEntity(
        timestampMillis = timestampMillis,
        level = level.ordinal,
        category = category.ordinal,
        message = message,
        uid = uid,
        packageName = packageName,
    )

    private fun LogEntryEntity.toEvent() = LogEvent(
        timestampMillis = timestampMillis,
        level = LogLevel.entries.getOrElse(level) { LogLevel.INFO },
        category = LogCategory.entries.getOrElse(category) { LogCategory.SYSTEM },
        message = message,
        uid = uid,
        packageName = packageName,
    )
}

/** Room-backed [StatsRepository] for durable per-app byte totals. */
class RoomStatsRepository(private val dao: AppUsageDao) : StatsRepository {

    override fun perAppTotals(): Flow<List<AppUsageRecord>> =
        dao.observeAll().map { rows -> rows.map { it.toRecord() } }

    override suspend fun current(): List<AppUsageRecord> = dao.getAll().map { it.toRecord() }

    override suspend fun upsert(records: List<AppUsageRecord>) {
        if (records.isEmpty()) return
        dao.upsertAll(records.map { it.toEntity() })
    }

    override suspend fun reset() = dao.clear()

    private fun AppUsageEntity.toRecord() =
        AppUsageRecord(uid, packageName, totalUpload, totalDownload, lastActiveMillis)

    private fun AppUsageRecord.toEntity() =
        AppUsageEntity(uid, packageName, totalUpload, totalDownload, lastActiveMillis)
}

package dev.netvalve.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LogDao {
    @Insert
    suspend fun insertAll(entries: List<LogEntryEntity>)

    @Query("SELECT * FROM logs ORDER BY id DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<LogEntryEntity>>

    @Query("SELECT * FROM logs ORDER BY id ASC")
    suspend fun all(): List<LogEntryEntity>

    @Query("SELECT COUNT(*) FROM logs")
    suspend fun count(): Int

    /** Delete everything but the newest [keep] rows (ring-buffer trim). */
    @Query("DELETE FROM logs WHERE id NOT IN (SELECT id FROM logs ORDER BY id DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)

    @Query("DELETE FROM logs")
    suspend fun clear()
}

@Dao
interface AppUsageDao {
    @Upsert
    suspend fun upsertAll(rows: List<AppUsageEntity>)

    @Query("SELECT * FROM app_usage")
    fun observeAll(): Flow<List<AppUsageEntity>>

    @Query("SELECT * FROM app_usage")
    suspend fun getAll(): List<AppUsageEntity>

    @Query("DELETE FROM app_usage")
    suspend fun clear()
}

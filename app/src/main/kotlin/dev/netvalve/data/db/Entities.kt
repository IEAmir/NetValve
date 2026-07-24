package dev.netvalve.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A persisted log line. Level/category stored as ordinals; mapped in the repo. */
@Entity(
    tableName = "logs",
    indices = [Index("timestampMillis"), Index("level")],
)
data class LogEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMillis: Long,
    val level: Int,
    val category: Int,
    val message: String,
    val uid: Int?,
    val packageName: String?,
)

/** Durable cumulative per-app byte totals. */
@Entity(tableName = "app_usage")
data class AppUsageEntity(
    @PrimaryKey val uid: Int,
    val packageName: String,
    val totalUpload: Long,
    val totalDownload: Long,
    val lastActiveMillis: Long,
)

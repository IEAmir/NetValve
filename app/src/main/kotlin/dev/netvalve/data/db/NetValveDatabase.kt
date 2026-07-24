package dev.netvalve.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [LogEntryEntity::class, AppUsageEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class NetValveDatabase : RoomDatabase() {
    abstract fun logDao(): LogDao
    abstract fun appUsageDao(): AppUsageDao

    companion object {
        const val NAME = "netvalve.db"
    }
}

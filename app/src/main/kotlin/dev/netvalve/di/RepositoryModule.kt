package dev.netvalve.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dev.netvalve.data.db.AppUsageDao
import dev.netvalve.data.db.LogDao
import dev.netvalve.repository.AppInfoLookup
import dev.netvalve.repository.AppSelectionRepository
import dev.netvalve.repository.DataStoreAppSelectionRepository
import dev.netvalve.repository.DataStoreSettingsRepository
import dev.netvalve.repository.InstalledAppsRepository
import dev.netvalve.repository.LogRepository
import dev.netvalve.repository.PackageManagerAppRepository
import dev.netvalve.repository.RoomLogRepository
import dev.netvalve.repository.RoomStatsRepository
import dev.netvalve.repository.SettingsRepository
import dev.netvalve.repository.StatsRepository
import dev.netvalve.rules.DeviceStateMonitor
import dev.netvalve.service.DeviceStateMonitorImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun provideSettingsRepository(
        dataStore: DataStore<Preferences>,
        json: Json,
    ): SettingsRepository = DataStoreSettingsRepository(dataStore, json)

    @Provides
    @Singleton
    fun provideSelectionRepository(
        dataStore: DataStore<Preferences>,
    ): AppSelectionRepository = DataStoreAppSelectionRepository(dataStore)

    @Provides
    @Singleton
    fun provideLogRepository(dao: LogDao): LogRepository = RoomLogRepository(dao)

    @Provides
    @Singleton
    fun provideStatsRepository(dao: AppUsageDao): StatsRepository = RoomStatsRepository(dao)

    @Provides
    @Singleton
    fun provideInstalledApps(@ApplicationContext context: Context): InstalledAppsRepository =
        PackageManagerAppRepository(context)

    @Provides
    fun provideAppInfoLookup(repo: InstalledAppsRepository): AppInfoLookup = repo

    @Provides
    @Singleton
    fun provideDeviceStateMonitor(@ApplicationContext context: Context): DeviceStateMonitor =
        DeviceStateMonitorImpl(context)
}

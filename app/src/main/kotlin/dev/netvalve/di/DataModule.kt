package dev.netvalve.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import dev.netvalve.data.datastore.NetValveJson
import dev.netvalve.data.datastore.netValveDataStore
import dev.netvalve.data.db.AppUsageDao
import dev.netvalve.data.db.LogDao
import dev.netvalve.data.db.NetValveDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideJson(): Json = NetValveJson

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.netValveDataStore

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): NetValveDatabase =
        Room.databaseBuilder(context, NetValveDatabase::class.java, NetValveDatabase.NAME)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideLogDao(db: NetValveDatabase): LogDao = db.logDao()

    @Provides
    fun provideAppUsageDao(db: NetValveDatabase): AppUsageDao = db.appUsageDao()

    @Provides
    @Singleton
    @AppScope
    fun provideAppScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

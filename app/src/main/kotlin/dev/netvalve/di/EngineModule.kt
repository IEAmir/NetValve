package dev.netvalve.di

import android.content.Context
import android.net.ConnectivityManager
import android.os.Process
import dev.netvalve.data.model.SelectionMode
import dev.netvalve.log.Logger
import dev.netvalve.log.NetValveLogger
import dev.netvalve.module.ModuleChain
import dev.netvalve.module.TrafficModule
import dev.netvalve.network.ConnectionManager
import dev.netvalve.network.ConnectionOwnerLookup
import dev.netvalve.network.DnsCache
import dev.netvalve.network.UidResolver
import dev.netvalve.repository.AppInfoLookup
import dev.netvalve.repository.AppSelectionRepository
import dev.netvalve.repository.LogRepository
import dev.netvalve.repository.SettingsRepository
import dev.netvalve.repository.StatsRepository
import dev.netvalve.rules.DeviceStateMonitor
import dev.netvalve.rules.RuleEngine
import dev.netvalve.stats.StatsCollector
import dev.netvalve.throttle.ThrottleManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

/** Wires the framework-light engine singletons together. */
@Module
@InstallIn(SingletonComponent::class)
object EngineModule {

    @Provides
    @Singleton
    fun provideLogger(logRepository: LogRepository, @AppScope scope: CoroutineScope): Logger =
        NetValveLogger(logRepository, scope)

    @Provides
    @Singleton
    fun provideDnsCache(): DnsCache = DnsCache()

    @Provides
    @Singleton
    fun provideRuleEngine(
        settingsRepository: SettingsRepository,
        selectionRepository: AppSelectionRepository,
        appInfo: AppInfoLookup,
        deviceStateMonitor: DeviceStateMonitor,
        @AppScope scope: CoroutineScope,
    ): RuleEngine = RuleEngine(
        settingsRepository = settingsRepository,
        selectionRepository = selectionRepository,
        appInfo = appInfo,
        deviceStateMonitor = deviceStateMonitor,
        ownUid = Process.myUid(),
        scope = scope,
    )

    @Provides
    @Singleton
    fun provideUidResolver(
        @ApplicationContext context: Context,
        logger: Logger,
        ruleEngine: RuleEngine,
        appInfo: AppInfoLookup,
    ): UidResolver {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val lookup = ConnectionOwnerLookup { protocol, local, remote ->
            cm?.getConnectionOwnerUid(protocol, local, remote) ?: UidResolver.INVALID_UID
        }
        return UidResolver(
            lookup = lookup,
            logger = logger,
            singleControlledUidProvider = {
                val snap = ruleEngine.snapshot.value
                if (snap.settings.selectionMode == SelectionMode.ONLY_SELECTED && snap.selected.size == 1) {
                    appInfo.uidForPackage(snap.selected.first())
                } else {
                    null
                }
            },
        )
    }

    @Provides
    @Singleton
    fun provideThrottleManager(
        ruleEngine: RuleEngine,
        @AppScope scope: CoroutineScope,
        logger: Logger,
    ): ThrottleManager = ThrottleManager(ruleEngine, scope, logger)

    @Provides
    @Singleton
    fun provideStatsCollector(statsRepository: StatsRepository, appInfo: AppInfoLookup): StatsCollector =
        StatsCollector(statsRepository, appInfo)

    @Provides
    @Singleton
    fun provideConnectionManager(stats: StatsCollector, logger: Logger): ConnectionManager =
        ConnectionManager(stats, logger)

    @Provides
    @Singleton
    fun provideModuleChain(modules: Set<@JvmSuppressWildcards TrafficModule>): ModuleChain =
        ModuleChain(modules)
}

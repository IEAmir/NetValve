package dev.netvalve

import android.app.Application
import dev.netvalve.log.LogLevel
import dev.netvalve.log.Logger
import dev.netvalve.repository.SettingsRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import dev.netvalve.di.AppScope
import javax.inject.Inject

/**
 * Hilt application entry point. Keeps the logger's minimum level in sync with
 * the persisted setting and installs StrictMode in debug builds so accidental
 * main-thread I/O or leaks surface loudly during development.
 */
@HiltAndroidApp
class NetValveApp : Application() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var logger: Logger
    @Inject @AppScope lateinit var appScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) StrictModeConfig.enable()

        settingsRepository.settings
            .map { it.logLevel }
            .distinctUntilChanged()
            .onEach { level -> logger.setMinLevel(LogLevel.entries.getOrElse(level) { LogLevel.INFO }) }
            .launchIn(appScope)
    }
}

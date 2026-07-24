package dev.netvalve.di

import dev.netvalve.log.Logger
import dev.netvalve.module.ConnectionLogModule
import dev.netvalve.module.DefaultPolicyModule
import dev.netvalve.module.TrafficModule
import dev.netvalve.rules.RuleEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Registers the built-in [TrafficModule]s into the Hilt multiset consumed by
 * [dev.netvalve.module.ModuleChain]. Adding a new module (quota, domain filter,
 * parental control, …) is a one-line `@Provides @IntoSet` here — the engine and
 * relay are untouched. That is the extensibility contract (change 11).
 */
@Module
@InstallIn(SingletonComponent::class)
object ModulesModule {

    @Provides
    @IntoSet
    fun provideDefaultPolicyModule(ruleEngine: RuleEngine): TrafficModule =
        DefaultPolicyModule(ruleEngine)

    @Provides
    @IntoSet
    fun provideConnectionLogModule(logger: Logger): TrafficModule =
        ConnectionLogModule(logger)
}

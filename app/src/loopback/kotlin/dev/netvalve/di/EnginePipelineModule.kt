package dev.netvalve.di

import dev.netvalve.log.Logger
import dev.netvalve.network.PacketPipeline
import dev.netvalve.network.PacketPipelineFactory
import dev.netvalve.network.UidResolver
import dev.netvalve.network.loopback.LoopbackPacketPipeline
import dev.netvalve.stats.StatsCollector
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Engine binding for the DEFAULT (loopback) build. Present only in
 * app/src/loopback; the netstack build ships a same-named module instead.
 */
@Module
@InstallIn(SingletonComponent::class)
object EnginePipelineModule {

    @Provides
    @Singleton
    fun providePipelineFactory(
        stats: StatsCollector,
        uidResolver: UidResolver,
        logger: Logger,
    ): PacketPipelineFactory = object : PacketPipelineFactory {
        override fun create(): PacketPipeline = LoopbackPacketPipeline(stats, uidResolver, logger)
    }
}

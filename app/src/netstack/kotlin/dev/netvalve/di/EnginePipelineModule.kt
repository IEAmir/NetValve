package dev.netvalve.di

import dev.netvalve.log.LogCategory
import dev.netvalve.log.LogLevel
import dev.netvalve.log.Logger
import dev.netvalve.network.PacketPipeline
import dev.netvalve.network.PacketPipelineFactory
import dev.netvalve.network.netstack.NetstackPacketPipeline
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Engine binding for the PRODUCTION (netstack) build, selected with
 * `-Pnetvalve.netstack=true`. Present only in app/src/netstack. Flow logic runs
 * through the injected [dev.netvalve.network.FlowHandler]; the pipeline also gets
 * a log sink so Go-side netstack lines (liveness beacon, recovered panics,
 * endpoint errors) surface in the app's Logs screen + logcat.
 */
@Module
@InstallIn(SingletonComponent::class)
object EnginePipelineModule {

    @Provides
    @Singleton
    fun providePipelineFactory(logger: Logger): PacketPipelineFactory =
        object : PacketPipelineFactory {
            override fun create(): PacketPipeline = NetstackPacketPipeline { level, msg ->
                val lvl = LogLevel.entries.getOrElse(level) { LogLevel.INFO }
                logger.log(lvl, LogCategory.SYSTEM, "[netstack] $msg")
            }
        }
}

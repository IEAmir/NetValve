package dev.netvalve.di

import javax.inject.Qualifier

/** Application-lifetime coroutine scope (SupervisorJob, Default dispatcher). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope

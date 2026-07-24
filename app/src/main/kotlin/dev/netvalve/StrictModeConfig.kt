package dev.netvalve

import android.os.StrictMode

/**
 * Debug-only StrictMode. Enforces "no main-thread I/O" and leak detection — a
 * concrete guard for the "no main-thread blocking / no leaks" acceptance
 * criteria. Penalties are logged, not fatal, to avoid masking unrelated crashes.
 */
object StrictModeConfig {
    fun enable() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedRegistrationObjects()
                .detectActivityLeaks()
                .penaltyLog()
                .build(),
        )
    }
}

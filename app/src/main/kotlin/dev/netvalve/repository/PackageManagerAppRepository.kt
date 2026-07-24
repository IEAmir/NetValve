package dev.netvalve.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import dev.netvalve.data.model.InstalledApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * [InstalledAppsRepository] + [AppInfoLookup] backed by PackageManager. uid <->
 * package and label lookups are cached (they are hit on the attribution path via
 * StatsCollector), and [invalidate] clears them after install/uninstall events.
 */
class PackageManagerAppRepository(
    context: Context,
) : InstalledAppsRepository {

    private val pm: PackageManager = context.packageManager

    private val uidToPackages = ConcurrentHashMap<Int, List<String>>()
    private val packageToUid = ConcurrentHashMap<String, Int>()
    private val labelCache = ConcurrentHashMap<String, String>()

    @Suppress("DEPRECATION")
    override suspend fun listUserApps(includeSystem: Boolean): List<InstalledApp> =
        withContext(Dispatchers.IO) {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .asSequence()
                .mapNotNull { ai ->
                    val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val launchable = pm.getLaunchIntentForPackage(ai.packageName) != null
                    // Show user apps + any launchable/updated system app; hide the
                    // pure-system noise unless explicitly requested.
                    if (!includeSystem && isSystem && !launchable) return@mapNotNull null
                    val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(ai.packageName)
                    labelCache[ai.packageName] = label
                    packageToUid[ai.packageName] = ai.uid
                    InstalledApp(
                        packageName = ai.packageName,
                        label = label,
                        uid = ai.uid,
                        isSystem = isSystem,
                    )
                }
                .sortedBy { it.label.lowercase() }
                .toList()
        }

    override fun packagesForUid(uid: Int): List<String> =
        uidToPackages.getOrPut(uid) {
            runCatching { pm.getPackagesForUid(uid)?.toList() }.getOrNull() ?: emptyList()
        }

    override fun uidForPackage(packageName: String): Int? =
        packageToUid[packageName] ?: runCatching {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(packageName, 0).uid.also { packageToUid[packageName] = it }
        }.getOrNull()

    override fun labelForPackage(packageName: String): String =
        labelCache[packageName] ?: runCatching {
            @Suppress("DEPRECATION")
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
                .also { labelCache[packageName] = it }
        }.getOrDefault(packageName)

    override fun invalidate() {
        uidToPackages.clear(); packageToUid.clear(); labelCache.clear()
    }
}

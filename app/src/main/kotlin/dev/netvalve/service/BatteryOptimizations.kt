package dev.netvalve.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Doze / battery-optimization awareness and vendor auto-start guidance (change 8).
 *
 * A VpnService is a foreground service and is largely Doze-exempt while running,
 * but aggressive OEM ROMs still kill background apps and block auto-start. We
 * cannot silently fix that (no device-admin, no root); instead we *detect* the
 * situation and hand the user the right settings screen, degrading to plain text
 * guidance when a vendor intent is unavailable.
 */
object BatteryOptimizations {

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Standard AOSP prompt to exempt us from Doze battery optimization. */
    fun requestExemptionIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))

    data class VendorHint(
        val manufacturer: String,
        val message: String,
        val settingsIntent: Intent?,
    )

    /**
     * Best-effort per-vendor guidance. The component names are the historically
     * correct auto-start managers; each is returned only if it resolves on this
     * device, so a wrong/absent component degrades to guidance text with a null
     * intent instead of crashing.
     */
    fun vendorHint(context: Context): VendorHint? {
        val mfr = Build.MANUFACTURER.lowercase()
        val candidates = when {
            mfr.contains("xiaomi") || mfr.contains("redmi") || mfr.contains("poco") ->
                "On MIUI, enable Autostart and set battery saver to 'No restrictions' for NetValve." to listOf(
                    ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                )
            mfr.contains("huawei") || mfr.contains("honor") ->
                "On EMUI, add NetValve to 'Protected apps' and allow manual app management." to listOf(
                    ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                    ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
                )
            mfr.contains("oppo") || mfr.contains("realme") ->
                "On ColorOS, allow Auto-startup and disable battery optimization for NetValve." to listOf(
                    ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                )
            mfr.contains("vivo") || mfr.contains("iqoo") ->
                "On FuntouchOS/OriginOS, enable 'High background power consumption' and Auto-start for NetValve." to listOf(
                    ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                )
            mfr.contains("samsung") ->
                "On One UI, set NetValve to 'Unrestricted' in battery usage and exclude it from 'Deep sleeping apps'." to listOf(
                    ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                )
            else -> null
        } ?: return null

        val (message, components) = candidates
        val resolvable = components.firstOrNull { comp ->
            val intent = Intent().setComponent(comp)
            context.packageManager.resolveActivity(intent, 0) != null
        }?.let { Intent().setComponent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

        return VendorHint(Build.MANUFACTURER, message, resolvable)
    }
}

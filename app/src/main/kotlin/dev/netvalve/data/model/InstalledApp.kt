package dev.netvalve.data.model

/**
 * A launchable installed app, surfaced on the selection screen. Not persisted as
 * such — only [packageName]s are persisted (see AppSelectionRepository). The
 * label/icon are resolved live from PackageManager.
 */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val uid: Int,
    val isSystem: Boolean,
) {
    // Icons are loaded lazily by the UI layer (Drawable is not a model concern).
    val stableId: String get() = packageName
}

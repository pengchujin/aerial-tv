package app.orchard.tv.guard

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri

/** Update completion may uncover an installer or Home; an unknown front belongs to the viewer. */
internal object UpdateRecovery {
    /**
     * Whether Home should be taken back after Aerial's own update. Only a confirmed installer, or
     * a stock launcher the guard has not deliberately allowed, is fair game; an unknown front
     * (a video app, an input, nothing at all) is the viewer's and is left alone. Required to be
     * awake and unlocked, and Aerial itself not already resumed.
     */
    fun shouldRestore(
        interactive: Boolean, unlocked: Boolean, resumed: Boolean, front: String?,
        stockHome: Boolean, stockAllowed: Boolean, installer: Boolean,
    ): Boolean = interactive && unlocked && !resumed && front != null &&
        (installer || stockHome && !stockAllowed)

    /** Whether [pkg] answers Aerial's own APK with an enabled, exported system package installer. */
    @Suppress("DEPRECATION") // The int-flags API also runs on Android 5.
    fun isSystemInstaller(context: Context, pkg: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(
            Uri.parse("content://app.orchard.tv.update/aerial.apk"), "application/vnd.android.package-archive",
        )
        return runCatching {
            context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).any {
                val info = it.activityInfo
                info.packageName == pkg && info.exported && info.enabled && info.applicationInfo.enabled &&
                    info.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            }
        }.getOrDefault(false)
    }
}

package app.orchard.tv.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

/** Only controls an already installed client. No SDK, shell commands or network access. */
internal object ClashShortcut {
    // The runtime handler builds its action from its own application package. Some Alpha builds
    // still declare the stable action in their manifest (verified on 2.11.23.Alpha, Android 11).
    private val PACKAGES = listOf("com.github.metacubex.clash.meta", "com.github.metacubex.clash.alpha")
    private const val ACTIVITY = "com.github.kr328.clash.ExternalControlActivity"

    enum class Result { Sent, Unavailable, Failed }

    fun available(context: Context): Boolean = toggleIntent(context) != null

    private fun toggleIntent(context: Context): Intent? = PACKAGES.asSequence().map { pkg ->
            Intent("$pkg.action.TOGGLE_CLASH").setComponent(ComponentName(pkg, ACTIVITY))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }.firstOrNull { candidate ->
            // It is an explicit component call: validate the component, not the stale filter.
            val target = try {
                context.packageManager.getActivityInfo(candidate.component!!, 0)
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                null
            } catch (_: SecurityException) {
                null
            }
            if (target == null || !target.exported || !target.enabled || !target.applicationInfo.enabled) return@firstOrNull false
            // Android 13 also checks the filter on an explicit call into a modern app.
            Build.VERSION.SDK_INT < 33 || target.applicationInfo.targetSdkVersion < 33 ||
                context.packageManager.queryIntentActivities(Intent(candidate.action).setPackage(target.packageName), 0)
                    .any { it.activityInfo.name == target.name }
        }

    // The client owns both its running state and VPN permission. A system VPN badge may belong
    // to another app, so it must never determine whether we send START or STOP here.
    fun toggle(context: Context): Result {
        val intent = toggleIntent(context) ?: return Result.Unavailable
        return try {
            // VpnService.prepare(context) checks the caller, not the remote client. Initial VPN
            // approval must happen inside Clash; dispatch alone does not prove it connected.
            context.startActivity(intent)
            Result.Sent
        } catch (_: android.content.ActivityNotFoundException) {
            Result.Unavailable
        } catch (_: SecurityException) {
            Result.Failed
        }
    }
}

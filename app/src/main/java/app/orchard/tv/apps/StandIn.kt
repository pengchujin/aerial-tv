package app.orchard.tv.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * An entry of the same page that Aerial may start, for a launcher entry that needs a permission
 * Aerial cannot hold. Xiaomi's PatchWall lists `.MainActivityUserMode`, which requires its
 * signature permission HOME_STATE; `.MainActivity` beside it shows the same home and needs none
 * (MiTV-ANSP0, Android 9, issue #971; the same two entries on MiTV-MFTR0, Android 11). With
 * Aerial as the default home, the "Desktop" tile did nothing at all. Context.checkSelfPermission is
 * Android 6+; ContextCompat keeps this from crashing Android 5.
 */
object StandIn {
    fun find(context: Context, blocked: ComponentName): ComponentName? {
        val pm = context.packageManager
        val entries = runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).setPackage(blocked.packageName), 0)
        }.getOrNull().orEmpty().mapNotNull { r ->
            val a = r.activityInfo ?: return@mapNotNull null
            val open = a.exported && (a.permission == null ||
                ContextCompat.checkSelfPermission(context, a.permission) == PackageManager.PERMISSION_GRANTED)
            if (open) a.name else null
        }
        return pick(blocked.className, entries)?.let { ComponentName(blocked.packageName, it) }
    }

    /**
     * Among [open] activity names, the one [blocked]'s name extends (`MainActivityUserMode` →
     * `MainActivity`): the same page. Nothing else of the package is taken for it (PatchWall also
     * has an ErrorActivity and a video player under MAIN).
     */
    fun pick(blocked: String, open: List<String>): String? =
        open.filter { it != blocked && blocked.startsWith(it) }.maxByOrNull { it.length }
}

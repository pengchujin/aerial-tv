package app.orchard.tv.lite

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * TCL MS901 / Android 4.2.1 (MStar edison), verified on 192.168.0.151 on 2026-11-15.
 *
 * This firmware ships its settings as com.tcl.mstar.settings (/system/app/SystemSettings_901.apk).
 * It has neither com.android.settings nor com.tcl.settings, and nothing answers
 * android.settings.SETTINGS / android.settings.WIFI_SETTINGS, so every page in [SystemIntents]'s
 * original lists resolves to nothing and Control Center's gear and network buttons did nothing.
 *
 * The two activities below are the ones the TV's own menu opens, read from that APK's manifest:
 *   .TclSettings             - android.intent.action.MAIN (LAUNCHER)
 *   .NetworkSettingsActivity - android.intent.action.NETWORKSETTINGS
 * Neither declares android:exported, but both have an intent filter, so on this 4.2 firmware they
 * are exported and a third-party Home may start them.
 *
 * This is a CAPABILITY check, not a model string: only where both activities are present, enabled
 * and startable do the extra Intents go in front of [SystemIntents]' original list. Every other
 * TV gets empty arrays and keeps exactly the same candidate order as before. Nothing is compiled
 * against a vendor jar; the components are plain ComponentNames.
 */
internal object TclMs901Pages {
    private const val TAG = "AerialTclPages"
    private const val PKG = "com.tcl.mstar.settings"
    private val SETTINGS = ComponentName(PKG, "$PKG.TclSettings")
    private val NETWORK = ComponentName(PKG, "$PKG.NetworkSettingsActivity")

    /** Candidates to try first for the gear button; empty on every other firmware. */
    fun settings(context: Context): Array<Intent> =
        if (available(context)) arrayOf(Intent(Intent.ACTION_MAIN).setComponent(SETTINGS)) else emptyArray()

    /** Candidates to try first for the network button; empty on every other firmware. */
    fun network(context: Context): Array<Intent> =
        if (available(context)) arrayOf(Intent("android.intent.action.NETWORKSETTINGS").setComponent(NETWORK)) else emptyArray()

    /** Both pages belong to the same firmware: one missing means this is not that settings app. */
    private fun available(context: Context): Boolean = startable(context, SETTINGS) && startable(context, NETWORK)

    /** Same rules as SystemIntents.startable: exported, enabled, and any permission it asks for held. */
    private fun startable(context: Context, component: ComponentName): Boolean {
        val info = runCatching { context.packageManager.getActivityInfo(component, 0) }
            .onFailure { Log.d(TAG, "$component is not on this firmware", it) }.getOrNull() ?: return false
        if (!info.exported || !info.enabled || !info.applicationInfo.enabled) return false
        val permission = info.permission ?: return true
        return context.checkCallingOrSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
}

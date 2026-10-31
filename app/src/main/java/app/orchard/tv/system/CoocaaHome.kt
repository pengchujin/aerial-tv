package app.orchard.tv.system

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import java.io.File

/**
 * Tianci / Coocaa firmwares name their own launcher in two places: DEFAULT_HOMEPAGE in
 * /system/pcfg/<model>/config/general_config.xml (read-only, world-readable), and
 * persist.service.homepage.pkg/.cls, which com.tianci.system starts by component on the Home key.
 * Where Aerial has pointed the properties at itself (MainActivity.claimFirmwareHome), the values it
 * wrote over are kept in Settings.Global [SAVED_HOMEPAGE] / [SAVED_MODEL] as "pkg/cls", for
 * tools/restore.sh.
 */
object CoocaaHome {
    const val SAVED_HOMEPAGE = "aerial_coocaa_homepage"
    const val SAVED_MODEL = "aerial_coocaa_model"

    /**
     * The firmware's own launcher, or null where there is none Aerial can name: DEFAULT_HOMEPAGE
     * first, then what Aerial kept, then persist.service.homepage.*; each only when the activity
     * exists and is not Aerial. On Skyworth 8R734_A7HP (Android 13) it is
     * com.tianci.movieplatform/com.coocaa.aigc.home.home.rootpage.HomeActivity, which declares no
     * Home category at all. Not the properties first: at boot SkyBootIntent "resets" homepage.*
     * to the literal strings home[0] / home[1] before something puts the real value back.
     */
    fun launcher(context: Context): ComponentName? {
        val saved = runCatching { Settings.Global.getString(context.contentResolver, SAVED_HOMEPAGE) }.getOrNull()
        val named = "${property("persist.service.homepage.pkg").orEmpty()}/${property("persist.service.homepage.cls").orEmpty()}"
        return sequenceOf(defaultHomepage(), saved, named)
            .mapNotNull { it?.let(ComponentName::unflattenFromString) }
            .firstOrNull { isLauncher(context, it) }
    }

    /** Whether [component] is an activity on this TV, and not Aerial: worth keeping or opening. */
    fun isLauncher(context: Context, component: ComponentName): Boolean =
        component.packageName.isNotEmpty() && component.className.isNotEmpty() &&
            component.packageName != context.packageName &&
            runCatching { context.packageManager.getActivityInfo(component, 0) }.isSuccess

    /**
     * Whether a property's value ("pkg/cls") is worth keeping for restore. It is not looked up:
     * the launcher a Coocaa set names may answer no PackageManager query at all — on Skyworth
     * 8R721_A5FP (Android 13) resolve-activity found no Home candidate before Aerial took over, and
     * the firmware still started its launcher by component — and skipping the save left
     * tools/restore.sh nothing to put back (PR #992, the 8R721 report). Empty halves, Aerial
     * itself and the literal home[0] / home[1] SkyBootIntent.initGeneralConfig writes at a boot are
     * not kept; a real activity is not required.
     */
    fun worthKeeping(context: Context, value: String): Boolean {
        val component = runCatching { ComponentName.unflattenFromString(value) }.getOrNull() ?: return false
        if (component.packageName.isEmpty() || component.className.isEmpty()) return false
        if (component.packageName == context.packageName) return false
        if (component.packageName.startsWith(PLACEHOLDER) || component.className.startsWith(PLACEHOLDER)) return false
        return true
    }

    /** The front of the placeholder SkyBootIntent leaves in homepage.* for some seconds at a boot. */
    private const val PLACEHOLDER = "home["

    /** DEFAULT_HOMEPAGE, found the way SkyBootIntent.initPcfgPath finds the file. */
    private fun defaultHomepage(): String? = runCatching {
        val model = property("ro.build.skymodel").orEmpty()
        val type = property("ro.build.skytype").orEmpty()
        if (model.isEmpty() || type.isEmpty()) return null
        val base = File("/system/pcfg/${model}_$type")
        val panel = property("persist.sys.panel_size").orEmpty()
        val param = property("third.get.panel.param").orEmpty()
        val dirs = buildList {
            if (panel.isNotEmpty()) {
                if (param.isNotEmpty()) add(File(base, "${panel}_$param"))
                add(File(base, panel))
            }
            add(base)
        }
        val file = dirs.map { File(it, "config/general_config.xml") }.firstOrNull { it.canRead() } ?: return null
        Regex("""name="DEFAULT_HOMEPAGE"\s+value="([^"]+)"""").find(file.readText())?.groupValues?.get(1)
    }.getOrNull()

    private fun property(key: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as String
    }.getOrNull()
}

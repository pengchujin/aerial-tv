package app.orchard.tv.system

import android.content.ComponentName
import android.content.Context
import java.io.File

/**
 * Tianci / Coocaa firmwares name their own launcher in two places: DEFAULT_HOMEPAGE in
 * /system/pcfg/<model>/config/general_config.xml (read-only, world-readable), and
 * persist.service.homepage.pkg/.cls, which com.tianci.system starts by component on the Home key.
 * Aerial only reads them. PR #992 also had Aerial write those properties to itself while it is
 * Home; that part was not merged: uninstalled without tools/restore.sh first, Skyworth 8R734_A7HP
 * then had no Home at all until a reboot (the PR author's test, 2026-11-03).
 */
object CoocaaHome {
    /**
     * The firmware's own launcher, or null where there is none Aerial can name: DEFAULT_HOMEPAGE
     * first, then persist.service.homepage.*; each only when the activity exists and is not Aerial.
     * On Skyworth 8R734_A7HP (Android 13) it is
     * com.tianci.movieplatform/com.coocaa.aigc.home.home.rootpage.HomeActivity, which declares no
     * Home category at all. Not the properties first: at boot SkyBootIntent "resets" homepage.*
     * to the literal strings home[0] / home[1] before something puts the real value back.
     */
    fun launcher(context: Context): ComponentName? {
        val named = "${property("persist.service.homepage.pkg").orEmpty()}/${property("persist.service.homepage.cls").orEmpty()}"
        return sequenceOf(defaultHomepage(), named)
            .mapNotNull { it?.let(ComponentName::unflattenFromString) }
            .firstOrNull { isLauncher(context, it) }
    }

    /** Whether [component] is an activity on this TV, and not Aerial: worth opening. */
    fun isLauncher(context: Context, component: ComponentName): Boolean =
        component.packageName.isNotEmpty() && component.className.isNotEmpty() &&
            component.packageName != context.packageName &&
            runCatching { context.packageManager.getActivityInfo(component, 0) }.isSuccess

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

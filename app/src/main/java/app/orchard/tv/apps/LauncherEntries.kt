package app.orchard.tv.apps

import android.content.ComponentName
import android.content.pm.ActivityInfo

/** A distinct MAIN launcher component, even when several components belong to one APK. */
internal data class LauncherEntry(val key: String, val info: ActivityInfo, val leanback: Boolean)

/**
 * Collects launcher components in query priority order. The first component in an APK retains the
 * package-name key that older Aerial versions stored in layout preferences; later components get
 * their own stable component key. A component exposed in more than one launcher category appears
 * once, with the first category winning.
 */
internal class LauncherEntries {
    private val components = LinkedHashMap<String, Pair<ActivityInfo, Boolean>>()

    fun add(info: ActivityInfo, leanback: Boolean, onlyWhenPackageMissing: Boolean = false): Boolean {
        if (!info.exported || !info.isEnabled || !info.applicationInfo.enabled) return false
        if (onlyWhenPackageMissing && hasPackage(info.packageName)) return false
        val component = ComponentName(info.packageName, info.name).flattenToString()
        if (component in components) return false
        components[component] = info to leanback
        return true
    }

    fun hasPackage(pkg: String): Boolean = components.values.any { it.first.packageName == pkg }

    fun removePackage(pkg: String) {
        components.filterValues { it.first.packageName == pkg }.keys.toList().forEach(components::remove)
    }

    fun all(): List<LauncherEntry> {
        val primaryPackages = HashSet<String>()
        return components.map { (component, value) ->
            val (info, leanback) = value
            LauncherEntry(
                key = if (primaryPackages.add(info.packageName)) info.packageName else "$ACTIVITY_KEY_PREFIX$component",
                info = info,
                leanback = leanback,
            )
        }
    }
}

private const val ACTIVITY_KEY_PREFIX = "activity:"

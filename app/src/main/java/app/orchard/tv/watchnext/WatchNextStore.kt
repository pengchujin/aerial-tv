package app.orchard.tv.watchnext

import android.content.Context

/** Seen packages distinguish a new install from a source deliberately switched off. */
class WatchNextStore(context: Context) {
    private val prefs = context.getSharedPreferences("watch_next", Context.MODE_PRIVATE)
    val enabled get() = prefs.getBoolean("enabled", false)
    val sources: Set<String> get() = prefs.getStringSet("sources", emptySet())!!.toSet()
    var asked: Boolean
        get() = prefs.getBoolean("asked", false)
        set(value) { prefs.edit().putBoolean("asked", value).apply() }

    fun discover(packages: Set<String>) {
        val seen = prefs.getStringSet("seen", emptySet())!!.toSet()
        val fresh = packages - seen
        if (fresh.isEmpty()) return
        prefs.edit().putStringSet("sources", sources + fresh).putStringSet("seen", seen + packages).apply()
    }

    fun master(on: Boolean, available: Set<String>) {
        val edit = prefs.edit().putBoolean("enabled", on)
        if (on && !enabled && sources.isEmpty()) edit.putStringSet("sources", available)
        edit.apply()
    }

    fun source(pkg: String, on: Boolean) {
        prefs.edit().putStringSet("sources", if (on) sources + pkg else sources - pkg).apply()
    }
}

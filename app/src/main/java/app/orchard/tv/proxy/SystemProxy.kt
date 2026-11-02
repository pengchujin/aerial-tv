package app.orchard.tv.proxy

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import org.json.JSONObject

/** Owns only the proxy installed by this app, with a durable rollback before changing Android. */
object SystemProxy {
    private const val PREFS = "proxy_system_rollback"
    private const val ORIGINAL = "original"
    private const val OWN = "own"
    private const val RESTORING = "restoring"
    private const val LEGACY = "http_proxy"
    private const val HOST = "global_http_proxy_host"
    private const val PORT = "global_http_proxy_port"
    private const val EXCLUDES = "global_http_proxy_exclusion_list"
    private const val PAC = "global_proxy_pac_url"
    private val keys = listOf(LEGACY, HOST, PORT, EXCLUDES, PAC)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun read(context: Context, key: String): String? = Settings.Global.getString(context.contentResolver, key)
    private fun JSONObject.raw(key: String): String? = if (isNull(key)) null else getString(key)
    private fun snapshot(context: Context): JSONObject = JSONObject().also { state ->
        keys.forEach { key -> state.put(key, read(context, key) ?: JSONObject.NULL) }
    }

    private fun endpoint(state: JSONObject): String? {
        val host = state.raw(HOST).orEmpty()
        val port = state.raw(PORT)?.toIntOrNull() ?: 0
        if (host.isNotEmpty() && port in 1..65535 && ':' !in host) return "$host:$port"
        val legacy = state.raw(LEGACY).orEmpty()
        if (legacy.isBlank() || legacy == ":0" || legacy == "-1") return null
        val pieces = legacy.split(':')
        require(pieces.size == 2 && pieces[0].isNotBlank() && (pieces[1].toIntOrNull() ?: 0) in 1..65535) {
            "Existing system proxy cannot be safely restored"
        }
        return legacy
    }

    @Synchronized
    fun apply(context: Context, host: String, port: Int): Boolean {
        require(host == "127.0.0.1" && port in 1024..65535) { "Invalid local proxy endpoint" }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return false
        val store = prefs(context)
        val target = "$host:$port"
        if (store.contains(ORIGINAL)) {
            if (store.getString(OWN, null) == target && isApplied(context)) return true
            if (!restore(context)) return false
        }
        val before = snapshot(context)
        // Android 9 exposes only the legacy change trigger to a settings-authorized app. It
        // cannot restore PAC/exclusion runtime state, so leave those existing setups untouched.
        if (!before.raw(PAC).isNullOrBlank() || !before.raw(EXCLUDES).isNullOrBlank()) return false
        if (runCatching { endpoint(before) }.isFailure) return false
        if (!store.edit().putString(ORIGINAL, before.toString()).putString(OWN, target).remove(RESTORING).commit()) return false
        return try {
            if (!Settings.Global.putString(context.contentResolver, LEGACY, target)) {
                restore(context)
                false
            } else if (awaitEndpoint(context, host, port)) true else {
                restore(context)
                false
            }
        } catch (_: Exception) {
            restore(context)
            false
        }
    }

    /** Called after a dead core/old app process, before starting another engine. Never auto-enables. */
    @Synchronized
    fun recover(context: Context): Boolean = restore(context)

    @Synchronized
    fun isApplied(context: Context): Boolean {
        val own = prefs(context).getString(OWN, null) ?: return false
        return read(context, LEGACY) == own &&
            "${read(context, HOST)}:${read(context, PORT)}" == own &&
            read(context, PAC).isNullOrBlank() && read(context, EXCLUDES).isNullOrBlank()
    }

    /** Does not overwrite settings someone changed after Aerial applied its local endpoint. */
    @Synchronized
    fun restore(context: Context): Boolean {
        val store = prefs(context)
        val saved = store.getString(ORIGINAL, null) ?: return true
        val own = store.getString(OWN, null) ?: return false
        val before = runCatching { JSONObject(saved) }.getOrNull() ?: return false
        val target = runCatching { endpoint(before) }.getOrElse { return false }
        val trigger = target ?: ":0"
        val currentLegacy = read(context, LEGACY)
        val currentHost = read(context, HOST)
        val currentPort = read(context, PORT)
        val ownParts = own.split(':')
        val targetParts = target?.split(':')
        val ownEndpoint = currentHost == ownParts[0] && currentPort == ownParts[1]
        val savedEndpoint = currentHost == before.raw(HOST) && currentPort == before.raw(PORT)
        val restoredEndpoint = currentHost.orEmpty() == targetParts?.get(0).orEmpty() &&
            (currentPort?.toIntOrNull() ?: 0) == (targetParts?.get(1)?.toIntOrNull() ?: 0)
        val restoring = store.getBoolean(RESTORING, false)
        // A durable phase marker lets a subsequent stop/relaunch finish a partial rollback.
        // A different endpoint or PAC/exclusions still means an administrator took over.
        val ours = read(context, PAC).isNullOrBlank() && read(context, EXCLUDES).isNullOrBlank() &&
            (currentLegacy == own && (ownEndpoint || savedEndpoint) ||
                restoring && (currentLegacy == trigger || currentLegacy == before.raw(LEGACY)) &&
                (ownEndpoint || savedEndpoint || restoredEndpoint))
        if (!ours) {
            // A user/admin took over; discard our lease instead of replacing their newer choice.
            return store.edit().remove(ORIGINAL).remove(OWN).remove(RESTORING).commit()
        }
        return try {
            if (!store.edit().putBoolean(RESTORING, true).commit()) return false
            // Clearing the legacy key alone does not clear Android 9 ConnectivityService.
            // The valid empty host/zero-port trigger clears its in-memory ProxyInfo first.
            if (!Settings.Global.putString(context.contentResolver, LEGACY, target ?: ":0")) return false
            val parts = target?.split(':')
            if (!awaitEndpoint(context, parts?.get(0).orEmpty(), parts?.get(1)?.toIntOrNull() ?: 0)) return false
            val restored = keys.map { key -> Settings.Global.putString(context.contentResolver, key, before.raw(key)) }.all { it }
            if (restored) store.edit().remove(ORIGINAL).remove(OWN).remove(RESTORING).commit() else false
        } catch (_: Exception) {
            false
        }
    }

    private fun awaitEndpoint(context: Context, host: String, port: Int): Boolean {
        val end = SystemClock.elapsedRealtime() + 2_000
        do {
            val actualHost = read(context, HOST).orEmpty()
            val actualPort = read(context, PORT)?.toIntOrNull() ?: 0
            if (actualHost == host && actualPort == port) return true
            Thread.sleep(40)
        } while (SystemClock.elapsedRealtime() < end)
        return false
    }
}
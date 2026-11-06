package app.orchard.tv.system

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-model corrections the framework cannot tell us, as data: `assets/devices.json` holds the
 * ones found on reporters' TVs (each entry says where), and a TV can carry its own over ADB
 * (`ConfigReceiver --es device_profile '<json>'`, what site/adapt.md has an agent do). The TV's
 * own one replaces the built-in entry whole, so what was tried on the TV is exactly what goes
 * into `devices.json` once it is sent in.
 *
 * Inputs are keyed by the hardware id at the end of their TvInputInfo id (`.../HW5` is 5).
 */
class DeviceProfile(
    val hiddenHardware: Set<Int> = emptySet(),
    val labels: Map<Int, String> = emptyMap(),
    /** Xiaomi tvplayer source ids for inputs that have no rule or another one, by hardware id. */
    val xiaomiSourceIds: Map<Int, Int> = emptyMap(),
    /** Like [xiaomiSourceIds], but only where the firmware has no hdmiN_order of its own. */
    val xiaomiFallbackSourceIds: Map<Int, Int> = emptyMap(),
    /**
     * The TV keeps `global picture_backlight` but does not follow it (MiTV-MFTP0, two owners write
     * its panel, issues #284 and #335): Control Center leaves brightness to the TV's own slider.
     * Nothing on the TV tells this apart, hence a profile entry.
     */
    val ignoresPictureBacklight: Boolean = false,
    /**
     * At power-on, let the stock launcher be on screen this long (ms) before Home Guard takes Home.
     * Whaley WTV43K1J (Helios, Android 5.0.2) ends its boot, and stops the power light breathing,
     * only once its own launcher has been seen; taken over before its first frame, the light kept
     * breathing until the next standby (issue #902, the reporter's test with adb). Nothing an app
     * can read tells this apart, hence a profile entry.
     */
    val bootStockMs: Int = 0,
    /**
     * The TV takes `screen_brightness` and moves `mActualBacklight`, yet the panel stays as it was:
     * its picture middleware owns the backlight (Coocaa 5S09_A3, MStar, Android 6, issue #895,
     * checked by the owner in front of the TV). Control Center opens the TV's picture settings
     * instead of a slider that changes nothing. Nothing on the TV tells this apart.
     */
    val ignoresScreenBrightness: Boolean = false,
    /**
     * Home Guard filters keys whenever the screen is on, whatever is in front, and answers Home
     * itself. For firmwares whose own Home force-stops the app in front: Redmi Projector 4
     * (mt5873, Android 13) did so to Aerial and to any app, a 1-2 s flash each press, and with
     * the filter on it never did, but a held volume key then moved one step only (issue #684, the
     * reporter's local build). Which of the two is worse is the owner's call, so only by profile.
     */
    val filterHomeAlways: Boolean = false,
    /**
     * Home Guard filters no keys at all. Up to Android 11 it otherwise filters everywhere, and on
     * LeTV X4-40 (MStar 6A938, Android 6) one press of a direction key then moved two to five
     * places, in the firmware's own lists as well, while getevent showed one press and injected
     * keys moved one (issue #115, the reporter's test with the capability removed). Home then goes
     * to the system: 0.2-0.5 s on that TV with the stock launcher showing for about 1 s, and up to
     * 5 s where Android holds app starts after Home (TCL MT5879). The owner's call, so only by profile.
     */
    val neverFilterKeys: Boolean = false,
) {
    val isEmpty get() = hiddenHardware.isEmpty() && labels.isEmpty() && xiaomiSourceIds.isEmpty() &&
        xiaomiFallbackSourceIds.isEmpty() && !ignoresPictureBacklight && bootStockMs <= 0 &&
        !ignoresScreenBrightness && !filterHomeAlways && !neverFilterKeys

    /** The same fields `devices.json` uses, without the matching ones (models, buildMajorBelow, buildAtLeast). */
    fun toJson(): JSONObject = JSONObject().apply {
        if (hiddenHardware.isNotEmpty()) put("hidden", JSONArray(hiddenHardware.sorted()))
        if (labels.isNotEmpty()) put("labels", JSONObject(labels.toSortedMap().mapKeys { it.key.toString() }))
        if (xiaomiSourceIds.isNotEmpty()) put("xiaomiSources", JSONObject(xiaomiSourceIds.toSortedMap().mapKeys { it.key.toString() }))
        if (xiaomiFallbackSourceIds.isNotEmpty()) put("xiaomiFallbackSources", JSONObject(xiaomiFallbackSourceIds.toSortedMap().mapKeys { it.key.toString() }))
        if (ignoresPictureBacklight) put("ignoresPictureBacklight", true)
        if (bootStockMs > 0) put("bootStockMs", bootStockMs)
        if (ignoresScreenBrightness) put("ignoresScreenBrightness", true)
        if (filterHomeAlways) put("filterHomeAlways", true)
        if (neverFilterKeys) put("neverFilterKeys", true)
    }

    /** Where the profile in use came from, for the diagnostics. */
    enum class Origin { BuiltIn, ThisTv, None }

    companion object {
        private const val PREFS = "system"
        private const val KEY = "device_profile"

        @Volatile private var cached: Pair<DeviceProfile, Origin>? = null

        fun current(context: Context): DeviceProfile = load(context).first

        fun origin(context: Context): Origin = load(context).second

        private fun load(context: Context): Pair<DeviceProfile, Origin> = cached ?: run {
            val own = ownJson(context)?.let { runCatching { parse(JSONObject(it)) }.getOrNull() }
            val result = when {
                own != null -> own to Origin.ThisTv
                else -> builtIn(context)?.let { it to Origin.BuiltIn } ?: (DeviceProfile() to Origin.None)
            }
            cached = result
            result
        }

        /** This model's entry in devices.json, ignoring the TV's own profile. */
        fun builtIn(context: Context): DeviceProfile? = runCatching {
            val json = context.assets.open("devices.json").bufferedReader().use { it.readText() }
            match(json, Build.MODEL, buildMajor(), buildMinor())
        }.getOrNull()

        /** The TV's own profile as stored, or null when it uses the built-in one. */
        fun ownJson(context: Context): String? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)

        /**
         * Stores the TV's own profile ([json] null or blank goes back to the built-in one). Returns
         * false, storing nothing, when [json] is not a profile.
         */
        fun setOwn(context: Context, json: String?): Boolean {
            val text = json?.trim()?.takeIf { it.isNotEmpty() }
            val normalized = if (text == null) null else runCatching { parse(JSONObject(text)).toJson().toString() }.getOrNull() ?: return false
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
                if (normalized == null) remove(KEY) else putString(KEY, normalized)
            }.commit()
            cached = null
            return true
        }

        /** The first entry of [json] (devices.json) for [model] whose firmware condition holds. */
        fun match(json: String, model: String, buildMajor: Int, buildMinor: Int = 0): DeviceProfile? {
            val entries = JSONObject(json).getJSONArray("profiles")
            for (i in 0 until entries.length()) {
                val entry = entries.getJSONObject(i)
                val models = entry.getJSONArray("models")
                if ((0 until models.length()).none { models.getString(it) == model }) continue
                if (entry.has("buildMajorBelow") && buildMajor >= entry.getInt("buildMajorBelow")) continue
                // "22.11": Mi TV 4 numbers its HDMI ports differently from firmware 22.11 on (#584).
                if (entry.has("buildAtLeast")) {
                    val (major, minor) = entry.getString("buildAtLeast").split('.').map { it.toInt() } + listOf(0)
                    if (buildMajor < major || buildMajor == major && buildMinor < minor) continue
                }
                return parse(entry)
            }
            return null
        }

        fun parse(o: JSONObject): DeviceProfile {
            fun ints(key: String): Map<Int, Int> = o.optJSONObject(key)?.let { m ->
                m.keys().asSequence().associate { it.trim().toInt() to m.getInt(it) }
            } ?: emptyMap()
            return DeviceProfile(
                hiddenHardware = o.optJSONArray("hidden")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() } ?: emptySet(),
                labels = o.optJSONObject("labels")?.let { m ->
                    m.keys().asSequence().associate { it.trim().toInt() to m.getString(it).take(24) }
                } ?: emptyMap(),
                xiaomiSourceIds = ints("xiaomiSources"),
                xiaomiFallbackSourceIds = ints("xiaomiFallbackSources"),
                ignoresPictureBacklight = o.optBoolean("ignoresPictureBacklight", false),
                bootStockMs = o.optInt("bootStockMs", 0).coerceIn(0, 45_000),
                ignoresScreenBrightness = o.optBoolean("ignoresScreenBrightness", false),
                filterHomeAlways = o.optBoolean("filterHomeAlways", false),
                neverFilterKeys = o.optBoolean("neverFilterKeys", false),
            )
        }

        /** The first number of a build id such as Xiaomi's 22.4.15.3234, or 0. */
        private fun buildMajor(): Int = Build.DISPLAY.substringBefore('.').toIntOrNull() ?: 0

        /** The second number of such a build id (4 in 22.4.15.3234), or 0. */
        private fun buildMinor(): Int = Build.DISPLAY.split('.').getOrNull(1)?.toIntOrNull() ?: 0
    }
}

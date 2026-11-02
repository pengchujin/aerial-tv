package app.orchard.tv.weather

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import javax.net.ssl.SSLException
import kotlin.math.roundToInt

enum class WeatherKind { Clear, PartlyCloudy, Cloudy, Fog, Rain, Snow, Thunder }

/** [high] and [low] are today's, shown under the large clock; null in a reading saved by an older version. */
data class WeatherNow(val celsius: Int, val kind: WeatherKind, val day: Boolean, val high: Int? = null, val low: Int? = null)

/**
 * The weather beside the home clock, from Open-Meteo: no key, reachable from China. The place is
 * the one the owner names on the phone page, or else a guess from the network's public address.
 * The guess is often wrong behind a proxy (a whole home behind one shows another country), which
 * is why the phone page shows it and lets the owner correct it.
 *
 * Fetched when Home comes to the front, at most once an hour; nothing runs while the TV is idle.
 * Compose state, written on the main thread only.
 */
object Weather {
    private const val TAG = "AerialWeather"
    private const val PREFS = "weather"
    private const val REFRESH_MS = 60 * 60 * 1000L
    private const val RETRY_MS = 10 * 60 * 1000L
    private const val RELOCATE_MS = 24 * 60 * 60 * 1000L
    /** A reading older than this is not shown at all. */
    private const val STALE_MS = 3 * 60 * 60 * 1000L
    /** Stands for "no high or low" in the prefs. */
    private const val NONE = Int.MIN_VALUE

    var now by mutableStateOf<WeatherNow?>(null); private set

    /** Where the weather is for, as a name to show; null until known. */
    var place by mutableStateOf<String?>(null); private set

    /** True when the owner named the place; false while it is guessed from the network. */
    var chosen by mutableStateOf(false); private set

    private lateinit var prefs: SharedPreferences
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    private var lastAttempt = 0L
    private var job: Job? = null

    fun init(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        place = prefs.getString("place", null)
        chosen = prefs.getBoolean("chosen", false)
        if (System.currentTimeMillis() - prefs.getLong("fetched_at", 0) < STALE_MS) {
            val kind = runCatching { WeatherKind.valueOf(prefs.getString("kind", null) ?: "") }.getOrNull()
            if (kind != null) now = WeatherNow(
                prefs.getInt("celsius", 0), kind, prefs.getBoolean("day", true),
                high = prefs.getInt("high", NONE).takeIf { it != NONE },
                low = prefs.getInt("low", NONE).takeIf { it != NONE },
            )
        }
    }

    private var failed = false

    /** Like the daily photos: a failed reading is retried when the network connects (issue #129). */
    fun refreshIfStale(networkCameUp: Boolean = false) {
        if (!started) return
        val since = System.currentTimeMillis() - lastAttempt
        if (since >= REFRESH_MS || (failed && (networkCameUp || since >= RETRY_MS))) refresh()
    }

    private fun refresh() {
        lastAttempt = System.currentTimeMillis()
        job?.cancel()
        job = scope.launch {
            val reading = withContext(Dispatchers.IO) {
                runCatching { fetch() }.onFailure { Log.w(TAG, "weather unavailable", it) }.getOrNull()
            }
            prefs.getString("place", null)?.let { place = it }
            failed = reading == null
            if (reading != null) {
                now = reading
                prefs.edit {
                    putLong("fetched_at", System.currentTimeMillis())
                    putInt("celsius", reading.celsius)
                    putString("kind", reading.kind.name)
                    putBoolean("day", reading.day)
                    putInt("high", reading.high ?: NONE)
                    putInt("low", reading.low ?: NONE)
                }
            }
        }
    }

    /** Off the main thread. */
    private fun fetch(): WeatherNow {
        var lat = prefs.getFloat("lat", Float.NaN)
        var lon = prefs.getFloat("lon", Float.NaN)
        val guessedLongAgo = System.currentTimeMillis() - prefs.getLong("located_at", 0) > RELOCATE_MS
        if (prefs.getBoolean("chosen", false) && !lat.isNaN() && !prefs.getBoolean("checked_against_cities", false)) {
            correctChosenPlace(lat, lon)?.let { lat = it.lat.toFloat(); lon = it.lon.toFloat() }
        }
        if (!prefs.getBoolean("chosen", false) && (lat.isNaN() || guessedLongAgo)) {
            val guess = locate()
            if (guess != null) {
                lat = guess.lat
                lon = guess.lon
                val city = guess.city
                // The services name the city in English ("Foshan"); in China, say it as the owner would.
                // A name the table spells otherwise, or a district's, stayed in pinyin among Chinese
                // text (Hisense 43E3G, issue #1195): then the main listed place at that point names it.
                val zh = city?.takeIf { guess.cn && Locale.getDefault().language == "zh" }
                    ?.let {
                        (Cities.near(app, it, lat.toDouble(), lon.toDouble(), km = 80.0, ascii = true)
                            ?: Cities.mainNear(app, lat.toDouble(), lon.toDouble(), km = 20.0))?.name
                    }
                prefs.edit {
                    putFloat("lat", lat); putFloat("lon", lon)
                    putString("place", zh ?: city)
                    putLong("located_at", System.currentTimeMillis())
                }
            }
        }
        val json = JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto"))
        val current = json.getJSONObject("current")
        val daily = json.optJSONObject("daily")
        fun today(key: String) = daily?.optJSONArray(key)?.optDouble(0)?.takeIf { !it.isNaN() }?.roundToInt()
        return WeatherNow(
            celsius = current.getDouble("temperature_2m").roundToInt(),
            kind = kindOf(current.getInt("weather_code")),
            day = current.optInt("is_day", 1) == 1,
            high = today("temperature_2m_max"),
            low = today("temperature_2m_min"),
        )
    }

    /**
     * Where the connection is: ipwho.is first, ipinfo.io after it. ipinfo.io puts China Mobile's
     * pooled addresses in the wrong province (a Foshan, Guangdong line came out as Jiaxing,
     * Zhejiang), where ipwho.is names the city; both are keyless and answer over HTTPS.
     */
    private class Guess(val lat: Float, val lon: Float, val city: String?, val cn: Boolean)

    private fun locate(): Guess? {
        // Over IPv6 the databases place China Mobile's prefixes in another city of the province
        // (a Foshan line came out as Jiangmen), so the IPv4 address is asked for and looked up.
        val v4 = runCatching { get("https://ipv4.icanhazip.com").trim() }.getOrNull()
            ?.takeIf { Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(it) }
        runCatching {
            val ip = JSONObject(get(if (v4 != null) "https://ipwho.is/$v4" else "https://ipwho.is/"))
            if (ip.optBoolean("success", true)) {
                return Guess(
                    ip.getDouble("latitude").toFloat(),
                    ip.getDouble("longitude").toFloat(),
                    ip.optString("city").ifBlank { null },
                    ip.optString("country_code").equals("CN", ignoreCase = true),
                )
            }
        }.onFailure { Log.w(TAG, "ipwho.is", it) }
        runCatching {
            // ipinfo.io: no key, and its "loc" is "lat,lon".
            val ip = JSONObject(get("https://ipinfo.io/json"))
            val loc = ip.getString("loc").split(',')
            return Guess(
                loc[0].toFloat(),
                loc[1].toFloat(),
                ip.optString("city").ifBlank { null },
                ip.optString("country") == "CN",
            )
        }.onFailure { Log.w(TAG, "ipinfo.io", it) }
        return null
    }

    /**
     * A place chosen before 1.29 came from Open-Meteo's geocoder, which took 安庆 for a village in
     * Jiangsu. If a listed city of that name is not where the chosen point is, the city is meant.
     * Checked once.
     */
    private fun correctChosenPlace(lat: Float, lon: Float): Cities.City? {
        val name = prefs.getString("place", null)
        val city = name?.let { runCatching { Cities.find(app, it) }.getOrNull() }
            ?.takeIf { Cities.near(app, it.name, lat.toDouble(), lon.toDouble(), km = 30.0) == null }
        prefs.edit {
            putBoolean("checked_against_cities", true)
            if (city != null) {
                Log.i(TAG, "chosen place $name moved to the city of that name")
                putFloat("lat", city.lat.toFloat()); putFloat("lon", city.lon.toFloat())
            }
        }
        return city
    }

    /**
     * Names the place (from the phone page) and fetches its weather at once. Chinese names are
     * looked up in Aerial's own list first, others (pinyin, places abroad) in Open-Meteo's geocoder.
     * False when there is no such place.
     */
    suspend fun choose(name: String): Boolean {
        val hit = withContext(Dispatchers.IO) {
            runCatching { Cities.find(app, name) }.onFailure { Log.w(TAG, "could not read the city list", it) }.getOrNull()
                ?.let { Triple(it.lat, it.lon, it.name) }
                ?: runCatching {
                    val q = URLEncoder.encode(name.trim(), "UTF-8")
                    val results = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=10&language=zh&format=json"))
                        .optJSONArray("results")
                    val all = (0 until (results?.length() ?: 0)).map { results!!.getJSONObject(it) }
                    // Towns before villages of the same name.
                    val best = all.firstOrNull { it.optString("feature_code").startsWith("PPLA") || it.optString("feature_code") == "PPLC" } ?: all.firstOrNull()
                    best?.let { Triple(it.getDouble("latitude"), it.getDouble("longitude"), it.optString("name").ifBlank { name.trim() }) }
                }.onFailure { Log.w(TAG, "could not look up $name", it) }.getOrNull()
        } ?: return false
        prefs.edit {
            putFloat("lat", hit.first.toFloat())
            putFloat("lon", hit.second.toFloat())
            putString("place", hit.third)
            putBoolean("chosen", true)
            putBoolean("checked_against_cities", true)
        }
        chosen = true
        place = prefs.getString("place", null)
        refresh()
        return true
    }

    /** Back to guessing the place from the network. */
    fun useNetworkLocation() {
        prefs.edit { putBoolean("chosen", false); remove("lat"); remove("lon"); remove("located_at") }
        chosen = false
        refresh()
    }

    /** WMO weather interpretation codes, as Open-Meteo reports them. */
    private fun kindOf(code: Int): WeatherKind = when (code) {
        0 -> WeatherKind.Clear
        1, 2 -> WeatherKind.PartlyCloudy
        3 -> WeatherKind.Cloudy
        45, 48 -> WeatherKind.Fog
        in 71..77, 85, 86 -> WeatherKind.Snow
        in 95..99 -> WeatherKind.Thunder
        else -> WeatherKind.Rain // drizzle, rain and showers: 51..67, 80..82
    }

    /**
     * Android before 7.1 does not trust the Let's Encrypt root these hosts use; they answer the
     * same over plain HTTP (allowed for them in network_security_config.xml).
     */
    private fun get(url: String): String = try {
        read(url)
    } catch (e: SSLException) {
        read(url.replaceFirst("https://", "http://"))
    }

    private fun read(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 10_000
        return try {
            c.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            c.disconnect()
        }
    }
}

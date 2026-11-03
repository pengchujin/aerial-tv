package app.orchard.tv.lite

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject
import java.util.Locale
import javax.net.ssl.SSLException
import kotlin.math.roundToInt

/**
 * The weather beside the clock, as the full app's weather/Weather.kt: Open-Meteo (no key, reachable
 * from China) for a place guessed from the network's address (ipinfo.io), named in Chinese from the
 * city list when the TV speaks Chinese. Lite has no phone page, so the place is always the guess.
 * Fetched when Home comes to the front, at most once an hour; nothing runs while the TV is idle.
 */
object Weather {
    private const val TAG = "AerialLite"
    private const val REFRESH_MS = 60 * 60 * 1000L
    private const val RETRY_MS = 10 * 60 * 1000L
    private const val RELOCATE_MS = 24 * 60 * 60 * 1000L
    private const val STALE_MS = 3 * 60 * 60 * 1000L
    private const val NONE = Int.MIN_VALUE

    enum class Kind { Clear, PartlyCloudy, Cloudy, Fog, Rain, Snow, Thunder }
    class Now(val celsius: Int, val kind: Kind, val day: Boolean, val high: Int?, val low: Int?)

    @Volatile var now: Now? = null; private set
    @Volatile var place: String? = null; private set
    /** The place was named on the phone page rather than guessed from the network. */
    @Volatile var chosen = false; private set

    private lateinit var prefs: SharedPreferences
    private lateinit var app: Context
    private var lastAttempt = 0L
    private var failed = false

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        app = context.applicationContext
        prefs = app.getSharedPreferences("weather", Context.MODE_PRIVATE)
        place = prefs.getString("place", null)
        chosen = prefs.getBoolean("chosen", false)
        if (System.currentTimeMillis() - prefs.getLong("fetched_at", 0) < STALE_MS) {
            val kind = runCatching { Kind.valueOf(prefs.getString("kind", null) ?: "") }.getOrNull()
            if (kind != null) now = Now(
                prefs.getInt("celsius", 0), kind, prefs.getBoolean("day", true),
                prefs.getInt("high", NONE).takeIf { it != NONE }, prefs.getInt("low", NONE).takeIf { it != NONE },
            )
        }
    }

    /** Fetches on [run] (a background executor) when due; [done] on the main thread when it changed. */
    fun refreshIfStale(run: (Runnable) -> Unit, force: Boolean = false, done: () -> Unit) {
        if (!::prefs.isInitialized) return
        val since = System.currentTimeMillis() - lastAttempt
        if (!force && since < REFRESH_MS && !(failed && since >= RETRY_MS)) return
        lastAttempt = System.currentTimeMillis()
        run(Runnable {
            val reading = runCatching { fetch() }.onFailure { Log.w(TAG, "weather unavailable", it) }.getOrNull()
            failed = reading == null
            if (reading != null) {
                now = reading
                place = prefs.getString("place", null)
                prefs.edit()
                    .putLong("fetched_at", System.currentTimeMillis())
                    .putInt("celsius", reading.celsius).putString("kind", reading.kind.name).putBoolean("day", reading.day)
                    .putInt("high", reading.high ?: NONE).putInt("low", reading.low ?: NONE)
                    .apply()
                done()
            }
        })
    }

    private fun fetch(): Now {
        var lat = prefs.getFloat("lat", Float.NaN)
        var lon = prefs.getFloat("lon", Float.NaN)
        if (!prefs.getBoolean("chosen", false) && (lat.isNaN() || System.currentTimeMillis() - prefs.getLong("located_at", 0) > RELOCATE_MS)) {
            val guess = locate() ?: throw java.io.IOException("no location")
            lat = guess.lat
            lon = guess.lon
            // The services name the city in English ("Shangqiu"); in China, say it as the TV's owner would.
            val city = guess.city
            val zh = city?.takeIf { guess.cn && Locale.getDefault().language == "zh" }
                ?.let {
                    // Else the main listed place at ipinfo's point (the full app's issue #1195).
                    runCatching {
                        (Cities.near(app, it, lat.toDouble(), lon.toDouble(), km = 80.0, ascii = true)
                            ?: Cities.mainNear(app, lat.toDouble(), lon.toDouble(), km = 20.0))?.name
                    }.getOrNull()
                }
            prefs.edit().putFloat("lat", lat).putFloat("lon", lon).putString("place", zh ?: city)
                .putLong("located_at", System.currentTimeMillis()).apply()
        }
        val json = JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto"))
        val current = json.getJSONObject("current")
        val daily = json.optJSONObject("daily")
        fun today(key: String) = daily?.optJSONArray(key)?.optDouble(0)?.takeIf { !it.isNaN() }?.roundToInt()
        return Now(current.getDouble("temperature_2m").roundToInt(), kindOf(current.getInt("weather_code")), current.optInt("is_day", 1) == 1,
            today("temperature_2m_max"), today("temperature_2m_min"))
    }

    /**
     * Names the place (from the phone page), as the full app's Weather.choose: Chinese names from
     * Aerial's own list first, others from Open-Meteo's geocoder. False when there is no such
     * place. Off the main thread; fetch again after it.
     */
    fun choose(name: String): Boolean {
        val hit = runCatching { Cities.find(app, name) }.getOrNull()?.let { Triple(it.lat, it.lon, it.name) }
            ?: runCatching {
                val q = java.net.URLEncoder.encode(name.trim(), "UTF-8")
                val results = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=10&language=zh&format=json")).optJSONArray("results")
                val all = (0 until (results?.length() ?: 0)).map { results!!.getJSONObject(it) }
                // Towns before villages of the same name.
                val best = all.firstOrNull { it.optString("feature_code").startsWith("PPLA") || it.optString("feature_code") == "PPLC" } ?: all.firstOrNull()
                best?.let { Triple(it.getDouble("latitude"), it.getDouble("longitude"), it.optString("name").ifBlank { name.trim() }) }
            }.onFailure { Log.w(TAG, "could not look up $name", it) }.getOrNull()
            ?: return false
        prefs.edit().putFloat("lat", hit.first.toFloat()).putFloat("lon", hit.second.toFloat())
            .putString("place", hit.third).putBoolean("chosen", true).commit()
        chosen = true
        place = hit.third
        return true
    }

    /** Back to guessing the place from the network. */
    fun useNetworkLocation() {
        prefs.edit().putBoolean("chosen", false).remove("lat").remove("lon").remove("located_at").commit()
        chosen = false
    }

    /** WMO weather interpretation codes, as Open-Meteo reports them. */
    private fun kindOf(code: Int): Kind = when (code) {
        0 -> Kind.Clear
        1, 2 -> Kind.PartlyCloudy
        3 -> Kind.Cloudy
        45, 48 -> Kind.Fog
        in 71..77, 85, 86 -> Kind.Snow
        in 95..99 -> Kind.Thunder
        else -> Kind.Rain
    }

    /** Old Androids do not trust the Let's Encrypt root these hosts use; they answer the same over plain HTTP. */
    private class Guess(val lat: Float, val lon: Float, val city: String?, val cn: Boolean)

    /**
     * ipwho.is by the IPv4 address first, ipinfo.io after it, as in the full app (PR #1191: ipinfo
     * put a China Mobile line in Guangdong in Zhejiang, and over IPv6 the city was another one).
     */
    private fun locate(): Guess? {
        val v4 = runCatching { get("https://ipv4.icanhazip.com").trim() }.getOrNull()
            ?.takeIf { Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(it) }
        runCatching {
            val ip = JSONObject(get(if (v4 != null) "https://ipwho.is/$v4" else "https://ipwho.is/"))
            if (ip.optBoolean("success", true)) {
                return Guess(ip.getDouble("latitude").toFloat(), ip.getDouble("longitude").toFloat(),
                    ip.optString("city").ifBlank { null }, ip.optString("country_code").equals("CN", ignoreCase = true))
            }
        }.onFailure { Log.w(TAG, "ipwho.is", it) }
        runCatching {
            val ip = JSONObject(get("https://ipinfo.io/json"))
            val loc = ip.getString("loc").split(',')
            return Guess(loc[0].toFloat(), loc[1].toFloat(), ip.optString("city").ifBlank { null }, ip.optString("country") == "CN")
        }.onFailure { Log.w(TAG, "ipinfo.io", it) }
        return null
    }

    private fun get(url: String): String = try {
        Net.text(url)
    } catch (e: SSLException) {
        Net.text(url.replaceFirst("https://", "http://"))
    }

    /** The clock's weather line: glyph, temperature, and "place · condition · high low", as the full app's. */
    fun line(context: Context): WeatherLine? {
        val w = now ?: return null
        val glyph = when (w.kind) {
            Kind.Clear -> if (w.day) Glyph.Sun else Glyph.Moon
            Kind.PartlyCloudy -> if (w.day) Glyph.CloudSun else Glyph.Cloud
            Kind.Cloudy -> Glyph.Cloud
            Kind.Fog -> Glyph.CloudFog
            Kind.Rain -> Glyph.CloudRain
            Kind.Snow -> Glyph.CloudSnow
            Kind.Thunder -> Glyph.CloudBolt
        }
        val condition = context.getString(when (w.kind) {
            Kind.Clear -> R.string.weather_clear
            Kind.PartlyCloudy -> R.string.weather_partly_cloudy
            Kind.Cloudy -> R.string.weather_cloudy
            Kind.Fog -> R.string.weather_fog
            Kind.Rain -> R.string.weather_rain
            Kind.Snow -> R.string.weather_snow
            Kind.Thunder -> R.string.weather_thunder
        })
        val range = if (w.high != null && w.low != null) context.getString(R.string.weather_high_low, w.high, w.low) else null
        return WeatherLine(glyph, w.celsius, place, listOfNotNull(condition, range).joinToString(" · "))
    }
}

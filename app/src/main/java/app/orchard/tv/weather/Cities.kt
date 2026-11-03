package app.orchard.tv.weather

import android.content.Context
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * China's cities and counties by their Chinese names (assets/cities-cn.tsv, from GeoNames via
 * tools/make-cities.py). Open-Meteo's geocoder knows few Chinese names: 商丘 and 泉州 found nothing,
 * and 安庆 found a village in Jiangsu instead of the city in Anhui (issue #187).
 */
object Cities {
    class City(val name: String, val aliases: List<String>, val province: String, val lat: Double, val lon: Double, val rank: Int, val ascii: String)

    private var all: List<City>? = null

    /** Off the main thread: the first call reads the asset. */
    fun load(context: Context): List<City> = all ?: synchronized(this) {
        all ?: context.assets.open("cities-cn.tsv").bufferedReader().useLines { lines ->
            lines.filter { !it.startsWith("#") && it.isNotBlank() }.map { line ->
                val c = line.split('\t')
                City(c[0], c[1].split(',').filter { it.isNotEmpty() }, c[2], c[3].toDouble(), c[4].toDouble(), c[5].toInt(), c[6])
            }.toList()
        }.also { all = it }
    }

    /**
     * The place typed on the phone page: "商丘", "商丘市", or with its province, "河南商丘" or "北京朝阳"
     * for one of several of that name. Of several, a city before a county; the table is in that order.
     */
    fun find(context: Context, typed: String): City? {
        val q = typed.filterNot { it.isWhitespace() }.removePrefix("中国")
        if (q.isEmpty() || q.any { it.code < 0x4e00 || it.code > 0x9fff }) return null
        val cities = load(context)
        fun inProvince(city: City) = city.province.isNotEmpty() && q.startsWith(city.province)
        fun rest(city: City) = short(q.removePrefix(city.province).trimStart('省', '市').removePrefix("自治区").removePrefix("壮族自治区"))
        // Names before aliases: some places have another place's name as an alias (北京朝阳 is one of 朝外's).
        return cities.firstOrNull { it.name == short(q) }
            ?: cities.firstOrNull { inProvince(it) && it.name == rest(it) }
            ?: cities.firstOrNull { short(q) in it.aliases }
            ?: cities.firstOrNull { inProvince(it) && rest(it) in it.aliases }
    }

    /** The listed place called [name] nearest to a point, if one is within [km]. */
    fun near(context: Context, name: String, lat: Double, lon: Double, km: Double, ascii: Boolean = false): City? =
        load(context)
            .filter { if (ascii) it.ascii.equals(name, ignoreCase = true) else it.name == short(name) }
            .minByOrNull { distanceKm(it.lat, it.lon, lat, lon) }
            ?.takeIf { distanceKm(it.lat, it.lon, lat, lon) <= km }

    /**
     * The most important listed place within [km] of a point (a province seat before a prefecture,
     * before a county), the nearest of those; null if none is that close.
     */
    fun mainNear(context: Context, lat: Double, lon: Double, km: Double): City? =
        load(context)
            .map { it to distanceKm(it.lat, it.lon, lat, lon) }
            .filter { it.second <= km }
            .minWithOrNull(compareBy<Pair<City, Double>>({ it.first.rank }, { it.second }))
            ?.first

    /** Same suffixes tools/make-cities.py takes off. */
    private val suffix = Regex("(壮族自治区|回族自治区|维吾尔自治区|特别行政区|自治区|自治州|自治县|地区|新区|省|市|县|区|盟|旗)$")

    private fun short(name: String): String = suffix.replace(name, "").takeIf { it.length >= 2 } ?: name

    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val x = (lon2 - lon1) * cos(Math.toRadians((lat1 + lat2) / 2))
        val y = lat2 - lat1
        return sqrt(x * x + y * y) * 111.2
    }
}

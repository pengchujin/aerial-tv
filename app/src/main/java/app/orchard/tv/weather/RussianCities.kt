package app.orchard.tv.weather

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object RussianCities {
    private val DICTIONARY = mapOf(
        "Moscow" to "Москва",
        "Saint Petersburg" to "Санкт-Петербург",
        "St. Petersburg" to "Санкт-Петербург",
        "St Petersburg" to "Санкт-Петербург",
        "Novosibirsk" to "Новосибирск",
        "Yekaterinburg" to "Екатеринбург",
        "Ekaterinburg" to "Екатеринбург",
        "Kazan" to "Казань",
        "Nizhny Novgorod" to "Нижний Новгород",
        "Chelyabinsk" to "Челябинск",
        "Krasnoyarsk" to "Красноярск",
        "Samara" to "Самара",
        "Ufa" to "Уфа",
        "Rostov-on-Don" to "Ростов-на-Дону",
        "Rostov" to "Ростов-на-Дону",
        "Omsk" to "Омск",
        "Krasnodar" to "Краснодар",
        "Voronezh" to "Воронеж",
        "Perm" to "Пермь",
        "Volgograd" to "Волгоград",
        "Saratov" to "Саратов",
        "Tyumen" to "Тюмень",
        "Tolyatti" to "Тольятти",
        "Barnaul" to "Барнаул",
        "Izhevsk" to "Ижевск",
        "Makhachkala" to "Махачкала",
        "Khabarovsk" to "Хабаровск",
        "Ulyanovsk" to "Ульяновск",
        "Irkutsk" to "Иркутск",
        "Vladivostok" to "Владивосток",
        "Yaroslavl" to "Ярославль",
        "Sevastopol" to "Севастополь",
        "Stavropol" to "Ставрополь",
        "Orenburg" to "Оренбург",
        "Tomsk" to "Томск",
        "Kemerovo" to "Кемерово",
        "Novokuznetsk" to "Новокузнецк",
        "Ryazan" to "Рязань",
        "Astrakhan" to "Астрахань",
        "Naberezhnye Chelny" to "Набережные Челны",
        "Penza" to "Пенза",
        "Kirov" to "Киров",
        "Lipetsk" to "Липецк",
        "Cheboksary" to "Чебоксары",
        "Balashikha" to "Балашиха",
        "Kaliningrad" to "Калининград",
        "Tula" to "Тула",
        "Kursk" to "Курск",
        "Sochi" to "Сочи",
        "Ulan-Ude" to "Улан-Удэ",
        "Tver" to "Тверь",
        "Magnitogorsk" to "Магнитогорск",
        "Ivanovo" to "Иваново",
        "Bryansk" to "Брянск",
        "Belgorod" to "Белгород",
        "Surgut" to "Сургут",
        "Vladimir" to "Владимир",
        "Chita" to "Чита",
        "Arkhangelsk" to "Архангельск",
        "Simferopol" to "Симферополь",
        "Kaluga" to "Калуга",
        "Smolensk" to "Смоленск",
        "Volzhsky" to "Волжский",
        "Yakutsk" to "Якутск",
        "Saransk" to "Саранск",
        "Cherepovets" to "Череповец",
        "Vologda" to "Вологда",
        "Vladikavkaz" to "Владикавказ",
        "Grozny" to "Грозный",
        "Murmansk" to "Мурманск",
        "Tambov" to "Тамбов",
        "Petrozavodsk" to "Петрозаводск",
        "Kostroma" to "Кострома",
        "Nizhnevartovsk" to "Нижневартовск",
        "Novorossiysk" to "Новороссийск",
        "Yoshkar-Ola" to "Йошкар-Ола",
        "Taganrog" to "Таганрог",
        "Komsomolsk-on-Amur" to "Комсомольск-на-Амуре",
        "Syktyvkar" to "Сыктывкар",
        "Nalchik" to "Нальчик",
        "Nizhnekamsk" to "Нижнекамск",
        "Shakhty" to "Шахты",
        "Dzerzhinsk" to "Дзержинск",
        "Bratsk" to "Братск",
        "Orsk" to "Орск",
        "Angarsk" to "Ангарск",
        "Engels" to "Энгельс",
        "Blagoveshchensk" to "Благовещенск",
        "Stary Oskol" to "Старый Оскол",
        "Veliky Novgorod" to "Великий Новгород",
        "Korolyov" to "Королёв",
        "Korolev" to "Королёв",
        "Pskov" to "Псков",
        "Mytishchi" to "Мытищи",
        "Biysk" to "Бийск",
        "Lyubertsy" to "Люберцы",
        "Prokopyevsk" to "Прокопьевск",
        "Yuzhno-Sakhalinsk" to "Южно-Сахалинск",
        "Abakan" to "Абакан",
        "Armavir" to "Армавир",
        "Norilsk" to "Норильск",
        "Petropavlovsk-Kamchatsky" to "Петропавловск-Камчатский",
        "Zlatoust" to "Златоуст",
        "Ussuriysk" to "Уссурийск",
        "Kamensk-Uralsky" to "Каменск-Уральский",
        "Novocherkassk" to "Новочеркасск",
        "Khimki" to "Химки",
        "Podolsk" to "Подольск",
        "Krasnogorsk" to "Красногорск",
        "Minsk" to "Минск",
        "Almaty" to "Алматы",
        "Astana" to "Астана",
        "Tashkent" to "Ташкент",
        "Baku" to "Баку",
        "Yerevan" to "Ереван",
        "Tbilisi" to "Тбилиси",
        "Bishkek" to "Бишкек",
        "Dushanbe" to "Душанбе",
        "Ashgabat" to "Ашхабад",
        "London" to "Лондон",
        "Paris" to "Париж",
        "New York" to "Нью-Йорк",
        "Berlin" to "Берлин",
        "Rome" to "Рим",
        "Madrid" to "Мадрид",
        "Tokyo" to "Токио",
        "Beijing" to "Пекин",
        "Dubai" to "Дубай",
        "Istanbul" to "Стамбул",
    )

    fun translate(name: String?): String? {
        if (name == null) return null
        val trimmed = name.trim()
        DICTIONARY[trimmed]?.let { return it }
        DICTIONARY.entries.firstOrNull { it.key.equals(trimmed, ignoreCase = true) }?.value?.let { return it }
        return name
    }

    /** Geocode via BigDataCloud or Open-Meteo off the main thread. */
    fun findCity(lat: Float, lon: Float, englishCity: String?): String? {
        translate(englishCity)?.takeIf { it != englishCity }?.let { return it }

        val bdc = runCatching {
            val url = URL("https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=$lat&longitude=$lon&localityLanguage=ru")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.inputStream.bufferedReader().use { r ->
                val json = JSONObject(r.readText())
                json.optString("city").ifBlank { json.optString("locality").ifBlank { null } }
            }
        }.getOrNull()
        if (!bdc.isNullOrBlank()) return bdc

        if (!englishCity.isNullOrBlank()) {
            val om = runCatching {
                val url = URL("https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(englishCity, "UTF-8")}&count=1&language=ru")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                conn.inputStream.bufferedReader().use { r ->
                    val json = JSONObject(r.readText())
                    json.optJSONArray("results")?.optJSONObject(0)?.optString("name")?.ifBlank { null }
                }
            }.getOrNull()
            if (!om.isNullOrBlank()) return om
        }

        return englishCity
    }
}
